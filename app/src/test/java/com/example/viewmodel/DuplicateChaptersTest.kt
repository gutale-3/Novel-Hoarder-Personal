package com.example.viewmodel

import com.example.data.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateChaptersTest {
    private val story = (1..6).joinToString("\n") { "This is a fairly long paragraph number $it of the actual story text." }
    private fun ch(id: String, n: Int, title: String, content: String, url: String, read: Boolean = false, at: Long = 0) =
        ChapterEntity(id = id, bookId = "b", chapterId = id, chapterNumber = n, title = title, url = url,
            content = content, hash = id, isRead = read, downloadedAt = at)

    @Test
    fun keepsTheReadCopyAndFindsRawRedownload() {
        val chapters = listOf(
            ch("a", 1, "Chapter 1", story, "https://s/c1", read = true, at = 1),
            ch("b", 2, "Chapter 1", "x\n#1Chapter 1 heading\nNext »\n$story", "https://s/c1?service=web", at = 5),
            ch("c", 3, "Chapter 2", story.replace("story", "tale"), "https://s/c2"),
            // Volume 2 restarting at chapter 1, different text: not a duplicate
            ch("d", 4, "Chapter 1", "Completely different volume two opening paragraph that is long enough.", "https://s/v2c1")
        )
        val groups = DuplicateChapters.find(chapters)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups[0].map { it.id })
    }
}
