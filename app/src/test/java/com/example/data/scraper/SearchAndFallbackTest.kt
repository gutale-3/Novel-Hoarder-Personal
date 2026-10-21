package com.example.data.scraper

import com.example.data.plugin.PluginConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchAndFallbackTest {

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PluginConfig::class.java)
    private fun plugin(id: String) = adapter.fromJson(File("src/main/assets/plugins/$id.json").readText())!!

    @Test
    fun titleMatchingToleratesPunctuationAndTags() {
        assertTrue(SourceFallback.titlesMatch("Martial Peak", "MARTIAL PEAK!"))
        assertTrue(SourceFallback.titlesMatch("Advent of the Three Calamities [Completed]", "Advent of the Three Calamities"))
        assertTrue(SourceFallback.titlesMatch("Shadow Slave", "Shadow Slave"))
        assertFalse(SourceFallback.titlesMatch("Martial Peak", "Martial World"))
        assertFalse(SourceFallback.titlesMatch("Peak", "Peak of the Heavens and the Endless Sea of Stars"))
    }

    @Test
    fun picksChapterByNumberThenPosition() {
        val toc = listOf("https://x.com/b/chapter-1", "https://x.com/b/chapter-2-the-fight", "https://x.com/b/chapter-4")
        assertEquals("https://x.com/b/chapter-4", SourceFallback.pickChapter(toc, 4))
        val plain = listOf("https://y.com/novel/kks1_1.html", "https://y.com/novel/kks1_2.html")
        assertEquals("https://y.com/novel/kks1_2.html", SourceFallback.pickChapter(plain, 2))
    }

    private fun assertSearch(id: String, fixture: String, base: String) {
        val cfg = plugin(id)
        assertTrue("$id has a search URL", cfg.searchUrlTemplate.contains("{query}") || cfg.searchPostBody.contains("{query}"))
        val doc = Jsoup.parse(javaClass.classLoader!!.getResourceAsStream("fixtures/$fixture.html"), "UTF-8", base)
        val links = doc.select(cfg.searchResultSelector)
        assertTrue("$id results", links.size >= 5)
        assertTrue("$id titles", links.first()!!.text().isNotBlank() || links.first()!!.attr("title").isNotBlank())
    }

    @Test fun novelBinSearch() = assertSearch("novelbin", "nb_search", "https://novelbin.me/")
    @Test fun novelFireSearch() = assertSearch("novelfire", "nf_search", "https://novelfire.net/")
    @Test fun readNovelFullSearch() = assertSearch("readnovelfull", "rnf_search", "https://readnovelfull.com/")
    @Test fun wuxiaBoxSearch() = assertSearch("wuxiabox", "wb_search", "https://www.wuxiabox.com/")
}
