package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavJunkTest {
    @Test
    fun removesSiteFurnitureFromTheScreenshot() {
        val raw = listOf(
            "x",
            "#2Chapter 2: Being in a small position",
            "« PrevTable of Contents",
            "Next »",
            "Chapter 2: Possessing the Fangcun Land",
            "\"I'm back...\"",
            "The excruciating pain of raging fire consumed him."
        ).joinToString("\n")
        assertEquals("\"I'm back...\"\nThe excruciating pain of raging fire consumed him.", ChapterTextCleaner.removeJunk(raw))
    }

    @Test
    fun storyLinesStay() {
        assertFalse(ChapterTextCleaner.isNavLine("Next, he drew his sword."))
        assertFalse(ChapterTextCleaner.isNavLine("The index finger trembled."))
        assertFalse(ChapterTextCleaner.isNavLine("..."))
    }
}
