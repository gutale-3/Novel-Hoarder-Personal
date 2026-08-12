package com.example.util

/**
 * Pure Kotlin cleaner for web novel chapter content.
 * Removes website chrome, UI fragments, duplicate paragraphs, and ad lines.
 */
object ChapterCleaner {

    data class ChapterMark(val lineIndex: Int, val number: Int?)

    private val UI_LINE_PATTERNS = listOf(
        Regex("(?i)^glossary is a list of terms"),
        Regex("(?i)^replacement is for editing"),
        Regex("(?i)^powered by\\s+(gemini|google|openai|gpt|chatgpt|claude|deepl|papago|baidu|bing)"),
        Regex("(?i)^loading\\s*\\.{0,3}$"),
        Regex("(?i)^please only\\s*update names"),
        Regex("(?i)^choose entity types"),
        Regex("(?i)^ask your own question"),
        Regex("(?i)^translate (this )?chapter$"),
        Regex("(?i)^(previous|prev|next)( chapter)?$"),
        Regex("(?i)^report (an )?(error|chapter|problem)"),
        Regex("(?i)^add to (library|bookmarks?|favou?rites?)$"),
        Regex("(?i)^\\d+\\s+comments?$"),
        Regex("(?i)^advertisement$"),
        Regex("(?i)^(share|tweet|facebook|telegram|discord|whatsapp)$"),
        Regex("(?i)^chapter list$"),
        Regex("(?i)^table of contents$"),
        Regex("(?i)^ch\\.?\\s*\\d+\\s*/\\s*\\d+"),
        Regex("(?i)^\\d+(\\.\\d+)?\\s*%"),
        Regex("(?i)^web(\\s*\\+\\s*ai)?$"),
        Regex("(?i)^ai$"),
        Regex("(?i)^raw$"),
        Regex("(?i)^#\\d+$"),
        Regex("(?i)^font\\s*(size)?$"),
        Regex("(?i)^(increase|decrease)\\s+font$"),
        Regex("(?i)^capitalize\\s*:?$"),
        Regex("(?i)^translate\\s*:?$"),
        Regex("(?i)^search\\s*:?$"),
        Regex("(?i)^priority\\s*:?$"),
        Regex("(?i)^entity types\\s*:?$"),
        Regex("(?i)^named entity recognition"),
        Regex("(?i)^running ner"),
        Regex("(?i)^original \\(translated line\\)\\s*:?$"),
        Regex("(?i)^replace with\\s*:?$"),
        Regex("(?i)^case sensitive$"),
        Regex("(?i)^reading settings$"),
        Regex("(?i)^background\\s*:?$"),
        Regex("(?i)^text colou?r\\s*:?$"),
        Regex("(?i)^font (size|family)\\s*:?$"),
        Regex("(?i)^text align\\s*:?$"),
        Regex("(?i)^ai assistant$"),
        Regex("(?i)^(jpname|gtrans|linguee|pinyin)$"),
        Regex("(?i)^(google|bing|deepl|gemini|papago|yandex)$"),
        Regex("(?i)^(source|translation)$"),
        Regex("(?i)^✓$"),
        Regex("(?i)^ner detects")
    )

    private val UI_FRAGMENTS = listOf(
        "improve translation quality",
        "add selected rows to this book glossary",
        "review translations, then add",
        "named entities (person, location, organization)",
        "according to your preference",
        "not entire paragraphs",
        "choose entity types above"
    )

