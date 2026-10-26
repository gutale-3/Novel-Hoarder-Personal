package com.example.viewmodel

import com.example.data.local.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterStructureTest {
    private fun ch(id: String, n: Int, title: String, url: String) = ChapterEntity(
        id = id, bookId = "b", chapterId = id, chapterNumber = n, title = title, url = url, content = "t", hash = id
    )
    private fun toc(i: Int, title: String) = ChapterStructure.TocItem("https://s/c$i", title)

    @Test
    fun savedChaptersFollowTheSiteAndGapsAreFilled() {
        // Site: note, ch1, ch2, ch3, ch4. Saved: ch3 (wrongly at 1), ch1 (at 5), a private extra after ch3.
        val site = listOf(toc(0, "Author note"), toc(1, "Chapter 1: Start"), toc(2, "Chapter 2"), toc(3, "Chapter 3"), toc(4, "Chapter 4"))
        val local = listOf(
            ch("c3", 1, "Chapter 3", "https://s/c3?service=web"),
            ch("x", 2, "My extra page", "manual://b/x"),
            ch("c1", 5, "Chapter 85", "https://www.s/c1/")
        )
        val plan = ChapterStructure.plan(local, site, emptySet())
        // note(1) c1(2) ch2(3) c3(4) extra(5) ch4(6)
        assertEquals(mapOf("c1" to 2, "c3" to 4, "x" to 5), plan.numbers)
        assertEquals(listOf(1, 3, 6), plan.added.map { it.second })
        assertEquals("Chapter 1: Start", plan.titles["c1"])
        assertEquals(2, plan.matched)
        assertEquals(1, plan.extras)
    }

    @Test
    fun deletedChaptersAreNotReAdded() {
        val site = listOf(toc(1, "Chapter 1"), toc(2, "Chapter 2"))
        val plan = ChapterStructure.plan(emptyList(), site, setOf("s/c1"))
        assertEquals(listOf("Chapter 2"), plan.added.map { it.first.title })
    }

    @Test
    fun labels() {
        val c = ch("a", 85, "Chapter 79: The Duel", "u")
        assertEquals("85. The Duel", ChapterStructure.label(c, ChapterStructure.Numbering.SITE))
        assertEquals("Chapter 79: The Duel", ChapterStructure.label(c, ChapterStructure.Numbering.NOVEL))
    }
}
