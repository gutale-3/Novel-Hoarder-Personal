package com.example.data.plugin

import android.webkit.WebView
import com.example.data.local.BookEntity
import com.example.data.scraper.NovelSource
import com.example.util.CloudflareException
import com.example.util.GenericScraper
import com.example.util.JsResultParser
import com.example.util.PageExtractors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class PluginExecutionEngine(val config: PluginConfig) : NovelSource {

    override val sourceName: String = config.name

    override fun parseBookId(url: String): String? {
        val clean = url.substringBefore("#").substringBefore("?")
        return if (clean.isNotBlank()) "${config.id}_" + clean.hashCode().toString() else null
    }

    override fun parseChapterId(url: String): String? {
        val clean = url.substringBefore("#").substringBefore("?")
        return if (clean.isNotBlank()) "${config.id}_ch_" + clean.hashCode().toString() else null
    }

    override suspend fun scrapeBookInfo(webView: WebView, url: String): BookEntity = withContext(Dispatchers.Main) {
        loadUrlAndWait(webView, url)

        val script = config.customJs?.takeIf { it.isNotBlank() } ?: buildBookInfoScript()
        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            val eval = evaluateJs(webView, script)
            if (eval != null && eval.contains("cloudflare") && eval.contains("challenge")) {
                throw CloudflareException("Cloudflare protection detected.")
            }
            if (JsResultParser.isReady(eval)) {
                jsonResult = eval
                break
            }
            delay(500)
        }

        val jsonObj = JsResultParser.toJsonObject(jsonResult)
        val title = jsonObj?.optString("title")?.ifBlank { "Unknown Book" } ?: "Unknown Book"
        val author = jsonObj?.optString("author")?.ifBlank { "Unknown Author" } ?: "Unknown Author"
        val synopsis = jsonObj?.optString("synopsis") ?: ""
        val cover = jsonObj?.optString("cover") ?: ""

        val bookId = parseBookId(url) ?: ("${config.id}_" + url.hashCode())

        BookEntity(
            id = bookId,
            url = url,
            title = title,
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

        val script = buildChapterListScript()
        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            val eval = evaluateJs(webView, script)
            if (JsResultParser.isReady(eval)) {
                jsonResult = eval
                break
            }
            delay(500)
        }

        val entries = GenericScraper.parseTocEntries(jsonResult)
        GenericScraper.buildChapterList(entries)
    }

    override suspend fun scrapeChapterContent(
        webView: WebView,
        chapterUrl: String,
        shouldSkip: () -> Boolean
    ): Pair<String, String> = withContext(Dispatchers.Main) {
        evaluateJs(webView, PageExtractors.MARK_PREVIOUS_CONTENT_JS)
        loadUrlAndWait(webView, chapterUrl)

        val script = buildChapterContentScript()
        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            if (shouldSkip()) break
            val eval = evaluateJs(webView, script)
            if (JsResultParser.isReady(eval)) {
                jsonResult = eval
                break
            }
            delay(500)
        }

        val jsonObj = JsResultParser.toJsonObject(jsonResult)
        val title = jsonObj?.optString("title") ?: "Chapter"
        val rawContent = jsonObj?.optString("content") ?: ""

        val cleanBody = GenericScraper.sanitizeText(rawContent, aggressive = false)
        Pair(title, cleanBody)
    }

    private fun buildBookInfoScript(): String {
        val tSel = JsResultParser.jsLiteral(config.titleSelector)
        val aSel = JsResultParser.jsLiteral(config.authorSelector)
        val sSel = JsResultParser.jsLiteral(config.synopsisSelector)
        val cSel = JsResultParser.jsLiteral(config.coverSelector)

        return """
            (() => {
                const q = (s) => {
                    if (!s) return "";
                    try {
                        const e = document.querySelector(s);
                        return e ? (e.innerText || e.textContent || '').trim() : "";
                    } catch (err) { return ""; }
                };
                const attr = (s, a) => {
                    if (!s) return "";
                    try {
                        const e = document.querySelector(s);
                        return e ? (e.getAttribute(a) || "") : "";
                    } catch (err) { return ""; }
                };

                const title = q($tSel) || document.title || "";
                const author = q($aSel);
                const synopsis = q($sSel) || attr($sSel, 'content');
                let cover = attr($cSel, 'src') || attr($cSel, 'content');
                if (cover && cover.indexOf('http') !== 0) {
                    try { cover = new URL(cover, location.href).href; } catch(e) { cover = ""; }
                }

                return { ready: title.length > 0, title: title, author: author, synopsis: synopsis, cover: cover };
            })()
        """.trimIndent()
    }

    private fun buildChapterListScript(): String {
        val listSel = JsResultParser.jsLiteral(config.chapterListSelector)
        return """
            (() => {
                const results = [];
                const sel = $listSel;
                if (!sel) return { ready: false, count: 0 };
                
                document.querySelectorAll(sel).forEach(a => {
                    try {
                        const href = a.getAttribute('href');
                        if (!href) return;
                        const u = new URL(href, location.href);
                        const text = (a.innerText || a.textContent || '').trim();
                        results.push({ href: u.href, text: text });
                    } catch(e) {}
                });
                return results;
            })()
        """.trimIndent()
    }

    private fun buildChapterContentScript(): String {
        val tSel = JsResultParser.jsLiteral(config.chapterTitleSelector)
        val bSel = JsResultParser.jsLiteral(config.chapterBodySelector)

        return """
            (() => {
                let title = "";
                if ($tSel) {
                    const h = document.querySelector($tSel);
                    if (h) title = (h.innerText || h.textContent || '').trim();
                }
                if (!title) title = document.title || "";

                let text = "";
                if ($bSel) {
                    const b = document.querySelector($bSel);
                    if (b) {
                        const clone = b.cloneNode(true);
                        clone.querySelectorAll('br').forEach(br => {
                            if (br.parentNode) br.parentNode.replaceChild(document.createTextNode('\n'), br);
                        });
                        clone.querySelectorAll('p, div, article, section, li, blockquote').forEach(el => {
                            el.appendChild(document.createTextNode('\n\n'));
                        });
                        text = (clone.textContent || '').replace(/ /g, ' ').split('\n').map(s=>s.trim()).filter(s=>s.length>0).join('\n\n');
                    }
                }
                if (!text && document.body) {
                    text = (document.body.innerText || '').trim();
                }

                return { ready: text.length > 100, title: title, content: text };
            })()
        """.trimIndent()
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
