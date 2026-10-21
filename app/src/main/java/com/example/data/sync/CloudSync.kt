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
 * Keeps several phones/tablets in step through one small file in the backup folder (pick a
 * Google Drive folder and the Drive app carries it between devices — no account setup here).
 *
 * The file holds the library, chapter list and read marks, bookmarks and the exact reading
 * position, but not chapter text: a chapter opened on another device downloads itself on
 * demand. Merging is additive — nothing is lost if two devices were used offline — and the
 * most recent reading position wins.
 */
class CloudSync(private val context: Context, private val repository: NovelRepository) {

    data class Summary(val booksAdded: Int, val positionsUpdated: Int, val chaptersMarkedRead: Int, val removed: Int)

    private val mutex = Mutex()
    private val progressPrefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)

    val isEnabled: Boolean get() = syncPrefs.getBoolean(KEY_ENABLED, false) && folder() != null
    fun setEnabled(on: Boolean) = syncPrefs.edit().putBoolean(KEY_ENABLED, on).apply()
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

    /** Pull the other devices' state, merge it in, then publish the merged state. */
    suspend fun syncNow(): Result<Summary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val tree = folder() ?: throw IllegalStateException("Choose a backup folder first")
                val doc = findOrCreate(tree)
                val remote = read(doc)
                val summary = if (remote != null) merge(remote) else Summary(0, 0, 0, 0)
                write(doc, snapshot())
                syncPrefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply()
                summary
            }.onFailure { e ->
                syncPrefs.edit().putString(KEY_ERROR, e.message ?: e::class.simpleName).apply()
            }
        }
    }

    // ---------------- file access ----------------

    private fun findOrCreate(tree: Uri): Uri {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        return DocumentsContract.createDocument(resolver, parent, "application/json", FILE_NAME)
            ?: throw java.io.IOException("Could not create $FILE_NAME in the folder")
    }

    private fun read(doc: Uri): JSONObject? = runCatching {
        context.contentResolver.openInputStream(doc)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun write(doc: Uri, json: JSONObject) {
        context.contentResolver.openOutputStream(doc, "wt")?.use { it.write(json.toString().toByteArray()) }
            ?: throw java.io.IOException("Could not write $FILE_NAME")
    }

    // ---------------- snapshot ----------------

    internal suspend fun snapshot(): JSONObject {
        val books = JSONArray()
        for (b in repository.getAllBooks()) {
            val chapters = JSONArray()
            for (c in repository.getChapters(b.id)) {
                if (c.isDeleted) continue
                chapters.put(
                    JSONObject()
                        .put("id", c.id).put("chapterId", c.chapterId).put("n", c.chapterNumber)
                        .put("title", c.title).put("url", c.url)
                        .put("read", c.isRead).put("readAt", c.readAt ?: 0L)
                )
            }
            val chapterId = progressPrefs.getString("progress_chapter_${b.id}", null) ?: b.lastReadChapterId
            books.put(
                JSONObject()
                    .put("id", b.id).put("url", b.url).put("title", b.title).put("author", b.author)
                    .put("synopsis", b.synopsis).put("coverUrl", b.coverUrl ?: "").put("category", b.category)
                    .put("lastReadChapterId", chapterId ?: "")
                    .put("lastReadChapterNumber", b.lastReadChapterNumber)
                    .put("para", chapterId?.let { progressPrefs.getInt("progress_para_${b.id}_$it", 0) } ?: 0)
                    .put("progressAt", progressAt(b.id))
                    .put("chapters", chapters)
            )
        }
        val bookmarks = JSONArray()
        for (m in repository.getAllBookmarks()) {
            bookmarks.put(
                JSONObject().put("id", m.id).put("bookId", m.bookId).put("chapterId", m.chapterId)
                    .put("para", m.paragraphIndex).put("text", m.text).put("note", m.note).put("at", m.timestamp)
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put("books", books)
            .put("bookmarks", bookmarks)
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
            val localChapters = repository.getChapters(id).associateBy { it.id }
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
                        readAt = rc.optLong("readAt").takeIf { it > 0 }
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
        const val FILE_NAME = "NovelHoarder-sync.json"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST = "last_sync"
        private const val KEY_ERROR = "last_error"
        private const val KEY_REMOVED = "removed"
    }
}
