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

    /** True for plugins shipped inside the app. Set by the loader, never read from JSON. */
    @Transient val isBuiltIn: Boolean = false
)
