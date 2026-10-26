package com.example.util

/**
 * "Lines that look like this one": numbers may differ, the rest must match.
 *  - "#10Chapter 10 Borrowing Longevity" → any line starting "#<n>Chapter"
 *  - "Chapter 10: The Duel"              → any line starting "Chapter <n>:"
 *  - a short line without numbers ("Next »", "x") → exactly that line
 * Only short lines (≤ 200 characters) are ever matched, so paragraphs of story are safe.
 */
object LinePattern {
    fun forLine(line: String): Regex {
        val t = line.trim().replace(Regex("\\s+"), " ")
        if (!t.any { it.isDigit() }) {
            return Regex("^\\s*" + Regex.escape(t) + "\\s*$", RegexOption.IGNORE_CASE)
        }
        // Words up to and including the first one with a number in it (at most three).
        val words = t.split(" ")
        val upTo = words.indexOfFirst { w -> w.any { it.isDigit() } }.coerceAtMost(2)
        val prefix = words.take(upTo + 1).joinToString("\\s+") { w ->
            w.split(Regex("\\d+")).joinToString("\\d+") { if (it.isEmpty()) "" else Regex.escape(it) }
        }
        return Regex("^\\s*$prefix.*$", RegexOption.IGNORE_CASE)
    }

    fun matches(pattern: Regex, line: String): Boolean =
        line.trim().replace(Regex("\\s+"), " ").let { it.isNotEmpty() && it.length <= 200 && pattern.matches(it) }
}
