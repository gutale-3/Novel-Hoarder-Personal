package com.example.util

/**
 * Drops the heading lines many sites repeat at the top of the chapter text ("Chapter 81: …",
 * "Chapter 84: …", "第84章 …"). The reader already shows the chapter title, so these only push
 * the story down — and when two different numbers appear they are confusing.
 */
object ChapterTextCleaner {

    private val HEADING = listOf(
        Regex("(?i)^(chapter|chap\\.?|ch\\.?|episode|ep\\.?)\\s*[0-9]+[a-z]?\\b.*$"),
        Regex("^第\\s*[0-9零一二三四五六七八九十百千两]+\\s*[章节回卷話话].*$"),
        Regex("^#?\\s*[0-9]+\\s*[.:：-]?\\s*$")
    )

    /** Only the first few lines are looked at, and only short, heading-shaped ones go. */
    fun stripLeadingHeadings(text: String, maxLines: Int = 4): String {
        val lines = text.split("\n")
        var i = 0
        var removed = 0
        while (i < lines.size && removed < maxLines) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i++; continue }
            if (isHeading(line)) { removed++; i++ } else break
        }
        if (removed == 0) return text
        val rest = lines.drop(i).joinToString("\n").trimStart('\n')
        // Never strip a chapter down to nothing.
        return if (rest.isBlank()) text else rest
    }

    fun isHeading(line: String): Boolean {
        if (line.length > 150) return false
        // Dialogue or a sentence that happens to start with "Chapter" is story text.
        if (line.contains('"') || line.contains('“') || line.contains('”')) return false
        // A long line ending like a sentence is prose ("Chapter 12 was the best part…").
        if (line.endsWith('.') && line.split(' ').size > 12) return false
        return HEADING.any { it.matches(line) }
    }
}
