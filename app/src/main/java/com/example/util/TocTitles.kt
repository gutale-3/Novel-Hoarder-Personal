package com.example.util

import java.util.concurrent.ConcurrentHashMap

/**
 * The link text of each chapter in the last chapter lists read from a site ("Chapter 79: The
 * Duel"), by normalised address. Chapter-list readers fill it; "Fix chapter order" uses it to
 * name chapters that aren't downloaded yet with their real titles.
 */
object TocTitles {
    private val titles = ConcurrentHashMap<String, String>()

    fun put(url: String, title: String) {
        val t = title.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty() && t.length <= 200) titles[ChapterUrls.normalize(url)] = t
    }

    fun get(url: String): String? = titles[ChapterUrls.normalize(url)]
}
