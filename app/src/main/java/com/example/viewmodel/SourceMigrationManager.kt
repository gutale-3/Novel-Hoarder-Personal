package com.example.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.util.WebViewFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MigrationPreview(
    val book: BookEntity,
    val newSourceUrl: String,
    val existingChaptersCount: Int,
    val newSourceChaptersCount: Int,
    val additionalChaptersCount: Int,
    val newChapterUrls: List<String>
)

class SourceMigrationManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val coroutineScope: CoroutineScope
) {
    var isAnalyzingSource by mutableStateOf(false)
    var migrationPreview by mutableStateOf<MigrationPreview?>(null)
    var migrationStatusMessage by mutableStateOf<String?>(null)

    fun analyzeNewSource(
        book: BookEntity,
        newSourceUrl: String,
        onComplete: (Result<MigrationPreview>) -> Unit = {}
    ) {
        if (newSourceUrl.isBlank()) return
        isAnalyzingSource = true
        migrationStatusMessage = "Scraping chapter list from new source..."

        coroutineScope.launch(Dispatchers.Main) {
            try {
                val scraper = SourceManager.getSourceForUrl(newSourceUrl)
                val webView = WebViewFactory.create(application, null)
                var newUrls = emptyList<String>()

                try {
                    newUrls = scraper.scrapeChapterList(webView, newSourceUrl)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { webView.destroy() } catch (_: Exception) {}
                }

                if (newUrls.isEmpty()) {
                    isAnalyzingSource = false
                    migrationStatusMessage = "No chapters could be detected from the new URL."
                    onComplete(Result.failure(IllegalArgumentException("No chapters found at new source URL.")))
                    return@launch
                }

                val existingChapters = withContext(Dispatchers.IO) {
                    repository.getChapters(book.id)
                }

                val existingCount = existingChapters.size
                val newCount = newUrls.size
                val additionalCount = (newCount - existingCount).coerceAtLeast(0)

                val preview = MigrationPreview(
                    book = book,
                    newSourceUrl = newSourceUrl,
                    existingChaptersCount = existingCount,
                    newSourceChaptersCount = newCount,
                    additionalChaptersCount = additionalCount,
                    newChapterUrls = newUrls
                )

                migrationPreview = preview
                isAnalyzingSource = false
                migrationStatusMessage = "Found $newCount chapters on new source ($additionalCount new chapters available)."
                onComplete(Result.success(preview))
            } catch (e: Exception) {
                isAnalyzingSource = false
                migrationStatusMessage = "Error analyzing new source: ${e.message}"
                onComplete(Result.failure(e))
            }
        }
    }

    fun executeMigration(
        preview: MigrationPreview,
        onFinished: (Boolean, String) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val book = preview.book
                val newUrls = preview.newChapterUrls

                // 1. Update book source URL
                val updatedBook = book.copy(
                    url = preview.newSourceUrl,
                    totalChapters = preview.newSourceChaptersCount.coerceAtLeast(book.totalChapters)
                )
                repository.updateBook(updatedBook)

                // 2. Fetch existing chapters
                val existingChapters = repository.getChapters(book.id)
                val existingUrls = existingChapters.map { it.url }.toSet()

                // 3. For any chapter in new source beyond existing count, insert new pending chapter entities
                val newChaptersToInsert = mutableListOf<ChapterEntity>()
                for (i in existingChapters.size until newUrls.size) {
                    val url = newUrls[i]
                    if (!existingUrls.contains(url)) {
                        newChaptersToInsert.add(
                            ChapterEntity(
                                id = "${book.id}_migrated_${i + 1}",
                                bookId = book.id,
                                chapterId = "ch_${i + 1}",
                                chapterNumber = i + 1,
                                title = "Chapter ${i + 1}",
                                url = url,
                                content = "",
                                hash = ""
                            )
                        )
                    }
                }

                if (newChaptersToInsert.isNotEmpty()) {
                    repository.insertChapters(newChaptersToInsert)
                }

                withContext(Dispatchers.Main) {
                    migrationPreview = null
                    migrationStatusMessage = "Source switched successfully to ${preview.newSourceUrl}"
                    onFinished(true, "Successfully switched novel source and updated chapter links!")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    migrationStatusMessage = "Migration failed: ${e.message}"
                    onFinished(false, e.message ?: "Failed to migrate source")
                }
            }
        }
    }

    fun clearPreview() {
        migrationPreview = null
        migrationStatusMessage = null
    }
}
