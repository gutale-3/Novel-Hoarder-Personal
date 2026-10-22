package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.local.*
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full Offline Backup Archive & Restore Engine.
 * Packages all database tables (Books, Chapters, Bookmarks, Glossaries, Reading Sessions, Replacement Rules)
 * and cover images into a single compressed .nhbackup / .zip archive.
 * Also seamlessly supports restoring from legacy raw JSON backups.
 */
object BackupRestoreManager {

    private const val CHAPTER_ENTRY_PREFIX = "chapters/"

    private fun chapterToJson(ch: ChapterEntity) = JSONObject().apply {
        put("id", ch.id)
        put("bookId", ch.bookId)
        put("chapterId", ch.chapterId)
        put("chapterNumber", ch.chapterNumber)
        put("title", ch.title)
        put("url", ch.url)
        put("content", ch.content)
        put("hash", ch.hash)
        put("isRead", ch.isRead)
        put("readAt", ch.readAt ?: 0L)
        put("isArchived", ch.isArchived)
        put("isDeleted", ch.isDeleted)
        put("downloadedAt", ch.downloadedAt)
    }

    private fun chapterFromJson(c: JSONObject, index: Int) = ChapterEntity(
        id = c.getString("id"),
        bookId = c.getString("bookId"),
        chapterId = c.optString("chapterId", "ch_$index"),
        chapterNumber = c.optInt("chapterNumber", index + 1),
        title = c.optString("title", "Chapter ${index + 1}"),
        url = c.optString("url", ""),
        content = c.optString("content", ""),
        hash = c.optString("hash", ""),
        isRead = c.optBoolean("isRead", false),
        readAt = c.optLong("readAt", 0L).takeIf { it > 0 },
        isArchived = c.optBoolean("isArchived", false),
        isDeleted = c.optBoolean("isDeleted", false),
        downloadedAt = c.optLong("downloadedAt", System.currentTimeMillis())
    )

