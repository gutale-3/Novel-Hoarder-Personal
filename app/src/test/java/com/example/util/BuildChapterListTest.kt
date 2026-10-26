package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildChapterListTest {
    private fun e(i: Int, t: String) = TocEntry("https://s/c/$i", t)

    @Test
    fun authorNotesKeepTheirPlace() {
        val entries = listOf(e(10, "Chapter 1"), e(11, "Author's note"), e(12, "Chapter 2"), e(13, "Chapter 3"), e(14, "Chapter 4"))
        assertEquals(listOf(10, 11, 12, 13, 14).map { "https://s/c/$it" }, GenericScraper.buildChapterList(entries))
    }

    @Test
    fun newestFirstListsAreReversed() {
        val entries = listOf(e(4, "Chapter 4"), e(3, "Chapter 3"), e(99, "Notice"), e(2, "Chapter 2"), e(1, "Chapter 1"))
        assertEquals(listOf(1, 2, 99, 3, 4).map { "https://s/c/$it" }, GenericScraper.buildChapterList(entries))
    }
}
