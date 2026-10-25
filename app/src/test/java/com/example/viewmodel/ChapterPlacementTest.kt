package com.example.viewmodel

import com.example.data.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterPlacementTest {
    private fun ch(pos: Int, title: String, content: String = "text") = ChapterEntity(
        id = "b_$pos", bookId = "b", chapterId = "$pos", chapterNumber = pos, title = title,
        url = "https://s/$pos", content = content, hash = "h$pos"
    )

    // Four author notes early on: position = printed number + 4 from chapter 5 onwards.
    private val book = (1..4).map { ch(it, "Chapter $it") } +
        (5..8).map { ch(it, "Author's note ${it - 4}") } +
        (9..123).map { ch(it, "Chapter ${it - 4}") }

    @Test
    fun savingChapter84GoesNextToChapter83NotToTheEnd() {
        val withGap = book.filterNot { it.title == "Chapter 84" } // 84 not saved yet
        val plan = ChapterPlacement.place(withGap, null, 84, null)
        assertEquals(88, plan.position) // right after "Chapter 83" at position 87
        assertNull(plan.fill)
    }

    @Test
    fun resavingAnExistingChapterUpdatesIt() {
        val plan = ChapterPlacement.place(book, null, 84, null)
        assertEquals("Chapter 84", plan.fill?.title)
    }

    @Test
    fun unnumberedPageGoesAfterLastSaved() {
        assertEquals(41, ChapterPlacement.place(book, null, null, 40).position)
    }
}
