package com.example.util

import org.junit.Assert.*
import org.junit.Test

class SampleNovelsTest {

    @Test
    fun testSampleNovelsDefinitions() {
        val novels = SampleNovels.DEFINITIONS
        // User requested 4 or 5 sample novels
        assertEquals(5, novels.size)

        // User requested 500 chapter plus novels
        novels.forEach { novel ->
            assertTrue(
                "Novel ${novel.title} must have at least 500 chapters, has ${novel.totalChapters}",
                novel.totalChapters >= 500
            )
            assertTrue("Novel ${novel.title} must have volumes", novel.volumeCount >= 4)
            assertTrue("Novel ${novel.title} synopsis should be descriptive", novel.synopsis.isNotBlank())
        }

        val totalChaptersAllNovels = novels.sumOf { it.totalChapters }
        assertTrue("Total chapters across sample novels should be > 2500", totalChaptersAllNovels > 2500)
    }

    @Test
    fun testSubdivideSampleNovelIntoBatches() {
        // Test 520 chapter novel divided into batches of 50
        val dummyChapters = (1..520).map { num ->
            val vol = ((num - 1) / 104) + 1
            val chapInVol = ((num - 1) % 104) + 1
            com.example.data.local.ChapterEntity(
                id = "sample_cultivator_ch_$num",
                bookId = "sample_cultivator",
                chapterId = "ch_$num",
                chapterNumber = num,
                title = "Volume $vol Chapter $chapInVol: Chapter $num",
                url = "sample://ch/$num",
                content = "Content $num",
                hash = "hash_$num"
            )
        }

        // Subdivide with BATCH_CHAPTERS (batchSize = 50)
        val batches = ChapterSubdivisionHelper.subdivideChapters(
            chapters = dummyChapters,
            mode = TocSubdivisionMode.BATCH_CHAPTERS,
            batchSize = 50
        )

        // 520 / 50 = 10 batches of 50 + 1 batch of 20 = 11 batches
        assertEquals(11, batches.size)
        assertEquals("Chapters 1 – 50", batches.first().title)
        assertEquals(50, batches.first().chapters.size)
        assertEquals("Chapters 501 – 520", batches.last().title)
        assertEquals(20, batches.last().chapters.size)

        // Subdivide with VOLUMES (Volume keyword detection)
        val volumes = ChapterSubdivisionHelper.subdivideChapters(
            chapters = dummyChapters,
            mode = TocSubdivisionMode.VOLUMES
        )

        assertEquals(5, volumes.size)
        assertEquals("Volume 1", volumes[0].title)
        assertEquals(104, volumes[0].chapters.size)
        assertEquals("Volume 5", volumes[4].title)
        assertEquals(104, volumes[4].chapters.size)
    }

    @Test
    fun testSafeWebViewClientRendererProcessGone() {
        val client = WebViewFactory.SafeWebViewClient()
        // onRenderProcessGone must return true to prevent host app process termination
        val handled = client.onRenderProcessGone(null, null)
        assertTrue("onRenderProcessGone must return true to prevent host app crash", handled)
    }
}
