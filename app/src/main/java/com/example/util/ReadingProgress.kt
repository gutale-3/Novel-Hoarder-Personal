package com.example.util

import com.example.data.local.BookEntity
import com.example.data.local.BookStats

/**
 * Where you are in a novel, by chapter number. Chapters before the furthest one you've read count
 * as read even if they were never downloaded (e.g. you started at chapter 900), so "left" means
 * chapters still ahead of you, not unopened rows in the database.
 */
data class ReadingProgress(
    val latest: Int,
    val readUpTo: Int,
    val downloaded: Int
) {
    val left: Int get() = (latest - readUpTo).coerceAtLeast(0)
    val started: Boolean get() = readUpTo > 0
    val caughtUp: Boolean get() = started && left == 0
    val fraction: Float get() = if (latest <= 0) 0f else (readUpTo.toFloat() / latest).coerceIn(0f, 1f)

    /** e.g. "Ch. 1303 of 1318 · 15 left", "Caught up · ch. 1318", "Not started · 316 chapters". */
    val label: String get() = when {
        latest <= 0 -> if (downloaded > 0) "$downloaded chapters" else "No chapters yet"
        caughtUp -> "Caught up · ch. $latest"
        !started -> "Not started · $latest chapters"
        else -> "Ch. $readUpTo of $latest · $left left"
    }

    companion object {
        fun of(book: BookEntity, stats: BookStats?): ReadingProgress {
            val latest = maxOf(book.totalChapters, stats?.maxChapterNumber ?: 0)
            val readUpTo = maxOf(book.lastReadChapterNumber, stats?.maxReadChapterNumber ?: 0)
                .let { if (latest > 0) it.coerceAtMost(latest) else it }
            return ReadingProgress(latest, readUpTo, stats?.downloadedCount ?: 0)
        }
    }
}
