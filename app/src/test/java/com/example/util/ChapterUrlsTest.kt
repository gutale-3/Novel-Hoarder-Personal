package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChapterUrlsTest {
    @Test
    fun sameChapterDifferentAddressesMatch() {
        val a = ChapterUrls.normalize("https://wtr-lab.com/en/novel/123/master/chapter-1?service=web")
        assertEquals(a, ChapterUrls.normalize("http://www.WTR-LAB.com/en/novel/123/master/chapter-1/"))
        assertEquals(a, ChapterUrls.normalize("https://wtr-lab.com/en/novel/123/master/chapter-1#top"))
        assertNotEquals(a, ChapterUrls.normalize("https://wtr-lab.com/en/novel/123/master/chapter-2"))
        // Path case is kept (some sites are case-sensitive).
        assertNotEquals(ChapterUrls.normalize("https://x.com/A"), ChapterUrls.normalize("https://x.com/a"))
    }
}
