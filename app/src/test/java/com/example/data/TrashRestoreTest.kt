package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrashRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun restoringANovelKeepsChaptersDeletedEarlier(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = NovelRepository(db.bookDao())
        repo.insertBookAndChapters(
            BookEntity("a", "https://s/a", "A", "", "", null, null, null, 0),
            (1..3).map { ChapterEntity(id = "a_$it", bookId = "a", chapterId = "$it", chapterNumber = it, title = "Ch $it", url = "https://s/a/$it", content = "t", hash = "h") }
        )
        repo.insertChapters(listOf(repo.getChapter("a_2")!!.copy(isDeleted = true)))
        repo.softDeleteBook("a")
        repo.restoreBook("a")
        assertEquals(listOf(1, 3), repo.getChapters("a").map { it.chapterNumber })
        db.close()
    }
}
