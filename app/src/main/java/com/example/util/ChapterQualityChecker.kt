package com.example.util

import com.example.data.local.ChapterEntity

/**
 * Finds downloaded chapters that are not really the chapter: login/limit/locked notices,
 * copies of another chapter, text cut far shorter than the rest of the novel, and gaps in the
 * numbering. Pure, so it is unit tested and cheap to run after every download session.
 */
object ChapterQualityChecker {

    enum class Problem(val label: String) {
        ERROR_PAGE("Login, limit or locked page"),
        DUPLICATE("Same text as another chapter"),
        TRUNCATED("Much shorter than usual")
    }

    data class Issue(val chapter: ChapterEntity, val problem: Problem, val detail: String)

    data class Report(
        val issues: List<Issue>,
        /** Chapter numbers absent between the first and last chapter the book has. */
        val missingNumbers: List<Int>,
        val checkedCount: Int
    ) {
        val badChapters: List<ChapterEntity> get() = issues.map { it.chapter }.distinctBy { it.id }
        val isClean: Boolean get() = issues.isEmpty() && missingNumbers.isEmpty()
    }

    private val ERROR_MARKERS = listOf(
        "login to", "log in to continue", "sign in to", "limit exceeded", "rate limit",
        "too many requests", "access denied", "unauthorized", "create an account", "please register",
        "sign in with", "this chapter is locked", "chapter is not available", "unlock this chapter",
        "purchase this chapter", "coins to unlock", "verify you are human", "just a moment"
    )

    fun looksLikeErrorPage(text: String): Boolean {
        if (text.length >= 2500) return false
        val lower = text.lowercase()
        return ERROR_MARKERS.any { lower.contains(it) }
    }

    fun check(chapters: List<ChapterEntity>): Report {
        val live = chapters.filter { !it.isDeleted }
        val downloaded = live.filter { it.content.isNotBlank() }
        val issues = mutableListOf<Issue>()

        downloaded.filter { looksLikeErrorPage(it.content) }.forEach {
            issues += Issue(it, Problem.ERROR_PAGE, it.content.trim().take(80))
        }

        // Duplicates: identical text under different chapter numbers. The earliest copy is kept
        // as the real one; later copies are flagged.
        downloaded
            .filter { it.content.length > 200 }
            .groupBy { it.hash.ifBlank { it.content.hashCode().toString() } }
            .values.filter { it.size > 1 }
            .forEach { group ->
                val sorted = group.sortedBy { it.chapterNumber }
                sorted.drop(1).forEach {
                    issues += Issue(it, Problem.DUPLICATE, "same as chapter ${sorted.first().chapterNumber}")
                }
            }

        // Truncation: compared with this novel's own typical length, so short-chapter novels are fine.
        val lengths = downloaded.map { it.content.length }.sorted()
        if (lengths.size >= 5) {
            val median = lengths[lengths.size / 2]
            if (median >= 2000) {
                val threshold = median * 0.3
                downloaded.filter { it.content.length < threshold && issues.none { i -> i.chapter.id == it.id } }
                    .forEach {
                        issues += Issue(it, Problem.TRUNCATED, "${it.content.length} chars vs usual ~$median")
                    }
            }
        }

        val numbers = live.map { it.chapterNumber }.filter { it > 0 }.toSortedSet()
        val missing = if (numbers.size >= 2) {
            (numbers.first()..numbers.last()).filter { it !in numbers }
        } else emptyList()

        return Report(issues.sortedBy { it.chapter.chapterNumber }, missing, downloaded.size)
    }
}
