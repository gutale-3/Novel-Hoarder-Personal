package com.example.util

import android.content.Context

/**
 * The same chapter often appears under slightly different addresses (http/https, "www.", a
 * trailing slash, tracking parameters like "?service=web"). Comparing raw strings saved it twice;
 * everything that asks "do we already have this chapter?" compares these normalised forms.
 * (Chapter ids already ignore the query part, so this matches them.)
 */
object ChapterUrls {
    fun normalize(url: String): String {
        var u = url.trim().substringBefore('#').substringBefore('?')
        u = u.replaceFirst(Regex("^(?i)https?://"), "").replaceFirst(Regex("^(?i)www\\."), "")
        val slash = u.indexOf('/')
        u = if (slash < 0) u.lowercase() else u.substring(0, slash).lowercase() + u.substring(slash)
        return u.trimEnd('/')
    }

    // ---- chapters you deleted for good: never bring them back from the site's list ----

    private fun prefs(c: Context) = c.getSharedPreferences("deleted_chapter_urls", Context.MODE_PRIVATE)

    fun rememberDeleted(c: Context, bookId: String, urls: Collection<String>) {
        val add = urls.filter { it.isNotBlank() && !it.startsWith("manual://") }.map(::normalize)
        if (add.isEmpty()) return
        val set = prefs(c).getStringSet(bookId, emptySet()).orEmpty() + add
        prefs(c).edit().putStringSet(bookId, set).apply()
    }

    fun deletedFor(c: Context, bookId: String): Set<String> = prefs(c).getStringSet(bookId, emptySet()).orEmpty()

    fun forget(c: Context, bookId: String) = prefs(c).edit().remove(bookId).apply()
}
