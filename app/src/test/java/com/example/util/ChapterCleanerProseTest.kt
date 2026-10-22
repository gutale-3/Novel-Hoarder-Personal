package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterCleanerProseTest {
    @Test
    fun keepsDialogueAndNumberLinesButDropsUiJunk() {
        val raw = listOf(
            "\"I'll cook it according to your preference,\" she said.",
            "90% of his spiritual energy was gone.",
            "“All rights reserved,” the old man muttered.",
            "45%",
            "Next Chapter"
        ).joinToString("\n")
        val kept = ChapterCleaner.clean(raw).lines()
        assertEquals(3, kept.size)
        assertEquals("90% of his spiritual energy was gone.", kept[1])
    }
}
