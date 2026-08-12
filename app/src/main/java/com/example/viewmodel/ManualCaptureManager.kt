package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import com.example.util.ChapterCleaner
import com.example.util.GenericScraper
import com.example.util.JsResultParser
import com.example.util.PageExtractors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.coroutines.resume

class ManualCaptureManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val coroutineScope: CoroutineScope
) {
    private val prefs = application.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    var captureBookId by mutableStateOf<String?>(null)
        private set
    var captureBookTitle by mutableStateOf("")
        private set
    var captureChapterCount by mutableStateOf(0)
        private set
    var isCapturing by mutableStateOf(false)
        private set

    init {
        coroutineScope.launch(Dispatchers.IO) {
            val savedId = prefs.getString("capture_book_id", null)
            if (!savedId.isNullOrBlank()) {
                val book = repository.getBook(savedId)
                if (book != null) {
                    val count = repository.getChapterCount(savedId)
                    withContext(Dispatchers.Main) {
                        captureBookId = book.id
                        captureBookTitle = book.title
                        captureChapterCount = count
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        clearSession()
                    }
                }
            }
        }
    }

    fun clearSession() {
        captureBookId = null
        captureBookTitle = ""
        captureChapterCount = 0
        isCapturing = false
        prefs.edit().remove("capture_book_id").apply()
    }

    fun startNewBrowserSession() {
        clearSession()
    }

    fun grabInfoFromCurrentPage(webView: WebView, onResult: (String) -> Unit) {
        if (isCapturing) {
            onResult("Capture in progress...")
            return
        }
        isCapturing = true

        coroutineScope.launch(Dispatchers.Main) {
            try {
                val rawResult = evaluateJs(webView, PageExtractors.BOOK_INFO_JS)
                val jsonObj = JsResultParser.toJsonObject(rawResult)

                val title = jsonObj?.optString("title")?.ifBlank { "Captured Novel" } ?: "Captured Novel"
                val cleanTitle = ChapterCleaner.cleanBookTitle(title)
                val author = jsonObj?.optString("author")?.ifBlank { "Unknown Author" } ?: "Unknown Author"
                val synopsis = jsonObj?.optString("synopsis") ?: ""
                val cover = jsonObj?.optString("cover") ?: ""
                val pageUrl = webView.url ?: "manual://capture"

                val bookId = captureBookId ?: ("manual_" + pageUrl.hashCode())

                val book = BookEntity(
                    id = bookId,
                    url = pageUrl,
                    title = cleanTitle,
                    author = author,
                    synopsis = synopsis,
                    coverUrl = cover,
                    coverLocalPath = null,
                    lastReadChapterId = null,
                    totalChapters = 0
                )

                withContext(Dispatchers.IO) {
                    repository.insertBook(book)
                    val count = repository.getChapterCount(bookId)
                    withContext(Dispatchers.Main) {
                        captureBookId = bookId
                        captureBookTitle = cleanTitle
                        captureChapterCount = count
                        prefs.edit().putString("capture_book_id", bookId).apply()
                    }
                }

                onResult("Captured novel info: $cleanTitle")
            } catch (e: Exception) {
                onResult("Failed to grab info: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }

    fun grabChapterFromCurrentPage(webView: WebView, onResult: (String) -> Unit) {
        if (isCapturing) {
            onResult("Capture in progress...")
            return
        }

        val targetBookId = captureBookId
        if (targetBookId == null) {
            onResult("No active capture session. Use 'Grab Novel Info' first.")
            return
        }

        isCapturing = true

        coroutineScope.launch(Dispatchers.Main) {
            try {
                val rawResult = evaluateJs(webView, PageExtractors.CHAPTER_CONTENT_JS)
                val jsonObj = JsResultParser.toJsonObject(rawResult)

                val rawTitle = jsonObj?.optString("title") ?: "Chapter"
                val rawContent = jsonObj?.optString("content") ?: ""

                val cleanBody = GenericScraper.sanitizeText(rawContent, aggressive = false)
                if (cleanBody.isBlank()) {
                    onResult("No chapter content found on page.")
                    isCapturing = false
                    return@launch
                }

                val contentHash = md5(cleanBody)

                withContext(Dispatchers.IO) {
                    val existingChapters = repository.getChapters(targetBookId)
                    val lastChapter = existingChapters.lastOrNull()

                    if (lastChapter != null && lastChapter.hash == contentHash) {
                        withContext(Dispatchers.Main) {
                            onResult("Chapter already captured (duplicate content).")
                        }
                        return@withContext
                    }

                    val nextIndex = existingChapters.size + 1
                    val chapterId = "${targetBookId}_ch_$nextIndex"
                    val pageUrl = webView.url ?: "manual://$targetBookId/ch/$nextIndex"

                    val chapter = ChapterEntity(
                        id = chapterId,
                        bookId = targetBookId,
                        chapterId = "ch_$nextIndex",
                        chapterNumber = nextIndex,
                        title = if (rawTitle.isBlank() || rawTitle == "Chapter") "Chapter $nextIndex" else rawTitle,
                        url = pageUrl,
                        content = cleanBody,
                        hash = contentHash
                    )

                    repository.insertChapter(chapter)
                    val newCount = repository.getChapterCount(targetBookId)

                    withContext(Dispatchers.Main) {
                        captureChapterCount = newCount
                        onResult("Saved Chapter $nextIndex: ${chapter.title}")
                    }
                }
            } catch (e: Exception) {
                onResult("Failed to grab chapter: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? {
        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
    }
}
