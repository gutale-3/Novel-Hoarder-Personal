package com.example.util

import android.content.Context
import android.net.Uri
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
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase

    private fun book(id: String) = BookEntity(id, "https://site/$id", "Book $id", "A", "", null, null, null, 0)
    private fun ch(book: String, n: Int) = ChapterEntity(
        id = "${book}_$n", bookId = book, chapterId = "$n", chapterNumber = n, title = "Chapter $n",
        url = "https://site/$book/$n", content = "Line one of $n\nline \"two\"", hash = "h$n",
        isRead = n == 1, readAt = if (n == 1) 1234L else null
    )

    @Before
    fun setUp() {
        sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { sourceDb.close(); targetDb.close() }

    @Test
    fun streamedBackupRestoresEveryChapter(): Unit = runBlocking {
        val source = NovelRepository(sourceDb.bookDao())
        source.insertBookAndChapters(book("a"), (1..650).map { ch("a", it) })
        source.insertBookAndChapters(book("b"), (1..3).map { ch("b", it) })

        val file = BackupRestoreManager.createLocalAutoBackup(context, source).getOrThrow()
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("backup_metadata.json"))
            assertEquals(2, names.count { it.startsWith("chapters/") })
        }

        val target = NovelRepository(targetDb.bookDao())
        val restored = BackupRestoreManager.restoreFullBackupZip(context, target, Uri.fromFile(file)).getOrThrow()
        assertEquals(2, restored)
        val chaptersA = target.getChapters("a")
        assertEquals(650, chaptersA.size)
        assertEquals(3, target.getChapters("b").size)
        val first = chaptersA.first { it.chapterNumber == 1 }
        assertEquals("Line one of 1\nline \"two\"", first.content)
        assertTrue(first.isRead)
        assertEquals(1234L, first.readAt)
        File(context.filesDir, "backups").deleteRecursively()
    }

    @Test
    fun restoringOverTheLibraryKeepsNewerAndHiddenChapters(): Unit = runBlocking {
        val repo = NovelRepository(sourceDb.bookDao())
        repo.insertBookAndChapters(book("a"), (1..3).map { ch("a", it) }.toMutableList().also {
            it[2] = it[2].copy(isArchived = true) // hidden chapter 3
        } )
        val file = BackupRestoreManager.createLocalAutoBackup(context, repo).getOrThrow()
        repo.insertChapters(listOf(ch("a", 4))) // downloaded after the backup

        BackupRestoreManager.restoreFullBackupZip(context, repo, Uri.fromFile(file)).getOrThrow()
        BackupRestoreManager.restoreFullBackupZip(context, repo, Uri.fromFile(file)).getOrThrow()
        val all = repo.getAllChaptersIncludingHidden("a")
        assertEquals(listOf(1, 2, 3, 4), all.map { it.chapterNumber })
        assertTrue(all.first { it.chapterNumber == 3 }.isArchived)
        File(context.filesDir, "backups").deleteRecursively()
    }
}
