package com.example.util

import com.example.data.local.ChapterEntity
import org.junit.Assert.*
import org.junit.Test

class ChapterSubdivisionHelperTest {

    private fun createChapter(id: String, chNum: Int, title: String): ChapterEntity {
        return ChapterEntity(
            id = id,
            bookId = "book_1",
            chapterId = id,
            chapterNumber = chNum,
            title = title,
            url = "local://test/$id",
            content = "Sample content for chapter $chNum",
            hash = "hash_$id"
        )
    }

    @Test
    fun testSubdivideAsIs() {
        val chapters = (1..50).map { createChapter("ch_$it", it, "Chapter $it") }
        val groups = ChapterSubdivisionHelper.subdivideChapters(chapters, TocSubdivisionMode.AS_IS)

        assertEquals(1, groups.size)
        assertEquals("All Chapters", groups.first().title)
        assertEquals(50, groups.first().chapters.size)
    }

    @Test
    fun testSubdivideBatchChapters_970Chapters() {
        // User example: 1 to 50, then 51 to 100, until we finish. If last is 970 -> 951 until 970.
        val chapters = (1..970).map { createChapter("ch_$it", it, "Chapter $it") }
        val groups = ChapterSubdivisionHelper.subdivideChapters(
            chapters = chapters,
            mode = TocSubdivisionMode.BATCH_CHAPTERS,
            batchSize = 50
        )

        // 970 / 50 = 19 full batches of 50 + 1 batch of 20 = 20 batches
        assertEquals(20, groups.size)

        // First batch: 1 to 50
        assertEquals("Chapters 1 – 50", groups[0].title)
        assertEquals(50, groups[0].chapters.size)
        assertEquals(1, groups[0].chapters.first().chapterNumber)
        assertEquals(50, groups[0].chapters.last().chapterNumber)

        // Second batch: 51 to 100
        assertEquals("Chapters 51 – 100", groups[1].title)
        assertEquals(50, groups[1].chapters.size)
        assertEquals(51, groups[1].chapters.first().chapterNumber)
        assertEquals(100, groups[1].chapters.last().chapterNumber)

        // 19th batch: 901 to 950
        assertEquals("Chapters 901 – 950", groups[18].title)
        assertEquals(50, groups[18].chapters.size)

        // Final batch: 951 to 970
        val lastBatch = groups.last()
        assertEquals("Chapters 951 – 970", lastBatch.title)
        assertEquals(20, lastBatch.chapters.size)
        assertEquals(951, lastBatch.chapters.first().chapterNumber)
        assertEquals(970, lastBatch.chapters.last().chapterNumber)
    }

    @Test
    fun testSubdivideVolumes_NumberingRestart() {
        // User example: Volume 1 is chapter 1 to 30 or 40. For volume 2, you start from chapter 1 until end.
        val vol1Chapters = (1..35).map { createChapter("vol1_ch_$it", it, "Chapter $it") }
        val vol2Chapters = (1..40).map { createChapter("vol2_ch_$it", it, "Chapter $it") }
        val allChapters = vol1Chapters + vol2Chapters

        val groups = ChapterSubdivisionHelper.subdivideChapters(
            chapters = allChapters,
            mode = TocSubdivisionMode.VOLUMES
        )

        assertEquals(2, groups.size)
        assertEquals("Volume 1", groups[0].title)
        assertEquals(35, groups[0].chapters.size)

        assertEquals("Volume 2", groups[1].title)
        assertEquals(40, groups[1].chapters.size)
    }

    @Test
    fun testSubdivideVolumes_TitleKeyword() {
        val chapters = listOf(
            createChapter("ch_1", 1, "Volume 1 Chapter 1: The Beginning"),
            createChapter("ch_2", 2, "Volume 1 Chapter 2: The Journey"),
            createChapter("ch_3", 3, "Volume 2 Chapter 1: The City"),
            createChapter("ch_4", 4, "Volume 2 Chapter 2: The Duel"),
            createChapter("ch_5", 5, "Volume 3 Chapter 1: Epilogue")
        )

        val groups = ChapterSubdivisionHelper.subdivideChapters(
            chapters = chapters,
            mode = TocSubdivisionMode.VOLUMES
        )

        assertEquals(3, groups.size)
        assertTrue(groups[0].title.contains("Volume 1"))
        assertEquals(2, groups[0].chapters.size)

        assertTrue(groups[1].title.contains("Volume 2"))
        assertEquals(2, groups[1].chapters.size)

        assertTrue(groups[2].title.contains("Volume 3"))
        assertEquals(1, groups[2].chapters.size)
    }

    @Test
    fun testSubdivideVolumes_CustomSplits() {
        val chapters = (1..100).map { createChapter("ch_$it", it, "Chapter $it") }
        val customSplits = listOf(1, 31, 71)

        val groups = ChapterSubdivisionHelper.subdivideChapters(
            chapters = chapters,
            mode = TocSubdivisionMode.VOLUMES,
            customVolumeSplits = customSplits
        )

        assertEquals(3, groups.size)
        assertEquals("Volume 1", groups[0].title)
        assertEquals(30, groups[0].chapters.size) // 1 to 30

        assertEquals("Volume 2", groups[1].title)
        assertEquals(40, groups[1].chapters.size) // 31 to 70

        assertEquals("Volume 3", groups[2].title)
        assertEquals(30, groups[2].chapters.size) // 71 to 100
    }

    @Test
    fun testFindGroupIdForActiveChapter() {
        val chapters = (1..100).map { createChapter("ch_$it", it, "Chapter $it") }
        val groups = ChapterSubdivisionHelper.subdivideChapters(
            chapters = chapters,
            mode = TocSubdivisionMode.BATCH_CHAPTERS,
            batchSize = 50
        )

        val groupIdForCh15 = ChapterSubdivisionHelper.findGroupIdForChapter(groups, "ch_15")
        assertNotNull(groupIdForCh15)
        assertEquals(groups[0].id, groupIdForCh15)

        val groupIdForCh75 = ChapterSubdivisionHelper.findGroupIdForChapter(groups, "ch_75")
        assertNotNull(groupIdForCh75)
        assertEquals(groups[1].id, groupIdForCh75)
    }
}
