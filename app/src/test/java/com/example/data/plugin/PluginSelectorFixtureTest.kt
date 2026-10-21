package com.example.data.plugin

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs each built-in plugin's selectors against saved copies of the real site's pages, so a
 * selector typo or a site redesign shows up as a failing test instead of a silent empty chapter.
 * To refresh after a redesign, re-save the page into src/test/resources/fixtures.
 */
class PluginSelectorFixtureTest {

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(PluginConfig::class.java)

    private val pluginsDir = File("src/main/assets/plugins")

    private fun plugin(id: String): PluginConfig =
        adapter.fromJson(File(pluginsDir, "$id.json").readText())!!

    private fun fixture(name: String, baseUri: String): Document {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name.html")
            ?: error("missing fixture $name")
        return Jsoup.parse(stream, "UTF-8", baseUri)
    }

    private fun assertSite(id: String, prefix: String, base: String, expectedTitle: String) {
        val cfg = plugin(id)

        val book = fixture("${prefix}_book", base)
        assertEquals(expectedTitle, book.select(cfg.titleSelector).first()?.text())
        assertTrue("$id synopsis", book.select(cfg.synopsisSelector).text().length > 20)
        assertTrue("$id cover", book.select(cfg.coverSelector).isNotEmpty())

        val toc = fixture("${prefix}_toc", base)
        val links = toc.select(cfg.chapterListSelector)
        assertTrue("$id chapter links", links.size >= 3)
        assertTrue("$id first link", links.first()!!.absUrl("href").contains("chapter"))

        val chapter = fixture("${prefix}_ch", base)
        val body = chapter.select(cfg.chapterBodySelector).first()
        assertTrue("$id body", (body?.text()?.length ?: 0) > 200)
        if (cfg.chapterReadySelector.isNotBlank()) {
            assertTrue("$id ready selector", chapter.select(cfg.chapterReadySelector).isNotEmpty())
        }
        cfg.junkSelectors.forEach { sel -> chapter.select(sel) } // throws on an invalid selector
    }

    @Test
    fun novelBin() = assertSite("novelbin", "nb", "https://novelbin.me/", "A Blossom, A Cup of Wine, An Immortal Life")

    @Test
    fun novelFire() {
        assertSite("novelfire", "nf", "https://novelfire.net/", "Advent of the Three Calamities")
        val toc = fixture("nf_toc", "https://novelfire.net/")
        assertTrue(toc.select(plugin("novelfire").tocLastPageSelector).any { it.attr("href").contains("page=9") })
    }

    @Test
    fun readNovelFull() = assertSite("readnovelfull", "rnf", "https://readnovelfull.com/", "Advent of the Three Calamities")

    @Test
    fun novelIdAttributeIsPresentForArchiveTemplates() {
        assertEquals("a-blossom-a-cup-of-wine-an-immortal-life",
            fixture("nb_book", "https://novelbin.me/").select("[data-novel-id]").first()?.attr("data-novel-id"))
        assertEquals("2454",
            fixture("rnf_book", "https://readnovelfull.com/").select("[data-novel-id]").first()?.attr("data-novel-id"))
    }

    /** The remote updater only fetches what index.json lists, so it must match the shipped files. */
    @Test
    fun remoteIndexMatchesBundledPlugins() {
        val index = File(pluginsDir, PluginManager.INDEX_FILE).readText()
        val listed = Regex("\"id\":\\s*\"([^\"]+)\",\\s*\"version\":\\s*\"([^\"]+)\"")
            .findAll(index).associate { it.groupValues[1] to it.groupValues[2] }
        val shipped = pluginsDir.listFiles { _, n -> n.endsWith(".json") && n != PluginManager.INDEX_FILE }!!
            .map { plugin(it.nameWithoutExtension) }
            .associate { it.id to it.version }
        assertEquals(shipped, listed)
    }

    @Test
    fun ixdzsRawChinese() {
        val cfg = plugin("ixdzs")
        assertEquals("zh", cfg.language)
        val book = fixture("ix_book", "https://ixdzs.tw/")
        assertEquals("武道大帝", book.select(cfg.titleSelector).first()?.text())
        val links = book.select(cfg.chapterListSelector)
        // Oldest first, and the "latest chapters" box at the top is not included.
        assertTrue(links.first()!!.attr("href").endsWith("/p1.html"))
        val chapter = fixture("ix_ch", "https://ixdzs.tw/")
        val body = chapter.select(cfg.chapterBodySelector).first()!!.text()
        assertTrue(body.startsWith("天武國"))
        val search = fixture("ix_search", "https://ixdzs.tw/")
        assertTrue(search.select(cfg.searchResultSelector).size >= 5)
    }
}
