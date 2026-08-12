package com.example.ui.theme

import androidx.compose.ui.text.style.TextOverflow

/**
 * Shared text-truncation defaults.
 *
 * A Compose Text with no maxLines will wrap forever, which is why single words were appearing on
 * two lines inside buttons and list rows. Anything on one line must say so explicitly.
 */
object TextDefaults {
    const val SINGLE_LINE = 1
    const val TITLE_LINES = 2
    const val BODY_LINES = 3
    val OVERFLOW = TextOverflow.Ellipsis
}
