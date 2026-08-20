package com.example.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

object BionicReadingHelper {

    /**
     * Converts a regular string into an AnnotatedString where the first portion of each word
     * is styled with FontWeight.Bold (Bionic Reading) to guide eye fixation points.
     */
    fun formatBionicText(
        text: String,
        baseColor: Color = Color.Unspecified,
        boldColor: Color = Color.Unspecified
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")

        return buildAnnotatedString {
            val length = text.length
            var i = 0

            while (i < length) {
                // If whitespace or punctuation, append as is
                if (!text[i].isLetterOrDigit()) {
                    append(text[i])
                    i++
                    continue
                }

                // Find word boundary
                val start = i
                while (i < length && text[i].isLetterOrDigit()) {
                    i++
                }
                val word = text.substring(start, i)
                val wordLen = word.length

                val boldLen = when {
                    wordLen <= 1 -> 1
                    wordLen <= 3 -> 1
                    wordLen <= 5 -> 2
                    wordLen <= 7 -> 3
                    else -> (wordLen * 0.45f).toInt().coerceAtLeast(1)
                }

                val boldPart = word.substring(0, boldLen)
                val restPart = word.substring(boldLen)

                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Black,
                        color = if (boldColor != Color.Unspecified) boldColor else baseColor
                    )
                ) {
                    append(boldPart)
                }

                if (restPart.isNotEmpty()) {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Normal,
                            color = baseColor
                        )
                    ) {
                        append(restPart)
                    }
                }
            }
        }
    }
}
