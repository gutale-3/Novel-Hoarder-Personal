package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTextCleanerTest {
    @Test
    fun dropsRepeatedHeadingsFromScreenshot() {
        val text = "Chapter 81: Confucian saints words slip\n\nChapter 84: Confucian saints words slip\n\n\"This—\" Fang Xun said with some hesitation."
        assertEquals("\"This—\" Fang Xun said with some hesitation.", ChapterTextCleaner.stripLeadingHeadings(text))
    }

    @Test
    fun keepsStoryAndChineseHeadingsGo() {
        assertEquals("他笑了。", ChapterTextCleaner.stripLeadingHeadings("第八十四章 儒圣言\n他笑了。"))
        val story = "Chapter one of his life had ended long ago, he thought.\nMore text."
        assertEquals(story, ChapterTextCleaner.stripLeadingHeadings(story))
        val prose = "Chapter 12 was the part of the old book that everyone in the village remembered best.\nMore."
        assertEquals(prose, ChapterTextCleaner.stripLeadingHeadings(prose))
        val dialogue = "\"Chapter 3 is where it gets good,\" she said.\nMore."
        assertEquals(dialogue, ChapterTextCleaner.stripLeadingHeadings(dialogue))
        assertEquals("Chapter 5", ChapterTextCleaner.stripLeadingHeadings("Chapter 5"))
    }
}
