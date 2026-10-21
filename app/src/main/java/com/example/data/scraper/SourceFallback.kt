package com.example.data.scraper

import android.content.Context
import android.webkit.WebView
import com.example.data.local.BookEntity
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * When a chapter cannot be downloaded from a novel's own site, fetch the same chapter number from
 * another site carrying the same novel. Backup copies are found by title through [NovelSearch]
 * and remembered per novel, so the search only happens once.
 */
class SourceFallback(private val context: Context, private val search: NovelSearch) {

    private val prefs = context.getSharedPreferences("source_fallback", Context.MODE_PRIVATE)

    /** Chapter lists of backup sources, fetched at most once per app run. */
    private val tocCache = HashMap<String, List<String>>()

    fun alternates(bookId: String): List<String> {
        val raw = prefs.getString("alt_$bookId", null) ?: return emptyList()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun setAlternates(bookId: String, urls: List<String>) {
        prefs.edit().putString("alt_$bookId", JSONArray(urls).toString()).putLong("searched_$bookId", System.currentTimeMillis()).apply()
    }

    private fun searchedRecently(bookId: String) =
        System.currentTimeMillis() - prefs.getLong("searched_$bookId", 0L) < 7 * 24 * 60 * 60 * 1000L

    /** Searches other sites for [book] by title; keeps results whose title matches closely. */
    suspend fun discover(book: BookEntity): List<String> {
        val ownKey = SourceKeys.keyFor(book.url)
        val found = mutableListOf<String>()
        search.searchAll(book.title, search.sources().filter { it.key != ownKey }) { r ->
            r.results.firstOrNull { titlesMatch(it.title, book.title) }?.let { found += it.url }
        }
        setAlternates(book.id, found)
        return found
    }

    /**
     * Downloads chapter [chapterNumber] of [book] from the first backup site that has it.
     * @return title, text and the site name, or null when no backup could provide it.
     */
    suspend fun fetchChapter(book: BookEntity, chapterNumber: Int, webView: WebView): Triple<String, String, String>? {
        var alts = alternates(book.id)
        if (alts.isEmpty() && !searchedRecently(book.id)) alts = discover(book)
        val quota = DownloadQuota.get(context)
        for (altUrl in alts) {
            val source = SourceManager.getSourceForUrl(altUrl)
            val key = SourceKeys.keyFor(altUrl)
            if (quota.remaining(key) <= 0) continue
            try {
                val toc = tocCache[altUrl] ?: withTimeoutOrNull(90_000) { source.scrapeChapterList(webView, altUrl) }
                    ?.also { tocCache[altUrl] = it } ?: continue
                val chapterUrl = pickChapter(toc, chapterNumber) ?: continue
                val (title, text) = withTimeoutOrNull(45_000) { source.scrapeChapterContent(webView, chapterUrl) { false } } ?: continue
                if (text.length < 200) continue
                quota.record(key)
                return Triple(title, text, source.sourceName)
            } catch (e: Exception) {
                // Try the next backup; this one is broken or blocked right now.
            }
        }
        return null
    }

    companion object {
        fun normalizeTitle(t: String): String = t.lowercase()
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

        /** Same title, allowing for punctuation, bracketed tags and a short subtitle difference. */
        fun titlesMatch(a: String, b: String): Boolean {
            val x = normalizeTitle(a)
            val y = normalizeTitle(b)
            if (x.isBlank() || y.isBlank()) return false
            if (x == y) return true
            val (short, long) = if (x.length <= y.length) x to y else y to x
            return short.length >= 8 && long.startsWith(short) && short.length.toDouble() / long.length >= 0.6
        }

        private val CHAPTER_NO = Regex("(?i)(?:chapter|chap|ch)[_-]?(\\d+)")

        /** Finds chapter [n] by a "chapter-N" style URL, falling back to list position. */
        fun pickChapter(toc: List<String>, n: Int): String? {
            toc.firstOrNull { url ->
                CHAPTER_NO.find(url.trimEnd('/').substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull() == n
            }?.let { return it }
            return toc.getOrNull(n - 1)
        }
    }
}
