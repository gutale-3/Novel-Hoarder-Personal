package com.example.viewmodel

import com.example.data.local.ChapterEntity
import com.example.util.ChapterCleaner
import com.example.util.ChapterUrls

/**
 * Lines a novel's saved chapters up with the site's chapter list: saved chapters take the site's
 * order, chapters the site has but you don't are added as empty entries (titles only, nothing is
 * downloaded), and pages only you have (author notes, extras saved from the browser) stay right
 * after the chapter they followed. Positions become 1, 2, 3… with no gaps or repeats.
 */
object ChapterStructure {
    data class TocItem(val url: String, val title: String)

    data class Plan(
        /** New position for every saved chapter (by id). */
        val numbers: Map<String, Int>,
        /** Better titles for saved chapters that only had a placeholder name ("Chapter 85"). */
        val titles: Map<String, String>,
        /** Site chapters to add, with their position. */
        val added: List<Pair<TocItem, Int>>,
        val matched: Int,
        val extras: Int
    )

    private val GENERIC_TITLE = Regex("(?i)^\\s*chapter\\s*\\d+\\s*$")

    fun plan(local: List<ChapterEntity>, toc: List<TocItem>, skipUrls: Set<String>): Plan {
        val ordered = local.sortedBy { it.chapterNumber }
        val remaining = ordered.toMutableList()
        val slots = arrayOfNulls<ChapterEntity>(toc.size)

        // 1. Same page (by address).
        val byUrl = ordered.groupBy { ChapterUrls.normalize(it.url) }
        toc.forEachIndexed { i, t ->
            val m = byUrl[ChapterUrls.normalize(t.url)]?.firstOrNull { it in remaining } ?: return@forEachIndexed
            slots[i] = m; remaining.remove(m)
        }
        // 2. Same printed chapter number, for pages saved under an address the site doesn't list.
        toc.forEachIndexed { i, t ->
            if (slots[i] != null) return@forEachIndexed
            val p = ChapterCleaner.detectChapterNumber(t.title) ?: return@forEachIndexed
            val m = remaining.firstOrNull { ChapterCleaner.detectChapterNumber(it.title) == p } ?: return@forEachIndexed
            slots[i] = m; remaining.remove(m)
        }

        // 3. Your own extra pages stay after the chapter they followed.
        val slotOf = HashMap<String, Int>()
        slots.forEachIndexed { i, c -> if (c != null) slotOf[c.id] = i }
        val extrasAfter = HashMap<Int, MutableList<ChapterEntity>>()
        var last = -1
        for (c in ordered) {
            val s = slotOf[c.id]
            if (s != null) last = s else extrasAfter.getOrPut(last) { mutableListOf() } += c
        }

        val numbers = LinkedHashMap<String, Int>()
        val titles = HashMap<String, String>()
        val added = mutableListOf<Pair<TocItem, Int>>()
        var n = 0
        extrasAfter[-1]?.forEach { numbers[it.id] = ++n }
        toc.forEachIndexed { i, t ->
            val c = slots[i]
            if (c != null) {
                numbers[c.id] = ++n
                if (GENERIC_TITLE.matches(c.title) && t.title.isNotBlank() && !GENERIC_TITLE.matches(t.title)) titles[c.id] = t.title
            } else if (ChapterUrls.normalize(t.url) !in skipUrls) {
                added += t to ++n
            }
            extrasAfter[i]?.forEach { numbers[it.id] = ++n }
        }
        return Plan(numbers, titles, added, matched = slotOf.size, extras = ordered.size - slotOf.size)
    }

    // ---- how chapter numbers are shown ----

    enum class Numbering { SITE, NOVEL }

    private val LEADING_NUMBER = Regex("(?i)^\\s*(chapter|ch\\.?)\\s*\\d+\\s*[:.\\-–—]?\\s*")

    /**
     * SITE: the chapter's place in the list ("85: The Duel"), every entry counted.
     * NOVEL: the novel's own number as written in the title ("Chapter 79: The Duel").
     */
    fun label(c: ChapterEntity, numbering: Numbering): String = when (numbering) {
        Numbering.NOVEL -> c.title
        Numbering.SITE -> {
            val rest = c.title.replace(LEADING_NUMBER, "").trim()
            if (rest.isEmpty()) "Chapter ${c.chapterNumber}" else "${c.chapterNumber}. $rest"
        }
    }
}
