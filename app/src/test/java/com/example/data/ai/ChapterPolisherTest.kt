package com.example.data.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.NovelRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChapterPolisherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var polisher: ChapterPolisher

    @Before
    fun setUp() {
        context.getSharedPreferences("chapter_polish", Context.MODE_PRIVATE).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        polisher = ChapterPolisher(context, NovelRepository(db.bookDao()), GeminiProvider(context))
    }

    @Test
    fun replyWithWrongParagraphCountIsRejected() {
        val ok = """```json
            {"paragraphs": ["A.", "B."], "names": [{"name": "Lin Feng", "aliases": ["Lin Fan"]}]}
            ```"""
        val parsed = polisher.parseReply(ok, 2)!!
        assertEquals(listOf("A.", "B."), parsed.first)
        assertEquals("Lin Feng", parsed.second.single().name)
        assertNull(polisher.parseReply(ok, 3))
        assertNull(polisher.parseReply("Error: Gemini returned HTTP 429", 2))
    }

    @Test
    fun firstSpellingStaysCanonicalAndVariantsAccumulate() {
        polisher.mergeNames("b", listOf(ChapterPolisher.Name("Lin Feng", listOf("Lin Fan"))))
        polisher.mergeNames("b", listOf(ChapterPolisher.Name("Lin Fan", listOf("Lin Fen")), ChapterPolisher.Name("Elder Zhang", emptyList())))
        val names = polisher.names("b")
        assertEquals(listOf("Lin Feng", "Elder Zhang"), names.map { it.name })
        assertEquals(listOf("Lin Fan", "Lin Fen"), names.first().aliases)
        assertTrue(polisher.buildPrompt("b", listOf("x")).contains("Lin Feng (also rendered as: Lin Fan, Lin Fen)"))
    }
}
