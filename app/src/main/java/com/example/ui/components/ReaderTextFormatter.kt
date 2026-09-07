package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.data.local.GlossaryEntity

fun softHyphenateText(text: String, enabled: Boolean): String {
    if (!enabled) return text
    // Soft hyphen character \u00AD allows the Android text layout engine to break long words gracefully
    val words = text.split(" ")
    return words.joinToString(" ") { word ->
        if (word.length > 8 && !word.contains("\u00AD") && !word.contains("-")) {
            val mid = word.length / 2
            word.substring(0, mid) + "\u00AD" + word.substring(mid)
        } else {
            word
        }
    }
}

fun highlightGlossaryTerms(
    text: String,
    glossaries: List<GlossaryEntity>,
    highlightColor: Color
): AnnotatedString {
    if (glossaries.isEmpty() || text.isBlank()) {
        return AnnotatedString(text)
    }

    val activeTerms = glossaries.filter { it.originalText.isNotBlank() }
    if (activeTerms.isEmpty()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var cursor = 0
        val lowerText = text.lowercase()

        // Find all matches with their start, end, and glossary item
        data class Match(val start: Int, val end: Int, val glossary: GlossaryEntity)
        val matches = mutableListOf<Match>()

        for (term in activeTerms) {
            val search = term.originalText.lowercase()
            var startIdx = lowerText.indexOf(search)
            while (startIdx != -1) {
                val endIdx = startIdx + search.length
                // Avoid overlapping matches
                val overlaps = matches.any { (startIdx < it.end && endIdx > it.start) }
                if (!overlaps) {
                    matches.add(Match(startIdx, endIdx, term))
                }
                startIdx = lowerText.indexOf(search, endIdx)
            }
        }

        matches.sortBy { it.start }

        for (match in matches) {
            if (match.start > cursor) {
                append(text.substring(cursor, match.start))
            }
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(match.start, match.end))
            }
            cursor = match.end
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
