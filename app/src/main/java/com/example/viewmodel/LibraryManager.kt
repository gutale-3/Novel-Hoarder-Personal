package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GlossaryEntity
import com.example.data.local.BookmarkEntity
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.util.NovelCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val scrapingManager: ScrapingManager
) {
    var onClearSelection: (() -> Unit)? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- Book & Chapter Soft-Delete / Restore / Trash Helpers ---
    fun deleteBook(bookId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.softDeleteBook(bookId)
                scrapingManager.addLog("Moved novel to Trash: $bookId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun restoreBook(bookId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.restoreBook(bookId)
                scrapingManager.addLog("Restored novel from Trash: $bookId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun permanentlyDeleteBook(bookId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val context = application.applicationContext
                val folder = File(context.filesDir, bookId)
                if (folder.exists()) {
                    folder.deleteRecursively()
                }
                repository.deleteBook(bookId)
                scrapingManager.addLog("Permanently deleted novel: $bookId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun softDeleteChapter(chapterId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.softDeleteChapter(chapterId)
                scrapingManager.addLog("Moved chapter to Trash: $chapterId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun softDeleteChapters(chapterIds: List<String>) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.softDeleteChapters(chapterIds)
                scrapingManager.addLog("Moved ${chapterIds.size} chapters to Trash")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun restoreChapter(chapterId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.restoreChapter(chapterId)
                scrapingManager.addLog("Restored chapter from Trash: $chapterId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun restoreChapters(chapterIds: List<String>) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.restoreChapters(chapterIds)
                scrapingManager.addLog("Restored ${chapterIds.size} chapters from Trash")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun permanentlyDeleteChapter(chapterId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.deleteChapter(chapterId)
                scrapingManager.addLog("Permanently deleted chapter: $chapterId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun permanentlyDeleteChapters(chapterIds: List<String>) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.deleteChapters(chapterIds)
                scrapingManager.addLog("Permanently deleted ${chapterIds.size} chapters")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun emptyTrash() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.emptyTrash()
                scrapingManager.addLog("Emptied trash permanently")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun moveChapterUp(bookId: String, chapterId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.moveChapterUp(bookId, chapterId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun moveChapterDown(bookId: String, chapterId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.moveChapterDown(bookId, chapterId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun md5(input: String): String {
        try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return input.hashCode().toString()
        }
    }

    fun addSingleChapter(
        bookId: String,
        title: String,
        chapterNumber: Int,
        content: String,
        onComplete: () -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val allChapters = repository.getChapters(bookId).sortedBy { it.chapterNumber }
            
            val newChId = "${bookId}_ch_manual_${System.currentTimeMillis()}"
            val newChapter = ChapterEntity(
                id = newChId,
                bookId = bookId,
                chapterId = "manual_${System.currentTimeMillis()}",
                chapterNumber = chapterNumber,
                title = title.trim(),
                url = "local://$bookId/ch/manual_${System.currentTimeMillis()}",
                content = content,
                hash = md5(content),
                isRead = false,
                isArchived = false,
                isDeleted = false
            )
            
            // Shift any chapters that have >= chapterNumber to avoid duplicates
            for (ch in allChapters) {
                if (ch.chapterNumber >= chapterNumber) {
                    repository.insertChapter(ch.copy(chapterNumber = ch.chapterNumber + 1))
                }
            }
            
            // Insert the new chapter
            repository.insertChapter(newChapter)
            
            // Update book total count
            val totalCount = repository.getChapterCount(bookId)
            repository.getBook(bookId)?.let { currentBook ->
                if (totalCount > currentBook.totalChapters) {
                    repository.insertBook(currentBook.copy(totalChapters = totalCount))
                }
            }
            
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun resequenceChapters(
        bookId: String,
        prefix: String,
        startNumber: Int,
        onComplete: (Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val activeChapters = repository.getChapters(bookId)
            val archivedChapters = repository.getArchivedChapters(bookId)
            val allChapters = (activeChapters + archivedChapters).sortedBy { it.chapterNumber }
            
            var currentNum = startNumber
            for (ch in allChapters) {
                val newTitle = if (prefix.trim().isEmpty()) {
                    "$currentNum"
                } else {
                    "${prefix.trim()} $currentNum"
                }
                
                repository.insertChapter(ch.copy(title = newTitle, chapterNumber = currentNum))
                currentNum++
            }
            
            withContext(Dispatchers.Main) {
                onComplete(allChapters.size)
            }
        }
    }

    fun compileFormat(book: BookEntity, format: String, onFinished: (Boolean, String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val context = application.applicationContext
            val chapters = repository.getChapters(book.id)
            val outputFolder = File(context.filesDir, book.id)
            if (!outputFolder.exists()) outputFolder.mkdirs()

            if (format == "EPUB") {
                val file = File(outputFolder, "${book.id}.epub")
                val ok = NovelCompiler.compileEpub(context, book, chapters, file)
                withContext(Dispatchers.Main) {
                    onFinished(ok, if (ok) file.absolutePath else "")
                }
            } else if (format == "PDF") {
                val file = File(outputFolder, "${book.id}.pdf")
                val ok = NovelCompiler.compilePdf(context, book, chapters, file)
                withContext(Dispatchers.Main) {
                    onFinished(ok, if (ok) file.absolutePath else "")
                }
            }
        }
    }

    // --- Bulk Operations ---
    fun bulkDeleteBooks(bookIds: Set<String>) {
        coroutineScope.launch(Dispatchers.IO) {
            for (id in bookIds) {
                repository.softDeleteBook(id)
            }
            withContext(Dispatchers.Main) {
                onClearSelection?.invoke()
            }
        }
    }

    fun bulkReScrapeBooks(bookIds: Set<String>, onResult: (String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            var checked = 0
            var added = 0
            for (id in bookIds) {
                val book = repository.getBook(id) ?: continue
                try {
                    val scraper = SourceManager.getSourceForUrl(book.url)
                    val urls = withContext(Dispatchers.Main) {
                        val webView = com.example.util.WebViewFactory.create(application.applicationContext, null)
                        try {
                            scraper.scrapeChapterList(webView, book.url)
                        } finally {
                            try { webView.destroy() } catch (_: Exception) {}
                        }
                    }
                    if (urls.isNotEmpty()) {
                        val currentCount = repository.getChapterCount(book.id)
                        val diff = urls.size - currentCount
                        if (diff > 0) {
                            added += diff
                        }
                    }
                    checked++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                onClearSelection?.invoke()
                onResult("Checked $checked novels. Found $added new chapters to download!")
            }
        }
    }

    // --- Library Backup / Restore ---
    fun backupLibrary(context: Context, onResult: (Boolean, String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val jsonString = com.example.util.BackupRestoreManager.generateBackupMetadataJson(repository)
                val backupFile = java.io.File(context.cacheDir, "novel_hoarder_library_backup.json")
                backupFile.writeText(jsonString)
                
                withContext(Dispatchers.Main) {
                    onResult(true, backupFile.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Failed to generate backup")
                }
            }
        }
    }

    fun restoreLibrary(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = com.example.util.BackupRestoreManager.restoreFullBackupZip(context, repository, uri)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val count = result.getOrDefault(0)
                    onResult(true, "Library restored successfully ($count novels restored)!")
                } else {
                    onResult(false, "Failed to restore: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            }
        }
    }

    // --- Import Local File ---
    fun importLocalFile(context: Context, uri: Uri, isEpub: Boolean, customUrl: String? = null, onResult: (Boolean, String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val importRes = com.example.util.EpubImporter.import(context, uri)
            
            if (importRes != null) {
                val bookWithCover = com.example.util.EpubImporter.saveCoverAndBuildBook(context, importRes.book, importRes.coverBytes)
                val finalUrl = if (!customUrl.isNullOrBlank()) customUrl.trim() else bookWithCover.url
                val updatedBook = bookWithCover.copy(url = finalUrl)
                
                repository.insertBookAndChapters(updatedBook, importRes.chapters)
                
                val warnText = if (importRes.warnings.isNotEmpty()) "\nWarnings:\n" + importRes.warnings.joinToString("\n") else ""
                withContext(Dispatchers.Main) {
                    onResult(true, "Imported \"${updatedBook.title}\" with ${importRes.chapters.size} chapters!$warnText")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(false, "Failed to parse local book file. Ensure the format is valid.")
                }
            }
        }
    }

    fun importLocalFileWithProgress(
        context: Context,
        uri: Uri,
        customUrl: String? = null,
        customTitle: String? = null,
        onProgress: (com.example.util.ImportProgress) -> Unit,
        onComplete: (Boolean, String, com.example.util.ImportResult?) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val importRes = com.example.util.EpubImporter.import(context, uri) { prog ->
                coroutineScope.launch(Dispatchers.Main) {
                    onProgress(prog)
                }
            }

            if (importRes != null) {
                val finalTitle = if (!customTitle.isNullOrBlank()) customTitle.trim() else importRes.book.title
                val bookWithTitle = importRes.book.copy(title = finalTitle)
                val bookWithCover = com.example.util.EpubImporter.saveCoverAndBuildBook(context, bookWithTitle, importRes.coverBytes)
                val finalUrl = if (!customUrl.isNullOrBlank()) customUrl.trim() else bookWithCover.url
                val updatedBook = bookWithCover.copy(url = finalUrl)

                repository.insertBookAndChapters(updatedBook, importRes.chapters)

                val warnText = if (importRes.warnings.isNotEmpty()) "\nWarnings:\n" + importRes.warnings.take(5).joinToString("\n") else ""
                val msg = "Successfully imported \"${updatedBook.title}\" (${importRes.chapters.size} chapters).$warnText"

                withContext(Dispatchers.Main) {
                    onComplete(true, msg, importRes.copy(book = updatedBook))
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Failed to import file. Ensure file is an EPUB or readable text document.", null)
                }
            }
        }
    }

    // --- Update Book Details (Cover, Author, Title, URL, Category) ---
    fun updateBookDetails(
        bookId: String,
        newTitle: String,
        newAuthor: String,
        newUrl: String,
        newCoverUrl: String?,
        newCoverLocalPath: String?,
        newCategory: String? = null,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val book = repository.getBook(bookId)
            if (book != null) {
                val updatedBook = book.copy(
                    title = newTitle.trim(),
                    author = newAuthor.trim(),
                    url = if (newUrl.isNotBlank()) newUrl.trim() else book.url,
                    coverUrl = newCoverUrl?.trim()?.ifEmpty { null },
                    coverLocalPath = newCoverLocalPath?.trim()?.ifEmpty { null },
                    category = newCategory?.trim()?.ifEmpty { null } ?: book.category,
                    updatedAt = System.currentTimeMillis()
                )
                repository.insertBook(updatedBook)
                withContext(Dispatchers.Main) {
                    onResult(true, "Novel details updated successfully!")
                }
            } else {
                withContext(Dispatchers.Main) {
                    onResult(false, "Novel not found.")
                }
            }
        }
    }

    fun bulkUpdateCategory(bookIds: Set<String>, category: String, onResult: (String) -> Unit = {}) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                repository.updateBooksCategory(bookIds.toList(), category)
                withContext(Dispatchers.Main) {
                    onClearSelection?.invoke()
                    onResult("Moved ${bookIds.size} novels to \"$category\"")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Failed to update category: ${e.message}")
                }
            }
        }
    }

    // --- Background Chapter Updates Checker ---
    fun scheduleChapterUpdatesCheck() {
        val context = application.applicationContext
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.background.ChapterUpdateWorker>(
                6, java.util.concurrent.TimeUnit.HOURS
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "chapter_updates_work",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
