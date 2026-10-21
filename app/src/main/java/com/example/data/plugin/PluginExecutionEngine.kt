package com.example.data.plugin

import android.webkit.WebView
import com.example.data.local.BookEntity
import com.example.data.scraper.NovelSource
import com.example.util.CloudflareDetector
import com.example.util.CloudflareException
import com.example.util.SelectorDiagnostics
import com.example.util.ScrapeTimeouts
import com.example.util.GenericScraper
import com.example.util.JsResultParser
import com.example.util.PageExtractors
import com.example.util.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class PluginExecutionEngine(val config: PluginConfig) : NovelSource {

    companion object {
        @Volatile
        var progressListener: ((Int) -> Unit)? = null

        private const val NOVEL_ID_JS =
            "(() => { const el = document.querySelector('[data-novel-id]'); " +
            "return el ? el.getAttribute('data-novel-id') : null; })()"

        /** Expands a [PluginConfig.tocUrlTemplate]. Pure so it can be unit tested. */
        fun resolveTocUrl(template: String, bookUrl: String, novelId: String?): String {
            val clean = bookUrl.substringBefore("#").substringBefore("?").trimEnd('/')
            val uri = java.net.URI(clean)
            val origin = "${uri.scheme}://${uri.host}" + if (uri.port != -1) ":${uri.port}" else ""
            val slug = clean.substringAfterLast('/').removeSuffix(".html")
            return template
                .replace("{url}", clean)
                .replace("{origin}", origin)
                .replace("{slug}", slug)
                .replace("{novelId}", novelId?.takeIf { it.isNotBlank() } ?: slug)
        }
    }

    /** Loads the page the chapter list should be read from: the book page, or [PluginConfig.tocUrlTemplate]. */
    private suspend fun loadTocPage(webView: WebView, bookUrl: String) {
        val template = config.tocUrlTemplate
        if (template.isBlank()) {
            loadUrlAndWait(webView, bookUrl)
            return
        }
        var novelId: String? = null
        if (template.contains("{novelId}")) {
            loadUrlAndWait(webView, bookUrl)
            val watch = CloudflareDetector.ChallengeWatch(config.name)
            val start = System.currentTimeMillis()
            while (coroutineContext.isActive && System.currentTimeMillis() - start < 15_000) {
                watch.check(webView)
                novelId = evaluateJs(webView, NOVEL_ID_JS)
                    ?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                if (novelId != null) break
                delay(500)
            }
        }
        loadUrlAndWait(webView, resolveTocUrl(template, bookUrl, novelId))
    }

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

        val watch = CloudflareDetector.ChallengeWatch(config.name)
        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < 20000) {
            watch.check(webView)
            val eval = evaluateJs(webView, script)
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

    override suspend fun scrapeChapterList(webView: WebView, bookUrl: String): List<String> =
        withContext(Dispatchers.Main) {
            loadTocPage(webView, bookUrl)

            val script = config.customTocJs?.takeIf { it.isNotBlank() }
                ?: buildPaginatedTocScript()

            // A custom script may fetch many pages itself, so allow far longer than a DOM read needs.
            val timeoutMs = if (config.customTocJs.isNullOrBlank()) 30_000L else 120_000L
            var jsonResult: String? = null
            val startTime = System.currentTimeMillis()

            val watch = CloudflareDetector.ChallengeWatch(config.name)
            while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < timeoutMs) {
                watch.check(webView)
                val eval = evaluateJs(webView, script)
                if (JsResultParser.isReady(eval)) { jsonResult = eval; break }

                val progressScript = "(() => { return window.__nhTocProgress || window.__nhWtrProgress || 0; })()"
                val progressVal = evaluateJs(webView, progressScript)
                val count = try { progressVal?.replace("\"", "")?.trim()?.toIntOrNull() ?: 0 } catch (e: Exception) { 0 }
                if (count > 0) {
                    progressListener?.invoke(count)
                }

                delay(500)
            }

            if (jsonResult == null && config.customTocJs.isNullOrBlank()) {
                jsonResult = evaluateJs(webView, PageExtractors.TOC_EXTRACT_JS)
            }

            val entries = GenericScraper.parseTocEntries(jsonResult)
            var urls = GenericScraper.buildChapterList(entries)

            if (config.chapterLinkFilterRegex.isNotBlank()) {
                try {
                    val filterRe = Regex(config.chapterLinkFilterRegex, RegexOption.IGNORE_CASE)
                    urls = urls.filter { filterRe.containsMatchIn(it) }
                } catch (_: Exception) {}
            }

            if (config.chapterLinkExcludeRegex.isNotBlank()) {
                try {
                    val excludeRe = Regex(config.chapterLinkExcludeRegex, RegexOption.IGNORE_CASE)
                    urls = urls.filterNot { excludeRe.containsMatchIn(it) }
                } catch (_: Exception) {}
            }

            if (config.reverseOrder) {
                urls = urls.reversed()
            }

            urls
        }

    override suspend fun scrapeChapterContent(
        webView: WebView,
        chapterUrl: String,
        shouldSkip: () -> Boolean
    ): Pair<String, String> = withContext(Dispatchers.Main) {
        evaluateJs(webView, PageExtractors.MARK_PREVIOUS_CONTENT_JS)
        loadUrlAndWait(webView, chapterUrl)

        val script = config.customChapterJs?.takeIf { it.isNotBlank() } ?: buildChapterContentScript()
        var jsonResult: String? = null
        val startTime = System.currentTimeMillis()

        val watch = CloudflareDetector.ChallengeWatch(config.name)
        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < ScrapeTimeouts.chapterMs) {
            if (shouldSkip()) break
            watch.check(webView)
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

        // Fail loudly rather than saving a blank chapter: the caller retries on an exception
        // but treats a successful empty return as a finished download.
        if (cleanBody.length < 200) {
            // A last look before failing: the challenge may have appeared after the grace window
            // started, and a selector that matches nothing deserves a clearer message than "short".
            if (CloudflareDetector.isChallenge(webView)) {
                throw CloudflareException("${config.name}: Cloudflare/anti-bot check is blocking $chapterUrl")
            }
            val diagnosis = if (config.customChapterJs.isNullOrBlank()) {
                SelectorDiagnostics.describeMisses(
                    evaluateJs(webView, SelectorDiagnostics.countScript(
                        mapOf(
                            "chapterBodySelector" to config.chapterBodySelector,
                            "chapterReadySelector" to config.chapterReadySelector
                        )
                    ))
                )
            } else null
            throw java.io.IOException(
                "${config.name}: chapter body was empty or too short (" +
                "${cleanBody.length} chars) at $chapterUrl" +
                (diagnosis?.let { " — $it" } ?: "")
            )
        }

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

    /**
     * Collects the chapter list across a paginated table of contents.
     *
     * Fetches the remaining pages rather than navigating to them: navigation would cost a full page
     * load each, and would also destroy the WebView state we are polling from. Runs the fetches in
     * small groups with a pause between, so we never open a hundred connections at once.
     */
    private fun buildPaginatedTocScript(): String {
        val listSel = JsResultParser.jsLiteral(config.chapterListSelector)
        val pagerSel = JsResultParser.jsLiteral(config.tocLastPageSelector)
        val pageParam = JsResultParser.jsLiteral(config.tocPageParam)
        val firstPage = config.tocFirstPage
        // When tocPaginationMode is "none", skip the pagination entirely (MAX_PAGES = FIRST_PAGE)
        val maxPages = if (config.tocPaginationMode == "none") config.tocFirstPage else config.tocMaxPages

        return """
            (() => {
                if (window.__nhTocState === 'running') return { ready: false };
                if (window.__nhTocResult) return window.__nhTocResult;
                window.__nhTocState = 'running';

                const LIST_SEL = $listSel;
                const PAGER_SEL = $pagerSel;
                const PAGE_PARAM = $pageParam;
                const FIRST_PAGE = $firstPage;
                const MAX_PAGES = $maxPages;

                const collect = (root) => {
                    const out = [];
                    root.querySelectorAll(LIST_SEL).forEach(a => {
                        try {
                            const href = a.getAttribute('href');
                            if (!href) return;
                            const u = new URL(href, location.href);
                            if (u.origin !== location.origin) return;
                            const text = (a.innerText || a.textContent || '').trim();
                            out.push({ href: u.href, text: text.slice(0, 160) });
                        } catch (e) {}
                    });
                    return out;
                };

                const lastPage = () => {
                    let max = FIRST_PAGE;
                    document.querySelectorAll(PAGER_SEL).forEach(a => {
                        const fromText = parseInt((a.innerText || '').trim(), 10);
                        if (!isNaN(fromText) && fromText > max) max = fromText;
                        const href = a.getAttribute('href') || '';
                        const m = href.match(new RegExp(PAGE_PARAM + '=(\\\\d+)'));
                        if (m) { const n = parseInt(m[1], 10); if (!isNaN(n) && n > max) max = n; }
                    });
                    return Math.min(max, MAX_PAGES);
                };

                const pageUrl = (n) => {
                    const u = new URL(location.href);
                    u.searchParams.set(PAGE_PARAM, String(n));
                    return u.href;
                };

                (async () => {
                    try {
                        const seen = new Set();
                        const all = [];
                        const add = (items) => items.forEach(it => {
                            if (!seen.has(it.href)) { seen.add(it.href); all.push(it); }
                        });

                        add(collect(document));

                        const last = lastPage();
                        const parser = new DOMParser();
                        const GROUP = 4;

                        for (let p = FIRST_PAGE + 1; p <= last; p += GROUP) {
                            const batch = [];
                            for (let i = p; i < p + GROUP && i <= last; i++) batch.push(i);

                            const docs = await Promise.all(batch.map(n =>
                                fetch(pageUrl(n), { credentials: 'same-origin' })
                                    .then(r => r.ok ? r.text() : '')
                                    .catch(() => '')
                            ));

                            docs.forEach(html => {
                                if (!html) return;
                                add(collect(parser.parseFromString(html, 'text/html')));
                            });

                            window.__nhTocProgress = all.length;
                            await new Promise(r => setTimeout(r, 400));
                        }

                        window.__nhTocResult = all;
                    } catch (e) {
                        window.__nhTocResult = [];
                    } finally {
                        window.__nhTocState = 'done';
                    }
                })();

                return { ready: false };
            })()
        """.trimIndent()
    }

    private fun buildChapterContentScript(): String {
        val tSel = JsResultParser.jsLiteral(config.chapterTitleSelector)
        val bSel = JsResultParser.jsLiteral(config.chapterBodySelector)
        val readySel = JsResultParser.jsLiteral(config.chapterReadySelector)

        val defaultJunk = "script, style, noscript, nav, header, footer, iframe, form, button, " +
            "select, textarea, .ad, .ads, .advertisement, .comments, #comments, " +
            ".share, .social, .chapter-nav, .nav-links, .toolbar"
        val customJunk = config.junkSelectors.filter { it.isNotBlank() }.joinToString(", ")
        val combinedJunk = if (customJunk.isNotBlank()) "$defaultJunk, $customJunk" else defaultJunk
        val jSel = JsResultParser.jsLiteral(combinedJunk)

        return """
            (() => {
                const READY_SEL = $readySel;
                if (READY_SEL) {
                    const gate = document.querySelector(READY_SEL);
                    if (!gate) return { ready: false };
                    const gateText = (gate.innerText || gate.textContent || '').trim();
                    if (gateText.length < 200) return { ready: false };
                }

                let title = "";
                const h = document.querySelector($tSel);
                if (h) title = (h.innerText || h.textContent || '').trim();
                if (!title) title = (document.title || '').split('|')[0].trim();

                const body = document.querySelector($bSel);
                if (!body) return { ready: false };

                const clone = body.cloneNode(true);
                clone.querySelectorAll($jSel).forEach(el => el.remove());

                // A cloned node is detached, so innerText is unavailable and every read silently falls
                // through to textContent — which drops all line breaks. Turn the breaks into text nodes
                // first, then read the tree once.
                clone.querySelectorAll('br').forEach(br => {
                    if (br.parentNode) br.parentNode.replaceChild(document.createTextNode('\n'), br);
                });
                clone.querySelectorAll('p, div, li, blockquote, h1, h2, h3, h4, h5, h6')
                     .forEach(el => el.appendChild(document.createTextNode('\n\n')));

                const text = (clone.textContent || '')
                    .split('\n').map(s => s.trim()).filter(s => s.length > 0).join('\n\n');

                // Guard against single-page-app readers that swap chapter text in place:
                // require the text to differ from the previous chapter AND to be stable across
                // two consecutive polls before accepting it.
                const fp = text.length + ':' + text.replace(/\s+/g, ' ').trim().slice(0, 200);
                const prev = window.__nhPrevFp;
                const last = window.__nhLastPluginFp;
                window.__nhLastPluginFp = fp;

                const changed = !prev || fp !== prev;
                const stable = last === fp;

                return {
                    ready: text.length > 200 && changed && stable,
                    title: title || "Chapter",
                    content: text
                };
            })()
        """.trimIndent()
    }

    private suspend fun loadUrlAndWait(webView: WebView, url: String) {
        if (config.customUserAgent.isNotBlank()) {
            webView.settings.userAgentString = config.customUserAgent
        }
        withTimeoutOrNull(ScrapeTimeouts.chapterMs) {
            suspendCancellableCoroutine<Unit> { continuation ->
                webView.webViewClient = object : WebViewFactory.SafeWebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        android.util.Log.w("PluginEngine", "Renderer process exited during load. Handled cleanly.")
                        if (continuation.isActive) continuation.resume(Unit)
                        return true
                    }
                }
                webView.loadUrl(url)
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
}
