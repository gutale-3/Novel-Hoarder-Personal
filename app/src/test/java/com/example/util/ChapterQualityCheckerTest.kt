package com.example.util

import com.example.data.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterQualityCheckerTest {
    private fun ch(n: Int, text: String, hash: String = "h$n") =
        ChapterEntity(id = "b_$n", bookId = "b", chapterId = "$n", chapterNumber = n, title = "C$n", url = "u$n", content = text, hash = hash)

    private val normal = "word ".repeat(1000) // 5000 chars

    @Test
    fun flagsErrorDuplicateTruncatedAndGaps() {
        val chapters = listOf(
            ch(1, normal), ch(2, normal + "a"), ch(3, "Please log in to continue. Login to read."),
            ch(4, normal, hash = "same"), ch(5, normal, hash = "same"),
            ch(6, "short ".repeat(100)), ch(8, normal + "b"), ch(9, "")
        )
        val r = ChapterQualityChecker.check(chapters)
        val byNumber = r.issues.associate { it.chapter.chapterNumber to it.problem }
        assertEquals(ChapterQualityChecker.Problem.ERROR_PAGE, byNumber[3])
        assertEquals(ChapterQualityChecker.Problem.DUPLICATE, byNumber[5])
        assertEquals(ChapterQualityChecker.Problem.TRUNCATED, byNumber[6])
        assertEquals(listOf(7), r.missingNumbers)
        assertTrue(9 !in byNumber) // not downloaded yet is not a problem
    }

    @Test
    fun shortChapterNovelIsNotTruncated() {
        val r = ChapterQualityChecker.check((1..10).map { ch(it, "x".repeat(900 + it)) })
        assertTrue(r.isClean)
    }
}

class ChapterQualityExtraTest {
    private fun ch(n: Int, title: String, content: String) = com.example.data.local.ChapterEntity(
        id = "c$n", bookId = "b", chapterId = "$n", chapterNumber = n, title = title, url = "u$n", content = content, hash = "h$n"
    )
    private fun para(i: Int) = "This is paragraph number $i of the story, long enough to count as prose."

    @org.junit.Test
    fun findsTwoChaptersInOne() {
        val body = (1..10).joinToString("\n") { para(it) } + "\nChapter 13: The Next Day\n" + (11..20).joinToString("\n") { para(it) }
        org.junit.Assert.assertEquals("Chapter 13: The Next Day", ChapterQualityChecker.secondChapterHeading(ch(12, "Chapter 12: Rain", body)))
    }

    @org.junit.Test
    fun findsTextCapturedTwice() {
        val half = (1..10).joinToString("\n") { para(it) }
        org.junit.Assert.assertNotNull(ChapterQualityChecker.repeatedShare("$half\n$half"))
        org.junit.Assert.assertNull(ChapterQualityChecker.repeatedShare(half))
    }
}
