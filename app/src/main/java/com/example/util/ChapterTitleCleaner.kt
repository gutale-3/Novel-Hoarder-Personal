package com.example.util

/**
 * Turns page titles like "The Wizard of the Steam Age Chapter 4 - WTR-LAB" into "Chapter 4".
 * Sites put the novel's name and their own name around the chapter title; neither belongs in a
 * table of contents. Pure, so every save path and the one-time library fix share it.
 */
object ChapterTitleCleaner {

    /** Site names that appear as " - Site" / " | Site" suffixes or prefixes. */
    private val SITE_NAMES = listOf(
        "wtr-lab", "wtr lab", "wtrlab", "novelbin", "novel bin", "novelfire", "novel fire",
        "readnovelfull", "read novel full", "novelfull", "novel full", "wuxiabox", "wuxia box",
        "tomatomtl", "tomato mtl", "royal road", "royalroad", "scribblehub", "scribble hub",
        "freewebnovel", "free web novel", "ranobes", "lightnovelpub", "light novel pub", "webnovel"
    )

    private val SEPARATORS = Regex("\\s+[-–—|:·•]+\\s+|\\s*\\|\\s*")

    fun clean(raw: String, bookTitle: String, sourceName: String = "", chapterNumber: Int? = null): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()

        // 1. Drop leading/trailing separator-delimited segments that are just a site name or a
        //    "Read ... online" blurb.
        val sites = SITE_NAMES + listOf(sourceName.lowercase()).filter { it.isNotBlank() }
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val kept = parts.filterNot { part -> isSiteOrBlurb(part, sites) }
            // Only rebuild when something was dropped, so "Chapter 4 - The Duel" keeps its dash.
            if (kept.isNotEmpty() && kept.size < parts.size) t = rejoin(kept)
        }

        // 2. Remove the novel's own title wherever it starts or ends the chapter title.
        bookTitleRegex(bookTitle)?.let { re ->
            val withoutPrefix = t.replace(Regex("^\\s*(?:${re})[\\s\\p{Punct}–—]*", RegexOption.IGNORE_CASE), "")
            val withoutSuffix = withoutPrefix.replace(Regex("[\\s\\p{Punct}–—]*(?:${re})\\s*$", RegexOption.IGNORE_CASE), "")
            if (withoutSuffix.isNotBlank()) t = withoutSuffix
            else if (chapterNumber != null) t = ""
        }

        t = t.trim(' ', '-', '–', '—', '|', ':', '：', '·', '•', ',').trim()

        // 3. Fill in what is left.
        if (t.isBlank()) return chapterNumber?.let { "Chapter $it" } ?: raw.trim()
        if (t.matches(Regex("\\d+"))) return "Chapter $t"
        return t
    }

    private fun isSiteOrBlurb(part: String, sites: List<String>): Boolean {
        val p = part.lowercase().trim()
        if (sites.any { p == it || p == "$it.com" || p == "$it.net" || p == "www.$it.com" }) return true
        if (Regex("^read\\b.*\\b(online|free)\\b.*").matches(p)) return true
        if (Regex("^(read )?(light )?novels? online( free)?$").matches(p)) return true
        return false
    }

    /** Rebuilds the title from kept segments, using ": " between them. */
    private fun rejoin(kept: List<String>): String =
        if (kept.size == 1) kept[0] else kept.joinToString(": ")

    /** The book title as a pattern tolerant of punctuation and spacing differences. */
    private fun bookTitleRegex(bookTitle: String): String? {
        val words = bookTitle.lowercase()
            .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), " ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty() || words.joinToString("").length < 3) return null
        return words.joinToString("[^\\p{L}\\p{N}]+") { Regex.escape(it) }
    }
}