    /**
     * Writes a backup ZIP: library metadata, then one "chapters/<n>.jsonl" entry per novel (one
     * chapter per line, one novel in memory at a time), then covers.
     */
    private suspend fun writeArchive(context: Context, repository: NovelRepository, out: java.io.OutputStream) {
        val meta = generateBackupMetadataJson(repository, includeChapters = false)
        ZipOutputStream(BufferedOutputStream(out)).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("backup_metadata.json"))
            zipOut.write(meta.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            repository.getAllBooks().forEachIndexed { i, book ->
                zipOut.putNextEntry(ZipEntry("$CHAPTER_ENTRY_PREFIX$i.jsonl"))
                for (ch in repository.getChapters(book.id)) {
                    zipOut.write((chapterToJson(ch).toString() + "\n").toByteArray(Charsets.UTF_8))
                }
                zipOut.closeEntry()
            }

            val coversDir = File(context.filesDir, "covers")
            if (coversDir.exists() && coversDir.isDirectory) {
                coversDir.listFiles()?.forEach { coverFile ->
                    if (coverFile.isFile) {
                        zipOut.putNextEntry(ZipEntry("covers/${coverFile.name}"))
                        coverFile.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Reads a backup ZIP: metadata (old backups also carry chapters inside it), covers, and
     * per-novel chapter entries, which are staged to temporary files and inserted in batches
     * after the novels exist (chapters reference their novel).
     */
    private suspend fun readArchive(
        context: Context,
        repository: NovelRepository,
        open: () -> java.io.InputStream?
    ): Int? {
        var jsonString: String? = null
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val staging = File(context.cacheDir, "restore_chapters").apply { deleteRecursively(); mkdirs() }
        try {
            open()?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    var staged = 0
                    while (entry != null) {
                        when {
                            entry.name.startsWith(CHAPTER_ENTRY_PREFIX) && entry.name.endsWith(".jsonl") ->
                                File(staging, "${staged++}.jsonl").outputStream().use { zipIn.copyTo(it) }
                            entry.name == "backup_metadata.json" || entry.name.endsWith(".json") -> {
                                val baos = ByteArrayOutputStream()
                                zipIn.copyTo(baos)
                                jsonString = baos.toString("UTF-8")
                            }
                            entry.name.startsWith("covers/") && !entry.isDirectory ->
                                File(coversDir, File(entry.name).name).outputStream().use { zipIn.copyTo(it) }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }
            val json = jsonString ?: return null
            val books = importJsonString(repository, json)
            staging.listFiles()?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }?.forEach { file ->
                val batch = ArrayList<ChapterEntity>(300)
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEachIndexed { i, line ->
                        batch += chapterFromJson(JSONObject(line), i)
                        if (batch.size >= 300) { repository.insertChapters(batch.toList()); batch.clear() }
                    }
                }
                if (batch.isNotEmpty()) repository.insertChapters(batch)
            }
            return books
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun createFullBackupZip(
        context: Context,
        repository: NovelRepository,
        outputUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(outputUri)
                ?: throw java.io.IOException("Could not open the backup file for writing")
            writeArchive(context, repository, out)
            Result.success(repository.getAllBooks().size)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * @param includeChapters false for ZIP backups, which stream chapters into per-novel entries
     * instead (a whole library as one string runs out of memory on large libraries).
     */
    suspend fun generateBackupMetadataJson(
        repository: NovelRepository,
        includeChapters: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val books = repository.getAllBooks()
        val allChapters = mutableListOf<ChapterEntity>()
        if (includeChapters) for (book in books) {
            allChapters.addAll(repository.getChapters(book.id))
        }
        val bookmarks = repository.getAllBookmarks()
        val glossaries = repository.getAllGlossaries()
        val readingSessions = repository.getAllReadingSessions()
        val replacementRules = repository.getAllReplacementRules()

        val root = JSONObject().apply {
            put("format", "novel_hoarder_archive")
            put("version", if (includeChapters) 2 else 3)
            put("chaptersInEntries", !includeChapters)
            put("exportedAt", System.currentTimeMillis())

            // Books
            val booksArray = JSONArray()
            for (book in books) {
                booksArray.put(JSONObject().apply {
                    put("id", book.id)
                    put("url", book.url)
                    put("title", book.title)
                    put("author", book.author)
                    put("synopsis", book.synopsis)
                    put("coverUrl", book.coverUrl ?: "")
                    put("coverLocalPath", book.coverLocalPath ?: "")
                    put("lastReadChapterId", book.lastReadChapterId ?: "")
                    put("totalChapters", book.totalChapters)
                    put("autoArchiveHours", book.autoArchiveHours)
                    put("isDeleted", book.isDeleted)
                    put("updatedAt", book.updatedAt)
                })
            }
            put("books", booksArray)

            // Chapters
            val chaptersArray = JSONArray()
            for (ch in allChapters) {
                chaptersArray.put(JSONObject().apply {
                    put("id", ch.id)
                    put("bookId", ch.bookId)
                    put("chapterId", ch.chapterId)
                    put("chapterNumber", ch.chapterNumber)
                    put("title", ch.title)
                    put("url", ch.url)
                    put("content", ch.content)
                    put("hash", ch.hash)
                    put("isRead", ch.isRead)
                    put("readAt", ch.readAt ?: 0L)
                    put("isArchived", ch.isArchived)
                    put("isDeleted", ch.isDeleted)
                    put("downloadedAt", ch.downloadedAt)
                })
            }
            put("chapters", chaptersArray)

            // Bookmarks
            val bookmarksArray = JSONArray()
            for (bm in bookmarks) {
                bookmarksArray.put(JSONObject().apply {
                    put("id", bm.id)
                    put("bookId", bm.bookId)
                    put("chapterId", bm.chapterId)
                    put("paragraphIndex", bm.paragraphIndex)
                    put("text", bm.text)
                    put("note", bm.note)
                    put("timestamp", bm.timestamp)
                })
            }
            put("bookmarks", bookmarksArray)

            // Glossaries
            val glossariesArray = JSONArray()
            for (gl in glossaries) {
                glossariesArray.put(JSONObject().apply {
                    put("id", gl.id)
                    put("bookId", gl.bookId)
                    put("originalText", gl.originalText)
                    put("replacementText", gl.replacementText)
                })
            }
            put("glossaries", glossariesArray)

            // Reading Sessions
            val sessionsArray = JSONArray()
            for (sess in readingSessions) {
                sessionsArray.put(JSONObject().apply {
                    put("id", sess.id)
                    put("bookId", sess.bookId)
                    put("chapterId", sess.chapterId)
                    put("date", sess.date)
                    put("durationSeconds", sess.durationSeconds)
                    put("wordsRead", sess.wordsRead)
                    put("timestamp", sess.timestamp)
                })
            }
            put("readingSessions", sessionsArray)

            // Text Replacement Rules
            val rulesArray = JSONArray()
            for (rule in replacementRules) {
                rulesArray.put(JSONObject().apply {
                    put("id", rule.id)
                    put("bookId", rule.bookId ?: "")
                    put("pattern", rule.pattern)
                    put("replacement", rule.replacement)
                    put("isRegex", rule.isRegex)
                    put("isCaseSensitive", rule.isCaseSensitive)
                    put("isEnabled", rule.isEnabled)
                    put("createdAt", rule.createdAt)
                })
            }
            put("replacementRules", rulesArray)
        }

        root.toString(2)
    }

    suspend fun restoreFullBackupZip(
        context: Context,
        repository: NovelRepository,
        inputUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // 1. A ZIP backup (new streamed format or the older single-file one)
            val fromZip = runCatching {
                readArchive(context, repository) { context.contentResolver.openInputStream(inputUri) }
            }.getOrNull()
            if (fromZip != null) return@withContext Result.success(fromZip)

            // 2. Otherwise a raw JSON export
            val jsonString = context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            if (jsonString.isNullOrBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Invalid or empty backup file"))
            }
            Result.success(importJsonString(repository, jsonString))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun importJsonString(repository: NovelRepository, jsonString: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonString)
        val booksArray = root.optJSONArray("books") ?: JSONArray()
        var importedBooksCount = 0

        val booksList = mutableListOf<BookEntity>()
        val chaptersList = mutableListOf<ChapterEntity>()
        val glossariesList = mutableListOf<GlossaryEntity>()
        val bookmarksList = mutableListOf<BookmarkEntity>()
        val sessionsList = mutableListOf<ReadingSessionEntity>()
        val rulesList = mutableListOf<TextReplacementRuleEntity>()

        for (i in 0 until booksArray.length()) {
            val b = booksArray.getJSONObject(i)
            val bookId = b.getString("id")
            val book = BookEntity(
                id = bookId,
                url = b.optString("url", ""),
                title = b.getString("title"),
                author = b.optString("author", "Unknown Author"),
                synopsis = b.optString("synopsis", ""),
                coverUrl = b.optString("coverUrl", "").takeIf { it.isNotEmpty() },
                coverLocalPath = b.optString("coverLocalPath", "").takeIf { it.isNotEmpty() },
                lastReadChapterId = b.optString("lastReadChapterId", "").takeIf { it.isNotEmpty() },
                totalChapters = b.optInt("totalChapters", 0),
                autoArchiveHours = b.optInt("autoArchiveHours", 0),
                isDeleted = b.optBoolean("isDeleted", false),
                updatedAt = b.optLong("updatedAt", System.currentTimeMillis())
            )
            booksList.add(book)
            importedBooksCount++

            // Check if chapters/glossaries are nested inside this book object (legacy format)
            val nestedChapters = b.optJSONArray("chapters")
            if (nestedChapters != null) {
                for (j in 0 until nestedChapters.length()) {
                    val c = nestedChapters.getJSONObject(j)
                    chaptersList.add(
                        ChapterEntity(
                            id = c.optString("id", "${bookId}_ch_${j+1}"),
                            bookId = bookId,
                            chapterId = c.optString("chapterId", "ch_${j+1}"),
                            chapterNumber = c.optInt("chapterNumber", j + 1),
                            title = c.optString("title", "Chapter ${j+1}"),
                            url = c.optString("url", ""),
                            content = c.optString("content", ""),
                            hash = c.optString("hash", ""),
                            isRead = c.optBoolean("isRead", false),
                            readAt = if (c.has("readAt") && c.getLong("readAt") > 0) c.getLong("readAt") else null,
                            isArchived = c.optBoolean("isArchived", false),
                            isDeleted = c.optBoolean("isDeleted", false)
                        )
                    )
                }
            }

            val nestedGlossaries = b.optJSONArray("glossaries")
            if (nestedGlossaries != null) {
                for (j in 0 until nestedGlossaries.length()) {
                    val gl = nestedGlossaries.getJSONObject(j)
                    glossariesList.add(
                        GlossaryEntity(
                            id = 0,
                            bookId = bookId,
                            originalText = gl.getString("originalText"),
                            replacementText = gl.getString("replacementText")
                        )
                    )
                }
            }
        }

        // Top-level chapters
        val topChapters = root.optJSONArray("chapters")
        if (topChapters != null) {
            for (i in 0 until topChapters.length()) {
                val c = topChapters.getJSONObject(i)
                chaptersList.add(
                    ChapterEntity(
                        id = c.getString("id"),
                        bookId = c.getString("bookId"),
                        chapterId = c.optString("chapterId", "ch_$i"),
                        chapterNumber = c.optInt("chapterNumber", i + 1),
                        title = c.getString("title"),
                        url = c.optString("url", ""),
                        content = c.optString("content", ""),
                        hash = c.optString("hash", ""),
                        isRead = c.optBoolean("isRead", false),
                        readAt = if (c.has("readAt") && c.getLong("readAt") > 0) c.getLong("readAt") else null,
                        isArchived = c.optBoolean("isArchived", false),
                        isDeleted = c.optBoolean("isDeleted", false)
                    )
                )
            }
        }

        // Top-level bookmarks
        val topBookmarks = root.optJSONArray("bookmarks")
        if (topBookmarks != null) {
            for (i in 0 until topBookmarks.length()) {
                val bm = topBookmarks.getJSONObject(i)
                bookmarksList.add(
                    BookmarkEntity(
                        id = bm.getString("id"),
                        bookId = bm.getString("bookId"),
                        chapterId = bm.getString("chapterId"),
                        paragraphIndex = bm.getInt("paragraphIndex"),
                        text = bm.getString("text"),
                        note = bm.optString("note", ""),
                        timestamp = bm.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        }

        // Top-level glossaries
        val topGlossaries = root.optJSONArray("glossaries")
        if (topGlossaries != null) {
            for (i in 0 until topGlossaries.length()) {
                val gl = topGlossaries.getJSONObject(i)
                glossariesList.add(
                    GlossaryEntity(
                        id = 0,
                        bookId = gl.getString("bookId"),
                        originalText = gl.getString("originalText"),
                        replacementText = gl.getString("replacementText")
                    )
                )
            }
        }

        // Top-level reading sessions
        val topSessions = root.optJSONArray("readingSessions")
        if (topSessions != null) {
            for (i in 0 until topSessions.length()) {
                val s = topSessions.getJSONObject(i)
                sessionsList.add(
                    ReadingSessionEntity(
                        id = 0,
                        bookId = s.getString("bookId"),
                        chapterId = s.getString("chapterId"),
                        date = s.getString("date"),
                        durationSeconds = s.getLong("durationSeconds"),
                        wordsRead = s.getInt("wordsRead"),
                        timestamp = s.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        }

        // Top-level text replacement rules
        val topRules = root.optJSONArray("replacementRules")
        if (topRules != null) {
            for (i in 0 until topRules.length()) {
                val r = topRules.getJSONObject(i)
                rulesList.add(
                    TextReplacementRuleEntity(
                        id = 0,
                        bookId = r.optString("bookId", "").takeIf { it.isNotEmpty() },
                        pattern = r.getString("pattern"),
                        replacement = r.getString("replacement"),
                        isRegex = r.optBoolean("isRegex", false),
                        isCaseSensitive = r.optBoolean("isCaseSensitive", false),
                        isEnabled = r.optBoolean("isEnabled", true),
                        createdAt = r.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }

        // Bulk insert to repository
        if (booksList.isNotEmpty()) repository.insertBooks(booksList)
        if (chaptersList.isNotEmpty()) repository.insertChapters(chaptersList)
        if (bookmarksList.isNotEmpty()) repository.insertBookmarks(bookmarksList)
        if (glossariesList.isNotEmpty()) repository.insertGlossaries(glossariesList)
        if (sessionsList.isNotEmpty()) repository.insertReadingSessions(sessionsList)
        if (rulesList.isNotEmpty()) repository.insertReplacementRules(rulesList)

        importedBooksCount
    }

    suspend fun createLocalAutoBackup(
        context: Context,
        repository: NovelRepository
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val backupsDir = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            val backupFile = File(backupsDir, "backup_${sdf.format(java.util.Date())}.zip")
            backupFile.outputStream().use { writeArchive(context, repository, it) }
            Result.success(backupFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun listLocalBackups(context: Context): List<File> {
        val backupsDir = File(context.filesDir, "backups")
        if (!backupsDir.exists() || !backupsDir.isDirectory) return emptyList()
        return backupsDir.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteLocalBackup(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restoreLocalBackup(
        context: Context,
        repository: NovelRepository,
        backupFile: File
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!backupFile.exists()) {
                return@withContext Result.failure(IllegalArgumentException("Backup file not found"))
            }
            val books = readArchive(context, repository) { backupFile.inputStream() }
                ?: return@withContext Result.failure(IllegalArgumentException("No readable data in backup file"))
            Result.success(books)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

