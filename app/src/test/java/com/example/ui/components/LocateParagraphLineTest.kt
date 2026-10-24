package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LocateParagraphLineTest {
    @Test
    fun repeatedSeparatorPicksTheTappedCopy() {
        val lines = listOf("Intro.", "***", "", "Middle text.", "***", "End text.")
        // Visible paragraphs: 0 Intro, 1 ***, 2 Middle, 3 ***, 4 End
        assertEquals(4, locateParagraphLine(lines, "***", 3))
        assertEquals(1, locateParagraphLine(lines, "***", 1))
        assertEquals(-1, locateParagraphLine(lines, "not there", 2))
    }
}
