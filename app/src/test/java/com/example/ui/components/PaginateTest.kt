package com.example.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PaginateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyWordSurvivesPaginationAndPagesAreUsed() {
        val paragraphs = (1..40).map { i -> "Paragraph $i " + "word$i ".repeat(25 + i % 7).trim() }
        var pages: List<ReaderPage> = emptyList()
        compose.setContent {
            val m = rememberTextMeasurer()
            pages = paginate(paragraphs, m, TextStyle(fontSize = 18.sp, lineHeight = 26.sp), 600, 300)
        }
        compose.waitForIdle()
        assertTrue("expected several pages, got ${pages.size}", pages.size > 3)
        val squash = { s: String -> s.replace(Regex("\\s+"), "") }
        assertEquals(squash(paragraphs.joinToString("")), squash(pages.joinToString("") { it.text }))
        // Page start paragraphs never go backwards.
        assertEquals(pages.map { it.firstParagraph }.sorted(), pages.map { it.firstParagraph })
    }
}
