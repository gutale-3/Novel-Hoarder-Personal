package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTitleCleanerTest {
    private fun c(raw: String, book: String, n: Int? = null, site: String = "") = ChapterTitleCleaner.clean(raw, book, site, n)

    @Test
    fun userReportedWtrLabTitle() {
        assertEquals("Chapter 4", c("The Wizard of the Steam Age Chapter 4 - WTR-LAB", "The Wizard of the Steam Age", 4))
    }

    @Test
    fun bookTitleBeforeOrAfter() {
        assertEquals("Chapter 1", c("ABCD Novel Chapter 1", "ABCD Novel", 1))
        assertEquals("Chapter 12: The Duel", c("Chapter 12: The Duel - Martial Peak", "Martial Peak", 12))
        assertEquals("Chapter 12: The Duel", c("Martial Peak - Chapter 12: The Duel | NovelBin", "Martial Peak", 12))
        assertEquals("Chapter 7", c("Shadow Slave: Chapter 7", "Shadow Slave", 7))
    }

    @Test
    fun keepsRealTitlesAndSeparators() {
        assertEquals("Chapter 4 - The Duel", c("Chapter 4 - The Duel", "Martial Peak", 4))
        assertEquals("Prologue", c("Prologue", "Martial Peak"))
    }

    @Test
    fun fillsInWhenNothingIsLeft() {
        assertEquals("Chapter 9", c("Martial Peak", "Martial Peak", 9))
        assertEquals("Chapter 15", c("15", "Martial Peak", 15))
    }

    @Test
    fun siteNameFromSourceAndReadOnlyBlurbs() {
        assertEquals("Chapter 2", c("Chapter 2 | Read Martial Peak Online Free", "Martial Peak", 2))
        assertEquals("Chapter 5", c("Chapter 5 - MyNovelSite", "X Y Z", 5, site = "MyNovelSite"))
    }
}
