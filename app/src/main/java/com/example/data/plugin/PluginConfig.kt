package com.example.data.plugin

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

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

    @Json(name = "isEnabled") val isEnabled: Boolean = true
)
