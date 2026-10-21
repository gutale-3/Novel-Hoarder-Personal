package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Simulates two devices: "phone" state is exported with snapshot(), then merged into a
 * "tablet" database that has a different, partly overlapping library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudSyncMergeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phoneDb: AppDatabase
    private lateinit var tabletDb: AppDatabase

    private fun book(id: String) = BookEntity(id, "https://site/$id", "Book $id", "A", "", null, null, null, 0)
    private fun ch(book: String, n: Int, text: String = "text $n", read: Boolean = false) = ChapterEntity(
        id = "${book}_$n", bookId = book, chapterId = "$n", chapterNumber = n, title = "Chapter $n",
        url = "https://site/$book/$n", content = text, hash = "h$n", isRead = read, readAt = if (read) 1000L * n else null
    )

    @Before
    fun setUp() {
        phoneDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tabletDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() { phoneDb.close(); tabletDb.close() }

    @Test
    fun phoneProgressAndNewNovelArriveOnTablet() = runBlocking {
        val phone = NovelRepository(phoneDb.bookDao())
        val tablet = NovelRepository(tabletDb.bookDao())
        val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

        // Phone: book "a" read up to chapter 3 paragraph 42, plus a novel the tablet lacks.
        phone.insertBookAndChapters(book("a"), listOf(ch("a", 1, read = true), ch("a", 2, read = true), ch("a", 3), ch("a", 4)))
        phone.insertBookAndChapters(book("b"), listOf(ch("b", 1)))
        prefs.edit().putString("progress_chapter_a", "a_3").putInt("progress_para_a_a_3", 42)
            .putLong("progress_at_a", 5_000L).commit()
        val exported = CloudSync(context, phone).snapshot()

        // Tablet: has "a" with only chapters 1-2 downloaded, nothing read, older position.
        tablet.insertBookAndChapters(book("a"), listOf(ch("a", 1), ch("a", 2)))
        prefs.edit().putLong("progress_at_a", 1_000L).commit()

        val summary = CloudSync(context, tablet).merge(exported)

        assertEquals(1, summary.booksAdded)
        assertEquals(2, summary.chaptersMarkedRead)
        val aChapters = tablet.getChapters("a").associateBy { it.chapterNumber }
        assertEquals(4, aChapters.size)
        assertEquals("", aChapters[3]!!.content) // arrives as a placeholder; downloads when opened
        assertEquals("text 1", aChapters[1]!!.content) // local text untouched
        assertEquals("a_3", tablet.getBook("a")!!.lastReadChapterId)
        assertEquals(42, prefs.getInt("progress_para_a_a_3", 0))
        assertTrue(tablet.getBook("b") != null)
    }

    @Test
    fun olderRemotePositionDoesNotOverwriteNewerLocalReading() = runBlocking {
        val phone = NovelRepository(phoneDb.bookDao())
        val tablet = NovelRepository(tabletDb.bookDao())
        val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
        phone.insertBookAndChapters(book("a"), listOf(ch("a", 1), ch("a", 2)))
        prefs.edit().putString("progress_chapter_a", "a_1").putLong("progress_at_a", 1_000L).commit()
        val exported = CloudSync(context, phone).snapshot()

        tablet.insertBookAndChapters(book("a").copy(lastReadChapterId = "a_2"), listOf(ch("a", 1), ch("a", 2)))
        prefs.edit().putString("progress_chapter_a", "a_2").putLong("progress_at_a", 9_000L).commit()

        CloudSync(context, tablet).merge(exported)
        assertEquals("a_2", tablet.getBook("a")!!.lastReadChapterId)
    }
}
