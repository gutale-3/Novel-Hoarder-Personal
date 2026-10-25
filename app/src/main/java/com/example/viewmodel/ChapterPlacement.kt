package com.example.viewmodel

import com.example.data.local.ChapterEntity
import com.example.util.ChapterCleaner

/**
 * Decides where a chapter saved from the browser goes. A novel's list position and its printed
 * chapter number drift apart once author notes or filler chapters are in it (position 84 may hold
 * "Chapter 80"), so placement goes by the printed numbers in the saved titles, not by position.
 */
object ChapterPlacement {
    data class Plan(val position: Int, val fill: ChapterEntity?, val note: String = "")

    private fun printed(c: ChapterEntity) = ChapterCleaner.detectChapterNumber(c.title)

    fun place(all: List<ChapterEntity>, byUrl: ChapterEntity?, printedNumber: Int?, lastCaptured: Int?): Plan {
        // Same page saved before (even under a slightly different address): update it in place.
        if (byUrl != null) return Plan(byUrl.chapterNumber, byUrl)
        val live = all.filter { !it.isDeleted }
        val end = (all.maxOfOrNull { it.chapterNumber } ?: 0) + 1

        if (printedNumber == null) {
            // Author notes and other unnumbered pages: right after the last chapter you saved.
            val after = lastCaptured ?: return Plan(end, null)
            return Plan(after + 1, null, " (no chapter number: placed after the last one you saved)")
        }

        live.firstOrNull { printed(it) == printedNumber }?.let { same ->
            return Plan(same.chapterNumber, same, if (same.content.isBlank()) "" else " (replaced the saved copy)")
        }
        // Between its neighbours by printed number.
        live.filter { (printed(it) ?: Int.MAX_VALUE) < printedNumber }.maxByOrNull { printed(it)!! }?.let { before ->
            return Plan(before.chapterNumber + 1, null)
        }
        live.filter { (printed(it) ?: Int.MIN_VALUE) > printedNumber }.minByOrNull { printed(it)!! }?.let { next ->
            return Plan(next.chapterNumber, null)
        }
        return Plan(end, null)
    }
}
