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

    /** Where the previous save went, so author notes without a number land right after it. */
    private var lastCapturedNumber: Int? = null

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

    /** Saves chapters into a novel already in the library, without grabbing book info first. */
    fun selectExistingBook(book: BookEntity) {
        captureBookId = book.id
        captureBookTitle = book.title
        prefs.edit().putString("capture_book_id", book.id).apply()
        coroutineScope.launch(Dispatchers.IO) {
            val count = repository.getChapterCount(book.id)
            withContext(Dispatchers.Main) { captureChapterCount = count }
        }
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
                val pageUrl = currentUrl(webView).ifBlank { "manual://capture" }

                // Check if we should start a new session because of a different novel title or different host/domain
                val isDifferentBook = captureBookId != null && captureBookTitle.isNotEmpty() &&
                    cleanTitle.lowercase() != captureBookTitle.lowercase() &&
                    !cleanTitle.contains(captureBookTitle, ignoreCase = true) && 
                    !captureBookTitle.contains(cleanTitle, ignoreCase = true)

                val bookId = if (isDifferentBook) {
                    "manual_" + pageUrl.hashCode()
                } else {
                    captureBookId ?: ("manual_" + pageUrl.hashCode())
                }

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
                android.util.Log.e("ManualCapture", "Book info grab failed", e)
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
            onResult("Choose which novel this chapter belongs to first.")
            return
        }

        isCapturing = true

        coroutineScope.launch(Dispatchers.Main) {
            try {
                val rawResult = evaluateJs(webView, PageExtractors.CHAPTER_CONTENT_JS)
                // WebView getters are main-thread-only, exactly like its methods. Read this here, while
                // we are still on Main — reading it inside the IO block below throws.
                val pageUrl = currentUrl(webView)

                val jsonObj = JsResultParser.toJsonObject(rawResult)

                val rawTitle = jsonObj?.optString("title") ?: "Chapter"
                val rawContent = jsonObj?.optString("content") ?: ""

                if (com.example.util.CloudflareDetector.isChallengeText(rawContent)) {
                    onResult("This page is a security check, not the chapter. Pass it, then press Save again.")
                    isCapturing = false
                    return@launch
                }
                val cleanBody = com.example.util.ChapterTextCleaner.stripLeadingHeadings(
                    GenericScraper.sanitizeText(rawContent, aggressive = false)
                )
                if (cleanBody.isBlank()) {
                    onResult("No chapter content found on page.")
                    isCapturing = false
                    return@launch
                }

                val contentHash = md5(cleanBody)

                withContext(Dispatchers.IO) {
                    val existingChapters = repository.getChapters(targetBookId)

                    val duplicate = existingChapters.firstOrNull { it.hash == contentHash }
                    if (duplicate != null) {
                        withContext(Dispatchers.Main) {
                            onResult("Already captured as Chapter ${duplicate.chapterNumber}: ${duplicate.title}")
                        }
                        return@withContext
                    }

                    val bookTitle = repository.getBook(targetBookId)?.title.orEmpty()
                    val all = repository.getAllChaptersIncludingHidden(targetBookId)
                    val normUrl = com.example.util.ChapterUrls.normalize(pageUrl)
                    val byUrl = all.firstOrNull { pageUrl.isNotBlank() && com.example.util.ChapterUrls.normalize(it.url) == normUrl }
                    val parsedNumber = GenericScraper.parseChapterNumber(rawTitle, pageUrl)
                    val plan = ChapterPlacement.place(all, byUrl, parsedNumber, lastCapturedNumber)
                    val number = plan.position
                    val title = com.example.util.ChapterTitleCleaner.clean(
                        rawTitle.takeIf { it.isNotBlank() && it != "Chapter" } ?: "Chapter ${parsedNumber ?: number}",
                        bookTitle, chapterNumber = parsedNumber ?: number
                    )

                    val target = plan.fill
                    val chapter = target?.copy(
                        title = title, content = cleanBody, hash = contentHash,
                        isDeleted = false, isArchived = false,
                        downloadedAt = System.currentTimeMillis()
                    ) ?: ChapterEntity(
                        // Unique per page, so moving chapters around can never make ids collide.
                        id = "${targetBookId}_m_${md5(normUrl.ifBlank { contentHash }).take(12)}",
                        bookId = targetBookId,
                        chapterId = "m_${md5(normUrl.ifBlank { contentHash }).take(12)}",
                        chapterNumber = number,
                        title = title,
                        url = pageUrl.ifBlank { "manual://$targetBookId/ch/$number" },
                        content = cleanBody,
                        hash = contentHash
                    )

                    if (target != null) repository.insertChapter(chapter) else repository.insertChapterAt(chapter)
                    lastCapturedNumber = chapter.chapterNumber
                    val newCount = repository.getChapterCount(targetBookId)
                    repository.getBook(targetBookId)?.let { repository.updateBook(it.copy(totalChapters = newCount)) }

                    withContext(Dispatchers.Main) {
                        captureChapterCount = newCount
                        onResult(
                            (if (target != null) "Filled in: " else "Saved as #$number: ") +
                                "${chapter.title} → $bookTitle" + plan.note
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ManualCapture", "Chapter capture failed", e)
                onResult("Could not save this chapter. Try reloading the page, then press Save again.")
            } finally {
                isCapturing = false
            }
        }
    }

    private suspend fun currentUrl(webView: WebView): String =
        withContext(Dispatchers.Main) {
            try {
                webView.url.orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }

    private suspend fun evaluateJs(webView: WebView, script: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        webView.evaluateJavascript(script) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) {
                        // A destroyed WebView throws here; a null result is handled by the caller.
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
    }
}
