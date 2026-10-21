package com.example.data.plugin

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A site definition. Selector-based by default; the three `custom*Js` fields let a site that needs
 * real logic (an SPA, a JSON API, a virtualised list) supply it without any Kotlin changes.
 */
@JsonClass(generateAdapter = true)
data class PluginConfig(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "version") val version: String = "1.0.0",
    @Json(name = "baseUrl") val baseUrl: String,
    @Json(name = "author") val author: String = "User",
    @Json(name = "description") val description: String = "",

    @Json(name = "titleSelector") val titleSelector: String = "h1",
    @Json(name = "authorSelector") val authorSelector: String = "",
    @Json(name = "synopsisSelector") val synopsisSelector: String = ".synopsis, .description, meta[name='description']",
    @Json(name = "coverSelector") val coverSelector: String = "img.cover, meta[property='og:image']",

    @Json(name = "chapterListSelector") val chapterListSelector: String = "a[href*='chapter'], a[href*='ch-']",
    @Json(name = "chapterTitleSelector") val chapterTitleSelector: String = "h1, .chapter-title",
    @Json(name = "chapterBodySelector") val chapterBodySelector: String = "article, main, .chapter-content, #content, .entry-content",

    @Json(name = "customJs") val customJs: String? = null,

    @Json(name = "isEnabled") val isEnabled: Boolean = true,

    // ---- added in this version ----

    /**
     * Extra hostnames this plugin also handles, beyond [baseUrl]. Matched the same way.
     * Example: a site reachable at both `wuxiabox.com` and `www.wuxiabox.com`.
     */
    @Json(name = "extraHosts") val extraHosts: List<String> = emptyList(),

    /**
     * How the table of contents is spread across pages.
     * `"none"`  – the whole list is on one page (the default, and what every existing plugin gets)
     * `"query"` – append `?<tocPageParam>=N`, pages numbered from [tocFirstPage]
     */
    @Json(name = "tocPaginationMode") val tocPaginationMode: String = "none",
    @Json(name = "tocPageParam") val tocPageParam: String = "page",
    @Json(name = "tocFirstPage") val tocFirstPage: Int = 1,

    /**
     * Selector matching the pagination links, used to discover the last page number. The largest
     * integer found in the text or href of any match wins.
     */
    @Json(name = "tocLastPageSelector") val tocLastPageSelector: String = ".pagination a, ul.pagination li a",

    /** Hard safety cap on TOC pages fetched, so a malformed pager cannot loop forever. */
    @Json(name = "tocMaxPages") val tocMaxPages: Int = 200,

    /**
     * Replaces the generated chapter-list script entirely. Must return a JS array of
     * `{ href, text }` objects, or `{ ready:false }` while it is still working.
     */
    @Json(name = "customTocJs") val customTocJs: String? = null,

    /**
     * Replaces the generated chapter-body script entirely. Must return
     * `{ ready, title, content }`.
     */
    @Json(name = "customChapterJs") val customChapterJs: String? = null,

    /**
     * A selector that must exist before the chapter body is read. Cheaper and far more reliable
     * than a fixed delay on a site that renders its text with JavaScript.
     */
    @Json(name = "chapterReadySelector") val chapterReadySelector: String = "",

    /** Minimum gap between page loads for this site, in milliseconds. 0 uses the global setting. */
    @Json(name = "requestDelayMs") val requestDelayMs: Int = 0,

    /**
     * Additional CSS selectors for junk/ad elements inside the chapter body to be stripped
     * (e.g. [".ad", ".author-note", ".watermark"]).
     */
    @Json(name = "junkSelectors") val junkSelectors: List<String> = emptyList(),

    /**
     * When true, reverses the collected chapter list so that sites listing newest-first are ordered 1..N.
     */
    @Json(name = "reverseOrder") val reverseOrder: Boolean = false,

    /**
     * Optional regex pattern that chapter URLs must match (e.g. ".*\/chapter-\d+.*").
     */
    @Json(name = "chapterLinkFilterRegex") val chapterLinkFilterRegex: String = "",

    /**
     * Optional regex pattern that chapter URLs must NOT match (e.g. ".*\/comments.*").
     */
    @Json(name = "chapterLinkExcludeRegex") val chapterLinkExcludeRegex: String = "",

    /**
     * Optional custom User-Agent string for sites with specific browser requirements.
     */
    @Json(name = "customUserAgent") val customUserAgent: String = "",

    /**
     * Where the chapter list lives when it is not on the book page itself. Placeholders:
     * `{url}` the book URL without a trailing slash, `{origin}` scheme + host, `{slug}` the last
     * path segment (minus `.html`), `{novelId}` the book page's `data-novel-id` attribute (falls
     * back to the slug). Examples: `{url}/chapters`, `{origin}/ajax/chapter-archive?novelId={novelId}`.
     */
    @Json(name = "tocUrlTemplate") val tocUrlTemplate: String = "",

    /**
     * In-app search. `{query}` is replaced with the URL-encoded search text. With [searchPostBody]
     * set the page is fetched with a form POST of that body instead of a GET.
     */
    @Json(name = "searchUrlTemplate") val searchUrlTemplate: String = "",
    @Json(name = "searchPostBody") val searchPostBody: String = "",
    /** Links to book pages on the search results page. */
    @Json(name = "searchResultSelector") val searchResultSelector: String = "",
    /** Replaces the generated results script. Must return `[{ title, url, cover }]` or `{ ready:false }`. */
    @Json(name = "customSearchJs") val customSearchJs: String? = null,

    /** Language of the site's text: "en", or "zh" for raw Chinese sites (translated on download). */
    @Json(name = "language") val language: String = "en",

    /** A page listing popular novels, shown under Discover. Uses [searchResultSelector]. */
    @Json(name = "browseUrl") val browseUrl: String = "",

    /** A known book on this site, used by the source health check. */
    @Json(name = "testUrl") val testUrl: String = "",

    /** True for plugins shipped inside the app. Set by the loader, never read from JSON. */
    @Transient val isBuiltIn: Boolean = false
)
