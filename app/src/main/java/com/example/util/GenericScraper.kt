package com.example.util

import android.webkit.WebView
import com.example.data.local.BookEntity
import com.example.data.scraper.NovelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

data class TocEntry(val href: String, val text: String)

object GenericScraper : NovelSource {

    override val sourceName: String = "Universal Web Scraper"

    private val AD_PATTERNS = listOf(
        Pattern.compile("Translator:.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Editor:.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Visit [^\\n]* for the best reading experience.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Read (this|the latest) (chapter )?(at|on) [^\\n]*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("This chapter (is updated by|upload first at) [^\\n]*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Follow current novels? on [^\\n]*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("If you (find|found) any errors.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Please support the (author|translator).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("©.*All rights reserved.*", Pattern.CASE_INSENSITIVE)
    )

    override fun parseBookId(url: String): String? {
        val clean = url.substringBefore("#").substringBefore("?")
        return if (clean.isNotBlank()) "gen_" + clean.hashCode().toString() else null
    }

    override fun parseChapterId(url: String): String? {
        val clean = url.substringBefore("#").substringBefore("?")
        return if (clean.isNotBlank()) "gen_ch_" + clean.hashCode().toString() else null
    }

    override suspend fun scrapeBookInfo(webView: WebView, url: String): BookEntity = withContext(Dispatchers.Main) {
        loadUrlAndWait(webView, url)
        
        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            val eval = evaluateJs(webView, PageExtractors.BOOK_INFO_JS)
            if (eval != null && eval.contains("cloudflare") && eval.contains("challenge")) {
                throw CloudflareException("Cloudflare protection detected. Please solve captcha in manual browser.")
            }
            if (JsResultParser.isReady(eval)) {
                jsonResult = eval
                break
            }
            delay(500)
        }

        val jsonObj = JsResultParser.toJsonObject(jsonResult)
        val rawTitle = jsonObj?.optString("title") ?: "Unknown Book"
        val cleanTitle = ChapterCleaner.cleanBookTitle(rawTitle)
        val author = jsonObj?.optString("author")?.ifBlank { "Unknown Author" } ?: "Unknown Author"
        val synopsis = jsonObj?.optString("synopsis") ?: ""
        val cover = jsonObj?.optString("cover") ?: ""

        val bookId = parseBookId(url) ?: ("gen_" + url.hashCode())

        BookEntity(
            id = bookId,
            url = url,
            title = cleanTitle,
            author = author,
            synopsis = synopsis,
            coverUrl = cover,
            coverLocalPath = null,
            lastReadChapterId = null,
            totalChapters = 0
        )
    }

    override suspend fun scrapeChapterList(webView: WebView, bookUrl: String): List<String> = withContext(Dispatchers.Main) {
        loadUrlAndWait(webView, bookUrl)

        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            val eval = evaluateJs(webView, PageExtractors.TOC_READY_JS)
            if (JsResultParser.isReady(eval)) {
                jsonResult = evaluateJs(webView, PageExtractors.TOC_EXTRACT_JS)
                break
            }
            delay(500)
        }

        val entries = parseTocEntries(jsonResult)
        buildChapterList(entries)
    }

    override suspend fun scrapeChapterContent(
        webView: WebView,
        chapterUrl: String,
        shouldSkip: () -> Boolean
    ): Pair<String, String> = withContext(Dispatchers.Main) {
        evaluateJs(webView, PageExtractors.MARK_PREVIOUS_CONTENT_JS)
        loadUrlAndWait(webView, chapterUrl)

        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            if (shouldSkip()) break
            val eval = evaluateJs(webView, PageExtractors.CHAPTER_CONTENT_JS)
            if (JsResultParser.isReady(eval)) {
                jsonResult = eval
                break
            }
            delay(500)
        }

        val jsonObj = JsResultParser.toJsonObject(jsonResult)
        val title = jsonObj?.optString("title") ?: "Chapter"
        val rawContent = jsonObj?.optString("content") ?: ""

        val cleanBody = sanitizeText(rawContent, aggressive = false)
        Pair(title, cleanBody)
    }

    fun parseTocEntries(raw: String?): List<TocEntry> {
        val array = JsResultParser.toJsonArray(raw) ?: return emptyList()
        val list = mutableListOf<TocEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val href = obj.optString("href", "")
            val text = obj.optString("text", "")
            if (href.isNotBlank()) {
                list.add(TocEntry(normalizeChapterUrl(href), text))
            }
        }
        return list
    }

    fun buildChapterList(entries: List<TocEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val numbered = entries.mapNotNull { entry ->
            val num = parseChapterNumber(entry.text, entry.href)
            if (num != null) Pair(num, entry.href) else null
        }

        val urls = if (numbered.size >= entries.size / 2 && numbered.isNotEmpty()) {
            numbered.distinctBy { it.first }
                .sortedBy { it.first }
                .map { it.second }
        } else {
            entries.map { it.href }
        }

        return distinctChapterUrls(urls)
    }

    fun normalizeChapterUrl(raw: String): String {
        return raw.substringBefore("#")
            .substringBefore("?utm_")
            .trimEnd('/')
    }

    fun distinctChapterUrls(urls: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (url in urls) {
            val norm = normalizeChapterUrl(url)
            if (seen.add(norm)) {
                result.add(norm)
            }
        }
        return result
    }

    fun parseChapterNumber(text: String, url: String): Int? {
        val fromText = ChapterCleaner.detectChapterNumber(text)
        if (fromText != null) return fromText

        val urlClean = url.substringAfterLast("/")
        val match = Regex("(?i)(?:chapter|chap|ch|c)[_-]?(\\d+)").find(urlClean)
            ?: Regex("(?i)(\\d+)").find(urlClean)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    fun sanitizeText(rawText: String, aggressive: Boolean): String {
        var cleaned = ChapterCleaner.clean(rawText)
        for (pattern in AD_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("")
        }

        if (aggressive) {
            cleaned = cleaned.replace(Regex("\\[[^\\]\\n]{0,80}\\]"), "")
        }

        cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
        return dropDuplicateParagraphs(cleaned)
    }

    fun dropDuplicateParagraphs(text: String): String {
        return ChapterCleaner.dropAdjacentNearDuplicates(text)
    }

    private suspend fun loadUrlAndWait(webView: WebView, url: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            webView.loadUrl(url)
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
}
