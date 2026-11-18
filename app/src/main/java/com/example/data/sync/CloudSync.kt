package com.example.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.background.AutoBackupWorker
import com.example.data.local.BookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps several phones/tablets in step through the backup folder (pick a Google Drive folder and
 * the Drive app carries it between devices — no account setup here).
 *
 * The folder gets an "Inkvault-sync" subfolder with one small file per novel plus a manifest of
 * per-novel fingerprints, so a sync only reads and writes the novels that changed. Each novel's
 * file holds its details, chapter list and read marks, bookmarks and the exact reading
 * position, but not chapter text: a chapter opened on another device downloads itself on
 * demand. Merging is additive — nothing is lost if two devices were used offline — and the
 * most recent reading position wins.
 */
class CloudSync(private val context: Context, private val repository: NovelRepository) {

    data class Summary(
        val booksAdded: Int,
        val positionsUpdated: Int,
        val chaptersMarkedRead: Int,
        val removed: Int,
        /** Chapters whose text came from another device. */
        val chaptersFilled: Int = 0
    )

    private val mutex = Mutex()
    private val progressPrefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    /** On when a sync password is set (cloud), or when folder sync is switched on. */
    val isEnabled: Boolean get() = isCloud || (syncPrefs.getBoolean(KEY_ENABLED, false) && folder() != null)
    fun setEnabled(on: Boolean) = syncPrefs.edit().putBoolean(KEY_ENABLED, on).apply()

    // ---------------- cloud sync (password) ----------------

    private fun cloudCrypto(): SyncCrypto? = SyncCrypto.import(syncPrefs.getString(KEY_CLOUD, null))
    val isCloud: Boolean get() = cloudCrypto() != null && serviceUrl().isNotBlank()
    fun hasPassword(): Boolean = cloudCrypto() != null

