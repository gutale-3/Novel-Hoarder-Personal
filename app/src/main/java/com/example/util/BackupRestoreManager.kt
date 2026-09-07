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

    suspend fun createFullBackupZip(
        context: Context,
        repository: NovelRepository,
        outputUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val jsonString = generateBackupMetadataJson(repository)

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    // 1. Write metadata.json entry
                    val metaEntry = ZipEntry("backup_metadata.json")
                    zipOut.putNextEntry(metaEntry)
                    zipOut.write(jsonString.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()

                    // 2. Include any cached cover images from filesDir
                    val coversDir = File(context.filesDir, "covers")
                    if (coversDir.exists() && coversDir.isDirectory) {
                        coversDir.listFiles()?.forEach { coverFile ->
                            if (coverFile.isFile) {
                                val coverEntry = ZipEntry("covers/${coverFile.name}")
                                zipOut.putNextEntry(coverEntry)
                                coverFile.inputStream().use { it.copyTo(zipOut) }
                                zipOut.closeEntry()
                            }
                        }
                    }
                }
            }
            val root = JSONObject(jsonString)
            val count = root.optJSONArray("books")?.length() ?: 0
            Result.success(count)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun generateBackupMetadataJson(repository: NovelRepository): String = withContext(Dispatchers.IO) {
        val books = repository.getAllBooks()
        val allChapters = mutableListOf<ChapterEntity>()
        for (book in books) {
            allChapters.addAll(repository.getChapters(book.id))
        }
        val bookmarks = repository.getAllBookmarks()
        val glossaries = repository.getAllGlossaries()
        val readingSessions = repository.getAllReadingSessions()
        val replacementRules = repository.getAllReplacementRules()

        val root = JSONObject().apply {
            put("format", "novel_hoarder_archive")
            put("version", 2)
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
            var jsonString: String? = null
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

            // 1. Try reading as a ZIP archive
            try {
                context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                    ZipInputStream(BufferedInputStream(inputStream)).use { zipIn ->
                        var entry: ZipEntry? = zipIn.nextEntry
                        while (entry != null) {
                            if (entry.name == "backup_metadata.json" || entry.name.endsWith(".json")) {
                                val baos = ByteArrayOutputStream()
                                zipIn.copyTo(baos)
                                jsonString = baos.toString("UTF-8")
                            } else if (entry.name.startsWith("covers/") && !entry.isDirectory) {
                                val fileName = File(entry.name).name
                                val destFile = File(coversDir, fileName)
                                destFile.outputStream().use { out ->
                                    zipIn.copyTo(out)
                                }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }
            } catch (ignored: Exception) {
                // Not a valid ZIP file; fall through to raw JSON reading
            }

            // 2. If not ZIP or no JSON entry found, read directly as raw JSON
            if (jsonString == null) {
                context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                    jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }

            if (jsonString.isNullOrBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Invalid or empty backup file"))
            }

            val importedCount = importJsonString(repository, jsonString!!)
            Result.success(importedCount)
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
}

