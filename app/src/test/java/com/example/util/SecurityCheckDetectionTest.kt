package com.example.util

import com.example.data.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCheckDetectionTest {

    /** Exactly what WTR Lab served instead of chapter 4 (user report). */
    private val wtrLabCheck = """
        Security Check Required

        Please complete the security check

        We've detected unusual reading activity from your IP address. Please complete the challenge below to continue reading.

        Having trouble with the challenge?

        Open Challenge in New Page
    """.trimIndent()

    @Test
    fun wtrLabCheckPageIsRecognised() {
        assertTrue(CloudflareDetector.isChallengeText(wtrLabCheck))
    }

    @Test
    fun savingItIsRefused() {
        try {
            com.example.viewmodel.cleanScrapedBody(GenericScraper, wtrLabCheck, aggressive = false)
            throw AssertionError("expected CloudflareException")
        } catch (e: CloudflareException) {
            // the download loop turns this into the solve-it dialog
        }
    }

    @Test
    fun alreadySavedCheckPagesAreFlaggedByQualityCheck() {
        val ch = ChapterEntity(id = "b_4", bookId = "b", chapterId = "4", chapterNumber = 4, title = "Chapter 4",
            url = "u", content = wtrLabCheck, hash = "h")
        val report = ChapterQualityChecker.check(listOf(ch))
        assertEquals(ChapterQualityChecker.Problem.ERROR_PAGE, report.issues.single().problem)
    }

    @Test
    fun realChapterMentioningARobotIsKept() {
        val story = "\"Are you a robot?\" she asked, and he laughed for a long while. ".repeat(80)
        assertFalse(CloudflareDetector.isChallengeText(story))
    }
}