    fun serviceUrl(): String = syncPrefs.getString(KEY_SERVICE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVICE_URL
    fun setServiceUrl(url: String) = syncPrefs.edit().putString(KEY_SERVICE_URL, url.trim()).apply()

    /**
     * Turns on cloud sync with [password] (the same on every phone) and syncs right away.
     * A new password means a different mailbox, so what this phone had pulled is forgotten.
     */
    suspend fun setPassword(password: String): Result<Summary> {
        val crypto = withContext(Dispatchers.Default) { SyncCrypto.fromPassword(password) }
        syncPrefs.edit().putString(KEY_CLOUD, crypto.export()).remove(KEY_PULLED + "_cloud").apply()
        return syncNow()
    }

    fun signOut() = syncPrefs.edit().remove(KEY_CLOUD).remove(KEY_PULLED + "_cloud").apply()

    /** Where the novels' sync files live: the cloud mailbox when a password is set, else the folder. */
    private interface Store {
        fun readRaw(name: String): String?
        fun read(name: String): JSONObject? = readRaw(name)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun write(name: String, text: String)
        fun legacy(): JSONObject? = null
        val id: String
    }

    private fun openStore(): Store {
        cloudCrypto()?.takeIf { serviceUrl().isNotBlank() }?.let { crypto ->
            val cloud = CloudStore(serviceUrl(), crypto)
            return object : Store {
                override val id = "cloud"
                override fun readRaw(name: String) = cloud.read(name)
                override fun write(name: String, text: String) = cloud.write(name, text)
            }
        }
        val tree = folder() ?: throw IllegalStateException("Set a sync password (or choose a backup folder) first")
        val dir = SyncFolder(tree)
        return object : Store {
            override val id = "folder"
            override fun readRaw(name: String) = dir.file(name)?.let { readText(it) }
            override fun write(name: String, text: String) = dir.write(name, text)
            override fun legacy() = dir.legacyFile()?.let { read(it) }
        }
    }
    fun lastSync(): Long = syncPrefs.getLong(KEY_LAST, 0L)
    fun lastError(): String? = syncPrefs.getString(KEY_ERROR, null)

    private fun folder(): Uri? =
        AutoBackupWorker.prefs(context).getString(AutoBackupWorker.KEY_TREE, null)?.let(Uri::parse)

    /** Records a removed novel so other devices drop it too instead of bringing it back. */
    fun recordRemoval(bookId: String) {
        val set = removals().apply { put(bookId, System.currentTimeMillis()) }
        syncPrefs.edit().putString(KEY_REMOVED, set.toString()).apply()
    }

    /** A novel restored from the Trash (or re-added) is live again everywhere. */
    fun clearRemoval(bookId: String) {
        val set = removals().apply { remove(bookId) }
        syncPrefs.edit().putString(KEY_REMOVED, set.toString()).apply()
        progressPrefs.edit().putLong("progress_at_$bookId", System.currentTimeMillis()).apply()
    }

    /** Automatic syncs (app opened/closed) at most every two minutes; "Sync now" always runs. */
    suspend fun syncIfDue() {
        if (!isEnabled) return
        if (System.currentTimeMillis() - lastSync() < 2 * 60 * 1000L) return
        syncNow()
    }

    private fun removals(): JSONObject =
        runCatching { JSONObject(syncPrefs.getString(KEY_REMOVED, "{}")!!) }.getOrDefault(JSONObject())

    /** Pull the other devices' changes, merge them in, then publish what changed here. */
    suspend fun syncNow(): Result<Summary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val dir = openStore()
                val pulledKey = if (dir.id == "cloud") KEY_PULLED + "_cloud" else KEY_PULLED
                // One-time (text copy v2): read every novel file again so this phone learns which
                // text bundles the other phone has.
                if (!syncPrefs.getBoolean(KEY_TEXT_V2, false)) {
                    syncPrefs.edit().remove(KEY_PULLED).remove(KEY_PULLED + "_cloud").putBoolean(KEY_TEXT_V2, true).apply()
                }
                var total = Summary(0, 0, 0, 0)

                // One-time upgrade from the old single-file sync.
                if (dir.id == "folder" && !syncPrefs.getBoolean(KEY_LEGACY_DONE, false)) {
                    dir.legacy()?.let { total += merge(it) }
                    syncPrefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
                }

                // Pull: only novels whose fingerprint differs from what was last seen here.
                val manifest = dir.read(MANIFEST) ?: JSONObject()
                val remoteStamps = manifest.optJSONObject("books") ?: JSONObject()
                val pulled = stamps(pulledKey)
                val changed = JSONArray()
                val changedStamps = HashMap<String, String>()
                for (id in remoteStamps.keys()) {
                    val stamp = remoteStamps.getString(id)
                    if (pulled.optString(id) == stamp) continue
                    // Not there yet, or an older copy still being served (the cloud store and
                    // Drive can lag a little)? Leave it for the next sync.
                    val raw = dir.readRaw(bookFileName(id)) ?: continue
                    if (com.example.util.ContentHash.of(raw) != stamp) continue
                    val book = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                    changed.put(book)
                    changedStamps[id] = stamp
                }
                total += merge(
                    JSONObject().put("books", changed)
                        .put("bookmarks", manifest.optJSONArray("bookmarks") ?: JSONArray())
                        .put("removed", manifest.optJSONObject("removed") ?: JSONObject())
                )
                // Chapter text is copied afterwards by [syncText] (in the background, resumable);
                // here we only note which bundles the other device has.
                for (i in 0 until changed.length()) {
                    val rb = changed.getJSONObject(i)
                    val id = rb.getString("id")
                    rb.optJSONObject("text")?.let { saveTextRecord("rtext", dir.id, id, it) }
                    pulled.put(id, changedStamps.getValue(id))
                }

                // Push: rewrite only the novels that differ from the folder's copy.
                val newStamps = JSONObject()
                for (b in repository.getAllBooks()) {
                    val json = bookJson(b).toString()
                    val stamp = com.example.util.ContentHash.of(json)
                    newStamps.put(b.id, stamp)
                    if (remoteStamps.optString(b.id) != stamp) dir.write(bookFileName(b.id), json)
                    pulled.put(b.id, stamp)
                }
                // The manifest is only rewritten when something in it changed (keeps the cloud
                // service well inside its free daily writes).
                val newManifest = JSONObject().put("version", 2)
                    .put("books", newStamps).put("bookmarks", bookmarksJson()).put("removed", removals())
                val oldComparable = JSONObject(manifest.toString()).apply { remove("savedAt") }.toString()
                if (newManifest.toString() != oldComparable) {
                    dir.write(MANIFEST, JSONObject(newManifest.toString()).put("savedAt", System.currentTimeMillis()).toString())
                }
                syncPrefs.edit()
                    .putString(pulledKey, pulled.toString())
                    .putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply()
                runCatching { com.example.background.TextSyncWorker.runNow(context) }
                total
            }.onFailure { e ->
                syncPrefs.edit().putString(KEY_ERROR, e.message ?: e::class.simpleName).apply()
            }
        }
    }

    private operator fun Summary.plus(o: Summary) = Summary(
        booksAdded + o.booksAdded, positionsUpdated + o.positionsUpdated,
        chaptersMarkedRead + o.chaptersMarkedRead, removed + o.removed, chaptersFilled + o.chaptersFilled
    )

    private fun stamps(key: String): JSONObject =
        runCatching { JSONObject(syncPrefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())

    // ---------------- file access ----------------

    /** The "Inkvault-sync" subfolder; its listing is read once per sync. */
    private inner class SyncFolder(private val tree: Uri) {
        private val resolver = context.contentResolver
        private val rootId = DocumentsContract.getTreeDocumentId(tree)
        private val dirUri: Uri = childOf(rootId, SUBFOLDER)
            ?: DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                DocumentsContract.Document.MIME_TYPE_DIR, SUBFOLDER
            ) ?: throw java.io.IOException("Could not create the $SUBFOLDER folder")
        private val files: MutableMap<String, Uri> = list(DocumentsContract.getDocumentId(dirUri))

        private fun list(parentId: String): MutableMap<String, Uri> {
            val out = HashMap<String, Uri>()
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
            return out
        }

        private fun childOf(parentId: String, name: String): Uri? = list(parentId)[name]

        fun legacyFile(): Uri? = childOf(rootId, LEGACY_FILE_NAME)

        fun file(name: String): Uri? = files[name]

        fun write(name: String, text: String) {
            val doc = files[name] ?: DocumentsContract.createDocument(resolver, dirUri, "application/json", name)
                ?.also { files[name] = it }
                ?: throw java.io.IOException("Could not create $name")
            resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray()) }
                ?: throw java.io.IOException("Could not write $name")
        }
    }

    private fun readText(doc: Uri): String? = runCatching {
        context.contentResolver.openInputStream(doc)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun read(doc: Uri): JSONObject? =
        readText(doc)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ---------------- snapshot ----------------

    private suspend fun bookJson(b: BookEntity): JSONObject {
        val rows = repository.getChapterSyncRows(b.id) // no text: fast even for big novels
        val chapters = JSONArray()
        for (c in rows) {
            chapters.put(
                JSONObject()
                    .put("id", c.id).put("chapterId", c.chapterId).put("n", c.chapterNumber)
                    .put("title", c.title).put("url", c.url)
                    .put("read", c.isRead).put("readAt", c.readAt ?: 0L)
                    .put("hidden", c.isArchived)
            )
        }
        val chapterId = progressPrefs.getString("progress_chapter_${b.id}", null) ?: b.lastReadChapterId
        return JSONObject()
            .put("id", b.id).put("url", b.url).put("title", b.title).put("author", b.author)
            .put("synopsis", b.synopsis).put("coverUrl", b.coverUrl ?: "").put("category", b.category)
            .put("lastReadChapterId", chapterId ?: "")
            .put("lastReadChapterNumber", b.lastReadChapterNumber)
            .put("para", chapterId?.let { progressPrefs.getInt("progress_para_${b.id}_$it", 0) } ?: 0)
            .put("progressAt", progressAt(b.id))
            .put("chapters", chapters)
            .put("text", textInfo(rows))
    }

    // ---------------- chapter text ----------------
    // A novel's downloaded chapters are spread over a few bundles (about 50 chapters each, by
    // chapter id), each with a fingerprint; only bundles whose fingerprint changed are sent.

    private fun bucketCount(chapters: Int): Int {
        var n = 1
        while (n * TEXT_PER_BUCKET < chapters && n < 64) n *= 2
        return n
    }

    private fun bucketOf(id: String, n: Int) = (id.hashCode() and 0x7fffffff) % n

    private fun bucketStamp(rows: List<Pair<String, String>>): String =
        if (rows.isEmpty()) "" else com.example.util.ContentHash.of(rows.sortedBy { it.first }.joinToString("\n") { "${it.first}:${it.second}" })

    /** {"n": bundles, "s": [fingerprint per bundle]} from text-free rows ("hash/length"). */
    private fun textInfo(rows: List<ChapterEntity>): JSONObject {
        val withText = rows.filter { !it.hash.endsWith("/0") }
        val n = bucketCount(withText.size)
        val groups = withText.groupBy { bucketOf(it.id, n) }
        val s = JSONArray()
        for (i in 0 until n) s.put(bucketStamp(groups[i].orEmpty().map { it.id to it.hash }))
        return JSONObject().put("n", n).put("s", s)
    }

    private fun textFileName(bookId: String, n: Int, i: Int) =
        "text_" + com.example.util.ContentHash.of(bookId).take(16) + "_${n}_$i.json"

    // Per novel and store: "rtext" = bundles the other device announced, "tpull" = bundles
    // copied in from it, "tpush" = bundles this device uploaded. Each is {"n": .., "s": [..]}
    // and is saved after every single bundle, so an interrupted copy picks up where it stopped.
    private fun textRecord(kind: String, storeId: String, bookId: String): JSONObject =
        runCatching { JSONObject(syncPrefs.getString("${kind}_${storeId}_$bookId", "{}")!!) }.getOrDefault(JSONObject())

    private fun saveTextRecord(kind: String, storeId: String, bookId: String, rec: JSONObject) =
        syncPrefs.edit().putString("${kind}_${storeId}_$bookId", rec.toString()).apply()

    /** [rec] with bundle [i] set to [stamp], starting a fresh record when the layout ([n]) differs. */
    private fun withBundle(rec: JSONObject, n: Int, i: Int, stamp: String): JSONObject {
        val out = if (rec.optInt("n") == n) JSONObject(rec.toString())
        else JSONObject().put("n", n).put("s", JSONArray().apply { repeat(n) { put("") } })
        out.getJSONArray("s").put(i, stamp)
        return out
    }

    private fun stampAt(rec: JSONObject, n: Int, i: Int): String =
        if (rec.optInt("n") == n) rec.optJSONArray("s")?.optString(i).orEmpty() else ""

    data class TextStatus(val running: Boolean, val message: String, val error: String?)

    fun textStatus() = TextStatus(
        syncPrefs.getBoolean(KEY_TEXT_RUNNING, false),
        syncPrefs.getString(KEY_TEXT_MSG, null).orEmpty(),
        syncPrefs.getString(KEY_TEXT_ERROR, null)
    )

    private fun setTextStatus(running: Boolean, message: String) =
        syncPrefs.edit().putBoolean(KEY_TEXT_RUNNING, running).putString(KEY_TEXT_MSG, message).apply()

    private val textMutex = Mutex()

    /**
     * Copies chapter text both ways, one bundle at a time: downloads the other device's bundles
     * this device hasn't applied, then uploads this device's bundles the other hasn't got.
     * Safe to stop at any point; the next run continues. Returns chapters filled in here.
     */
    suspend fun syncText(): Result<Int> = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext Result.success(0)
        textMutex.withLock {
            runCatching {
                val dir = openStore()
                val books = repository.getAllBooks()
                var filled = 0
                var uploaded = 0
                books.forEachIndexed { bi, b ->
                    setTextStatus(true, "Copying chapters: novel ${bi + 1} of ${books.size} (${b.title.take(40)})")

                    // Download what the other device has.
                    val remote = textRecord("rtext", dir.id, b.id)
                    val rn = remote.optInt("n")
                    val rs = remote.optJSONArray("s")
                    var pulledRec = textRecord("tpull", dir.id, b.id)
                    if (rn > 0 && rs != null) for (i in 0 until rn) {
                        val want = rs.optString(i)
                        if (want.isEmpty() || stampAt(pulledRec, rn, i) == want) continue
                        val bundle = dir.read(textFileName(b.id, rn, i)) ?: continue // not uploaded yet
                        val items = bundle.optJSONArray("chapters") ?: continue
                        val list = (0 until items.length()).map { items.getJSONObject(it) }
                        if (bucketStamp(list.map { it.getString("id") to it.getString("h") }) != want) continue // older copy
                        for (o in list) {
                            val local = repository.getChapter(o.getString("id")) ?: continue
                            if (local.isDeleted) continue
                            val content = o.getString("c")
                            val at = o.optLong("at")
                            if (local.content.isBlank() || (at > local.downloadedAt && local.content != content)) {
                                repository.setChapterText(local.id, content, o.optString("hash"), at)
                                filled++
                            }
                        }
                        pulledRec = withBundle(pulledRec, rn, i, want)
                        saveTextRecord("tpull", dir.id, b.id, pulledRec)
                    }

                    // Upload what the other device hasn't got.
                    val rows = repository.getChapterSyncRows(b.id).filter { !it.hash.endsWith("/0") }
                    val local = textInfo(rows)
                    val n = local.getInt("n")
                    val ls = local.getJSONArray("s")
                    var pushedRec = textRecord("tpush", dir.id, b.id)
                    val groups = rows.groupBy { bucketOf(it.id, n) }
                    for (i in 0 until n) {
                        val have = ls.getString(i)
                        if (have.isEmpty() || stampAt(pushedRec, n, i) == have) continue
                        // The other device already has exactly this bundle (e.g. we copied it from there).
                        if (stampAt(remote, n, i) == have) {
                            pushedRec = withBundle(pushedRec, n, i, have); saveTextRecord("tpush", dir.id, b.id, pushedRec); continue
                        }
                        val out = JSONArray()
                        for (r in groups[i].orEmpty()) {
                            val full = repository.getChapter(r.id) ?: continue
                            out.put(JSONObject().put("id", full.id).put("c", full.content).put("hash", full.hash).put("h", r.hash).put("at", full.downloadedAt))
                        }
                        dir.write(textFileName(b.id, n, i), JSONObject().put("chapters", out).toString())
                        uploaded++
                        pushedRec = withBundle(pushedRec, n, i, have)
                        saveTextRecord("tpush", dir.id, b.id, pushedRec)
                    }
                }
                setTextStatus(false, "All chapters copied" + if (filled > 0) " (received $filled)" else "")
                syncPrefs.edit().remove(KEY_TEXT_ERROR).putLong(KEY_TEXT_LAST, System.currentTimeMillis()).apply()
                filled
            }.onFailure { e ->
                setTextStatus(false, "Copying chapters paused")
                syncPrefs.edit().putString(KEY_TEXT_ERROR, e.message ?: e::class.simpleName).apply()
            }
        }
    }

    private suspend fun bookmarksJson(): JSONArray {
        val bookmarks = JSONArray()
        for (m in repository.getAllBookmarks()) {
            bookmarks.put(
                JSONObject().put("id", m.id).put("bookId", m.bookId).put("chapterId", m.chapterId)
                    .put("para", m.paragraphIndex).put("text", m.text).put("note", m.note).put("at", m.timestamp)
            )
        }
        return bookmarks
    }

    /** Everything in one object (the old single-file format; [merge] reads both). */
    internal suspend fun snapshot(): JSONObject {
        val books = JSONArray()
        for (b in repository.getAllBooks()) books.put(bookJson(b))
        return JSONObject()
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put("books", books)
            .put("bookmarks", bookmarksJson())
            .put("removed", removals())
    }

    private fun progressAt(bookId: String) = progressPrefs.getLong("progress_at_$bookId", 0L)

    // ---------------- merge ----------------

    internal suspend fun merge(remote: JSONObject): Summary {
        var added = 0
        var positions = 0
        var marked = 0
        var removed = 0

        // Removals from other devices, newer than our last change to that book.
        val remoteRemovals = remote.optJSONObject("removed") ?: JSONObject()
        val localRemovals = removals()
        for (id in remoteRemovals.keys()) {
            val at = remoteRemovals.getLong(id)
            if (!localRemovals.has(id)) localRemovals.put(id, at)
            val book = repository.getBook(id)
            if (book != null && !book.isDeleted && at > progressAt(id)) {
                repository.softDeleteBook(id) // to the Trash, so a mistake can still be undone
                removed++
            }
        }
        syncPrefs.edit().putString(KEY_REMOVED, localRemovals.toString()).apply()

        val books = remote.optJSONArray("books") ?: JSONArray()
        for (i in 0 until books.length()) {
            val rb = books.getJSONObject(i)
            val id = rb.getString("id")
            if (localRemovals.has(id) && localRemovals.getLong(id) >= rb.optLong("progressAt")) continue

            var local = repository.getBook(id)
            val remoteChapters = rb.optJSONArray("chapters") ?: JSONArray()
            if (local == null) {
                local = BookEntity(
                    id = id, url = rb.getString("url"), title = rb.optString("title"),
                    author = rb.optString("author"), synopsis = rb.optString("synopsis"),
                    coverUrl = rb.optString("coverUrl").ifBlank { null }, coverLocalPath = null,
                    lastReadChapterId = null, totalChapters = remoteChapters.length(),
                    category = rb.optString("category", "Reading")
                )
                repository.insertBook(local)
                added++
            }

            // Chapters: add the ones this device has never seen (text downloads when opened),
            // and carry read marks over.
            // Hidden and trashed chapters count as known: re-adding them would unhide them and
            // wipe their text.
            val localChapters = repository.getAllChaptersIncludingHidden(id).associateBy { it.id }
            val newOnes = ArrayList<ChapterEntity>()
            for (j in 0 until remoteChapters.length()) {
                val rc = remoteChapters.getJSONObject(j)
                val cid = rc.getString("id")
                val lc = localChapters[cid]
                if (lc == null) {
                    newOnes += ChapterEntity(
                        id = cid, bookId = id, chapterId = rc.optString("chapterId"),
                        chapterNumber = rc.optInt("n"), title = rc.optString("title"), url = rc.optString("url"),
                        content = "", hash = "", isRead = rc.optBoolean("read"),
                        readAt = rc.optLong("readAt").takeIf { it > 0 },
                        isArchived = rc.optBoolean("hidden")
                    )
                } else if (rc.optBoolean("read") && !lc.isRead) {
                    repository.updateChapterReadStatus(cid, true, rc.optLong("readAt").takeIf { it > 0 } ?: System.currentTimeMillis())
                    marked++
                }
            }
            if (newOnes.isNotEmpty()) {
                repository.insertChapters(newOnes)
                repository.updateBook(local.copy(totalChapters = repository.getChapters(id).size))
            }

            // Reading position: whichever device read most recently wins.
            val remoteAt = rb.optLong("progressAt")
            val chapterId = rb.optString("lastReadChapterId")
            if (remoteAt > progressAt(id) && chapterId.isNotBlank()) {
                val fresh = repository.getBook(id) ?: local
                repository.updateBook(
                    fresh.copy(
                        lastReadChapterId = chapterId,
                        lastReadChapterNumber = maxOf(fresh.lastReadChapterNumber, rb.optInt("lastReadChapterNumber"))
                    )
                )
                progressPrefs.edit()
                    .putString("progress_chapter_$id", chapterId)
                    .putInt("progress_para_${id}_$chapterId", rb.optInt("para"))
                    .putLong("progress_at_$id", remoteAt)
                    .apply()
                positions++
            }
        }

        val bookmarks = remote.optJSONArray("bookmarks") ?: JSONArray()
        val known = repository.getAllBookmarks().map { it.id }.toSet()
        val missing = (0 until bookmarks.length()).map { bookmarks.getJSONObject(it) }
            .filter { it.getString("id") !in known && repository.getBook(it.getString("bookId")) != null }
            .map {
                BookmarkEntity(
                    id = it.getString("id"), bookId = it.getString("bookId"), chapterId = it.getString("chapterId"),
                    paragraphIndex = it.optInt("para"), text = it.optString("text"), note = it.optString("note"),
                    timestamp = it.optLong("at")
                )
            }
        if (missing.isNotEmpty()) repository.insertBookmarks(missing)

        return Summary(added, positions, marked, removed)
    }

    companion object {
        const val LEGACY_FILE_NAME = "NovelHoarder-sync.json"
        const val SUBFOLDER = "Inkvault-sync"
        private const val TEXT_PER_BUCKET = 50
        private const val KEY_TEXT_RUNNING = "text_running"
        private const val KEY_TEXT_MSG = "text_message"
        private const val KEY_TEXT_ERROR = "text_error"
        private const val KEY_TEXT_LAST = "text_last"
        private const val KEY_TEXT_V2 = "text_v2_reset"
        private const val MANIFEST = "manifest.json"
        private const val KEY_PULLED = "pulled_stamps"
        private const val KEY_LEGACY_DONE = "legacy_imported"
        private const val KEY_CLOUD = "cloud_crypto"
        private const val KEY_SERVICE_URL = "service_url"
        /** The Inkvault sync service (sync-server/ in the repo), on the owner's Cloudflare account. */
        const val DEFAULT_SERVICE_URL = "https://inkvault-sync.ahmeddahirgutale.workers.dev"

        /** Book ids can hold any character, so files are named by a fingerprint of the id. */
        fun bookFileName(id: String) = "book_" + com.example.util.ContentHash.of(id) + ".json"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_sync"
        private const val KEY_ERROR = "last_error"
        private const val KEY_REMOVED = "removed"
    }
}