    fun normalizeText(text: String): String {
        return text
            .replace("\u00A0", " ")
            .replace("ﬀ", "ff")
            .replace("ﬁ", "fi")
            .replace("ﬂ", "fl")
            .replace("ﬃ", "ffi")
            .replace("ﬄ", "ffl")
            .replace("ﬅ", "ft")
            .replace("ﬆ", "st")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun isUiLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        for (pattern in UI_LINE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) return true
        }
        val lower = trimmed.lowercase()
        for (fragment in UI_FRAGMENTS) {
            if (lower.contains(fragment)) return true
        }
        return false
    }

    fun looksLikeProse(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 15) return false
        val hasSentenceEnd = trimmed.endsWith(".") || trimmed.endsWith("!") ||
                trimmed.endsWith("?") || trimmed.endsWith(".\"") ||
                trimmed.endsWith("!\"") || trimmed.endsWith("?\"") ||
                trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？」")
        return hasSentenceEnd && trimmed.firstOrNull()?.isUpperCase() == true
    }

    fun isChapterHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length > 80) return false
        return Regex("(?i)^(chapter|ch\\.?|episode|ep\\.?|volume|vol\\.?)?\\s*\\d+([:.-].*)?$").matches(trimmed) ||
                Regex("(?i)^第\\s*[0-9一二三四五六七八九十百千]+\\s*[章回节]").matches(trimmed)
    }

    fun findChapterMarks(lines: List<String>): List<ChapterMark> {
        val marks = mutableListOf<ChapterMark>()
        for ((index, line) in lines.withIndex()) {
            if (isChapterHeading(line)) {
                val num = detectChapterNumber(line)
                marks.add(ChapterMark(index, num))
            }
        }
        return marks
    }

    fun detectChapterNumber(body: String): Int? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        for (line in lines) {
            if (line.length > 120) continue
            // Must ignore "Ch. 3 / 70" progress widgets
            if (Regex("(?i)^ch\\.?\\s*\\d+\\s*/\\s*\\d+").containsMatchIn(line)) continue
            
            val match = Regex("(?i)^(?:chapter|ch\\.?|episode|ep\\.?|part)\\s*(\\d+)").find(line)
                ?: Regex("(?i)第\\s*(\\d+)\\s*[章回节]").find(line)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    fun detectChapterTitle(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(5)
        for (line in lines) {
            if (isChapterHeading(line) || line.length in 3..80) {
                if (!looksLikeBookTitleNotChapter(line)) {
                    return line
                }
            }
        }
        return null
    }

    fun looksLikeBookTitleNotChapter(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("novel") || lower.contains("read online") || lower.contains("index")
    }

    fun truncateAtNextChapterHeading(body: String): String {
        val lines = body.lines()
        var headingCount = 0
        var cutIndex = -1

        for ((i, line) in lines.withIndex()) {
            if (isChapterHeading(line)) {
                headingCount++
                if (headingCount == 2) {
                    cutIndex = i
                    break
                }
            }
        }

        if (cutIndex > 0) {
            return lines.subList(0, cutIndex).joinToString("\n").trim()
        }
        return body
    }

    fun isolateChapter(body: String, chapterNumber: Int?): String {
        if (chapterNumber == null) return truncateAtNextChapterHeading(body)
        val lines = body.lines()
        val marks = findChapterMarks(lines)
        if (marks.isEmpty()) return body

        val targetMark = marks.find { it.number == chapterNumber } ?: marks.first()
        val startIndex = targetMark.lineIndex
        val nextMark = marks.find { it.lineIndex > startIndex }
        val endIndex = nextMark?.lineIndex ?: lines.size

        return lines.subList(startIndex, endIndex).joinToString("\n").trim()
    }

    fun dropLeadingChromeBeforeTitle(body: String): String {
        val lines = body.lines()
        var titleIndex = -1
        for ((i, line) in lines.withIndex().take(15)) {
            if (isChapterHeading(line)) {
                titleIndex = i
                break
            }
        }
        if (titleIndex > 0) {
            return lines.subList(titleIndex, lines.size).joinToString("\n").trim()
        }
        return body
    }

    fun stripCarriedOverPrefix(newBody: String, previousBody: String): String {
        if (newBody.isBlank() || previousBody.isBlank()) return newBody
        val prevTail = previousBody.takeLast(300).trim()
        if (prevTail.length < 50) return newBody

        val newLines = newBody.lines()
        val matchIndex = newLines.indexOfFirst { line ->
            line.trim().length > 30 && prevTail.contains(line.trim())
        }

        if (matchIndex >= 0 && matchIndex < 5) {
            return newLines.subList(matchIndex + 1, newLines.size).joinToString("\n").trim()
        }
        return newBody
    }

    fun dropAdjacentNearDuplicates(text: String): String {
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.size <= 1) return text

        val result = mutableListOf<String>()
        for (p in paragraphs) {
            if (result.isNotEmpty()) {
                val prev = result.last()
                // Short lines (e.g. dialogue "No.") are preserved
                if (p.length > 20 && (p == prev || (p.length > 40 && prev.contains(p)))) {
                    continue
                }
            }
            result.add(p)
        }
        return result.joinToString("\n\n")
    }

    fun cleanBookTitle(raw: String): String {
        return raw.split("|")[0]
            .split(" - ")[0]
            .replace(Regex("(?i)\\s*-\\s*Read\\s+Online.*"), "")
            .replace(Regex("(?i)\\s*\\|?\\s*TomatoMTL.*"), "")
            .trim()
    }

    fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim(' ', '-', '|', ':', '：')
    }

    fun clean(raw: String): String {
        val normalized = normalizeText(raw)
        val filteredLines = normalized.lines().filter { line ->
            if (looksLikeProse(line)) return@filter true
            !isUiLine(line)
        }
        val cleaned = filteredLines.joinToString("\n")
        return dropAdjacentNearDuplicates(cleaned)
    }
}
