package com.example.data.scraper

import android.content.Context
import android.webkit.WebView
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginManager
import com.example.util.CloudflareDetector
import com.example.util.JsResultParser
import com.example.util.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Searches every site that knows how to search, in parallel, through hidden WebViews (so the
 * same Cloudflare cookies as scraping apply). Each site is described by a [SearchSource].
 */
class NovelSearch(private val context: Context, private val pluginManager: PluginManager) {

    data class SearchSource(
        val key: String,
        val name: String,
        val urlTemplate: String,
        val postBody: String = "",
        val resultSelector: String = "",
        val customJs: String? = null,
        /** Fallback pattern for sites without a selector: links whose URL matches are books. */
        val bookUrlRegex: String = ""
    )

    data class Result(val title: String, val url: String, val cover: String, val sourceKey: String, val sourceName: String)

    enum class Status { OK, EMPTY, BLOCKED, FAILED }

    data class SourceResults(val source: SearchSource, val status: Status, val results: List<Result>, val message: String = "")

    fun sources(): List<SearchSource> {
        val fromPlugins = pluginManager.plugins.value
            .filter { it.isEnabled && it.searchUrlTemplate.isNotBlank() }
            .map { it.toSearchSource() }
        return listOf(TOMATO) + fromPlugins
    }

    /** Runs every source (three at a time) and reports each one as it finishes. */
    suspend fun searchAll(
        query: String,
        sources: List<SearchSource> = sources(),
        onSource: (SourceResults) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(3)
        sources.map { src ->
            async {
                gate.withPermit {
                    val r = searchOne(src, query)
                    withContext(Dispatchers.Main) { onSource(r) }
                    r
                }
            }
        }.awaitAll()
    }

    suspend fun searchOne(source: SearchSource, query: String): SourceResults = withContext(Dispatchers.Main) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = source.urlTemplate.replace("{query}", encoded)
        var webView: WebView? = null
        try {
            webView = WebViewFactory.create(context)
            if (source.postBody.isNotBlank()) {
                webView.postUrl(url, source.postBody.replace("{query}", encoded).toByteArray())
            } else {
                webView.loadUrl(url)
            }
            val script = source.customJs?.takeIf { it.isNotBlank() } ?: resultsScript(source)
            val start = System.currentTimeMillis()
            var last: List<Result> = emptyList()
            var stableRounds = 0
            var challengeSince = 0L
            while (coroutineContext.isActive && System.currentTimeMillis() - start < 25_000) {
                delay(700)
                if (CloudflareDetector.isChallenge(webView)) {
                    if (challengeSince == 0L) challengeSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - challengeSince > 8_000) {
                        return@withContext SourceResults(source, Status.BLOCKED, emptyList(),
                            "Cloudflare check — open ${source.name} once in the browser to pass it")
                    }
                    continue
                }
                challengeSince = 0L
                val found = parse(evaluate(webView, script), source)
                if (found.isNotEmpty() && found.size == last.size) {
                    if (++stableRounds >= 1) return@withContext SourceResults(source, Status.OK, found)
                } else {
                    stableRounds = 0
                }
                last = found
                // A finished page with nothing on it: give lazy content a moment, then stop.
                if (found.isEmpty() && webView.progress == 100 && System.currentTimeMillis() - start > 8_000) break
            }
            if (last.isNotEmpty()) SourceResults(source, Status.OK, last)
            else SourceResults(source, Status.EMPTY, emptyList())
        } catch (e: Exception) {
            SourceResults(source, Status.FAILED, emptyList(), e.message ?: e::class.simpleName.orEmpty())
        } finally {
            WebViewFactory.destroySafely(webView)
        }
    }

    private suspend fun evaluate(webView: WebView, script: String): String? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont -> webView.evaluateJavascript(script) { if (cont.isActive) cont.resume(it) } }
    }

    private fun parse(raw: String?, source: SearchSource): List<Result> {
        val arr = JsResultParser.toJsonArray(raw) ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Result>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            val t = o.optString("title").trim()
            if (u.isBlank() || t.isBlank() || !seen.add(u)) continue
            out += Result(t, u, o.optString("cover"), source.key, source.name)
        }
        return out.take(MAX_RESULTS)
    }

    companion object {
        const val MAX_RESULTS = 20

        /**
         * TomatoMTL sits behind Cloudflare, so its search page could not be inspected while
         * building this; the URL is a best guess and results are found by book-link pattern
         * rather than a selector. If it returns nothing, use "Open site search" instead.
         */
        val TOMATO = SearchSource(
            key = SourceKeys.TOMATO,
            name = "TomatoMTL",
            urlTemplate = "https://tomatomtl.com/search?keyword={query}",
            bookUrlRegex = "/book/[^/?#]+/?$"
        )

        fun PluginConfig.toSearchSource() = SearchSource(
            key = id,
            name = name,
            urlTemplate = searchUrlTemplate,
            postBody = searchPostBody,
            resultSelector = searchResultSelector,
            customJs = customSearchJs
        )

        /** Generic results script: book links plus the nearest image as cover. */
        fun resultsScript(source: SearchSource): String {
            val sel = JsResultParser.jsLiteral(source.resultSelector.ifBlank { "a[href]" })
            val re = JsResultParser.jsLiteral(source.bookUrlRegex)
            return """
                (() => {
                    const SEL = $sel, RE = $re ? new RegExp($re) : null;
                    const out = [], seen = new Set();
                    document.querySelectorAll(SEL).forEach(a => {
                        const href = a.getAttribute('href');
                        if (!href) return;
                        let u; try { u = new URL(href, location.href); } catch (e) { return; }
                        if (RE && !RE.test(u.pathname)) return;
                        if (seen.has(u.href)) return;
                        let node = a, img = a.querySelector('img');
                        for (let i = 0; !img && i < 5 && node.parentElement; i++) {
                            node = node.parentElement;
                            img = node.querySelector('img');
                        }
                        const title = (a.getAttribute('title') || a.innerText || a.textContent ||
                                       (img && img.getAttribute('alt')) || '').trim().replace(/\s+/g, ' ');
                        if (!title || title.length > 200) return;
                        seen.add(u.href);
                        let cover = '';
                        if (img) {
                            const c = img.getAttribute('data-src') || img.getAttribute('data-original') ||
                                      img.getAttribute('src') || '';
                            try { cover = c ? new URL(c, location.href).href : ''; } catch (e) {}
                        }
                        out.push({ title: title, url: u.href, cover: cover });
                    });
                    return out.slice(0, ${MAX_RESULTS});
                })()
            """.trimIndent()
        }
    }
}
