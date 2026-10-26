package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinePatternTest {
    @Test
    fun numberedHeadingMatchesOtherChapters() {
        val p = LinePattern.forLine("#10Chapter 10 Borrowing Longevity from Heaven")
        assertTrue(LinePattern.matches(p, "#2Chapter 2: Being in a small position"))
        assertFalse(LinePattern.matches(p, "Chapter 2: Possessing the Fangcun Land"))
    }

    @Test
    fun plainLinesMatchExactly() {
        val p = LinePattern.forLine("Next »")
        assertTrue(LinePattern.matches(p, "  next  »"))
        assertFalse(LinePattern.matches(p, "Next, he drew his sword."))
    }

    @Test
    fun longParagraphsAreNeverMatched() {
        val p = LinePattern.forLine("Chapter 1: Start")
        assertTrue(LinePattern.matches(p, "Chapter 77: The End"))
        assertFalse(LinePattern.matches(p, "Chapter 7: " + "story ".repeat(60)))
    }
}
