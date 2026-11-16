package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PasswordSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun samePasswordSameMailboxAndRoundTrip() {
        val a = SyncCrypto.fromPassword("correct horse battery")
        val b = SyncCrypto.fromPassword("  correct horse battery ")
        assertEquals(a.space, b.space)
        assertEquals("hello", b.decrypt(a.encrypt("hello")))
        assertNotEquals(a.space, SyncCrypto.fromPassword("another password").space)
    }

    /** Needs the local sync service: node sync-server/local-server.mjs 8787 */
    @Test
    fun twoPhonesSyncThroughTheService(): Unit = runBlocking {
        val url = System.getenv("SYNC_TEST_URL")
        assumeTrue(url != null)
        fun book(id: String) = BookEntity(id, "https://site/$id", "Book $id", "A", "", null, null, null, 0)
        fun ch(b: String, n: Int, read: Boolean = false) = ChapterEntity(
            id = "${b}_$n", bookId = b, chapterId = "$n", chapterNumber = n, title = "Chapter $n",
            url = "https://site/$b/$n", content = "text", hash = "h$n", isRead = read, readAt = if (read) 1L else null
        )
        val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
        val syncPrefs = context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
        prefs.edit().clear().commit(); syncPrefs.edit().clear().commit()

        // Phone A: reads book "a" to chapter 3, paragraph 17.
        val dbA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repoA = NovelRepository(dbA.bookDao())
        // 120 chapters with text (several bundles), one of them hidden.
        repoA.insertBookAndChapters(book("a"), listOf(ch("a", 1, true), ch("a", 2, true), ch("a", 3)) +
            (4..120).map { ch("a", it).copy(content = "Text of chapter $it", hash = "x$it", isArchived = it == 7) })
        prefs.edit().putString("progress_chapter_a", "a_3").putInt("progress_para_a_a_3", 17).putLong("progress_at_a", 9_000L).commit()
        val phoneA = CloudSync(context, repoA).apply { setServiceUrl(url!!) }
        phoneA.setPassword("my sync password").getOrThrow()

        // Phone B: empty, same password (fresh device state).
        prefs.edit().clear().commit(); syncPrefs.edit().clear().commit()
        val dbB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repoB = NovelRepository(dbB.bookDao())
        val phoneB = CloudSync(context, repoB).apply { setServiceUrl(url!!) }
        val summary = phoneB.setPassword("my sync password").getOrThrow()

        assertEquals(1, summary.booksAdded)
        assertEquals(120, summary.chaptersFilled)
        assertEquals((1..120).filter { it != 7 }, repoB.getChapters("a").map { it.chapterNumber })
        assertEquals("Text of chapter 99", repoB.getChapter("a_99")?.content)
        assertEquals(true, repoB.getChapter("a_7")?.isArchived)
        assertEquals("Text of chapter 7", repoB.getChapter("a_7")?.content)
        assertEquals(2, repoB.getChapters("a").count { it.isRead })
        assertEquals("a_3", prefs.getString("progress_chapter_a", null))
        assertEquals(17, prefs.getInt("progress_para_a_a_3", 0))
        dbA.close(); dbB.close()
    }
}
