package com.example.viewmodel

import com.example.data.local.ChapterEntity
import com.example.util.ChapterCleaner
import com.example.util.ChapterUrls

/**
 * Finds chapters saved twice: the same page under two addresses, or two copies with the same
 * chapter number and (mostly) the same text, e.g. one cleaned by hand and one raw re-download.
 * Different chapters that merely share a number (volume 2 restarting at "Chapter 1") have
 * different text and are left alone.
 */
object DuplicateChapters {
    /** For each set of copies: the one to keep first, then the extras. */
    fun find(chapters: List<ChapterEntity>): List<List<ChapterEntity>> {
        val live = chapters.filter { !it.isDeleted }
        val groups = mutableListOf<List<ChapterEntity>>()
        val used = HashSet<String>()

        live.filter { !it.url.startsWith("manual://") }
            .groupBy { ChapterUrls.normalize(it.url) }
            .values.filter { it.size > 1 }
            .forEach { g -> groups += g; used += g.map { it.id } }

        live.filter { it.id !in used && it.content.isNotBlank() }
            .groupBy { ChapterCleaner.detectChapterNumber(it.title) }
            .filterKeys { it != null }
            .values.forEach { sameNumber ->
                val pool = sameNumber.toMutableList()
                while (pool.size > 1) {
                    val first = pool.removeAt(0)
                    val copies = pool.filter { similar(first.content, it.content) }
                    if (copies.isNotEmpty()) { groups += listOf(first) + copies; pool.removeAll(copies) }
                }
            }
        return groups.map { g -> g.sortedWith(keepOrder) }
    }

    // Keep the copy you've read (and maybe tidied), then the older one, then the longer one.
    private val keepOrder = compareByDescending<ChapterEntity> { it.isRead }
        .thenByDescending { it.content.isNotBlank() }
        .thenBy { it.downloadedAt }

    /** Most of the longer paragraphs appear in both. */
    internal fun similar(a: String, b: String): Boolean {
        fun paras(t: String) = t.split("\n").map { it.trim() }.filter { it.length >= 40 }.toSet()
        val pa = paras(a); val pb = paras(b)
        if (pa.isEmpty() || pb.isEmpty()) return a.trim() == b.trim()
        val shared = pa.intersect(pb).size
        return shared >= 0.6 * minOf(pa.size, pb.size)
    }
}
