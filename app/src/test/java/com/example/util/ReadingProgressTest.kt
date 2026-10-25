package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun startingMidwayCountsEarlierChaptersAsRead() {
        val p = ReadingProgress(latest = 1000, readUpTo = 950, downloaded = 100)
        assertEquals(50, p.left)
        assertEquals("Ch. 950 of 1000 · 50 left", p.label)
    }

    @Test
    fun labels() {
        assertTrue(ReadingProgress(1318, 1318, 10).caughtUp)
        assertEquals("Caught up · ch. 1318", ReadingProgress(1318, 1318, 10).label)
        assertEquals("Not started · 316 chapters", ReadingProgress(316, 0, 0).label)
        assertEquals("142 chapters", ReadingProgress(0, 0, 142).label)
    }
}
