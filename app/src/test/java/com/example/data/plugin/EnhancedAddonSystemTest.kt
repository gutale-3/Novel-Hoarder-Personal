package com.example.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancedAddonSystemTest {

    private lateinit var context: Context
    private lateinit var pluginManager: PluginManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val pluginDir = File(context.filesDir, "plugins")
        pluginDir.deleteRecursively()
        pluginDir.mkdirs()
        pluginManager = PluginManager(context)
    }

    @Test
    fun testAllBundledAddonsLoaded() {
        val plugins = pluginManager.plugins.value
        val royalroad = plugins.find { it.id == "royalroad" }
        val novelfull = plugins.find { it.id == "novelfull" }
        val ranobes = plugins.find { it.id == "ranobes" }
        val scribblehub = plugins.find { it.id == "scribblehub" }
        val freewebnovel = plugins.find { it.id == "freewebnovel" }

        assertNotNull("Royal Road add-on should be bundled", royalroad)
        assertEquals("Royal Road", royalroad?.name)
        assertTrue(royalroad?.junkSelectors?.contains(".author-note-portlet") == true)

        assertNotNull("NovelFull add-on should be bundled", novelfull)
        assertEquals("NovelFull", novelfull?.name)
        assertEquals("query", novelfull?.tocPaginationMode)

        assertNotNull("Ranobes add-on should be bundled", ranobes)
        assertEquals("Ranobes", ranobes?.name)

        assertNotNull("Scribble Hub add-on should be bundled", scribblehub)
        assertEquals("Scribble Hub", scribblehub?.name)

        assertNotNull("FreeWebNovel add-on should be bundled", freewebnovel)
        assertEquals("FreeWebNovel", freewebnovel?.name)
    }

    @Test
    fun testDomainMatchingForNewAddons() {
        val rr = pluginManager.findPluginForUrl("https://royalroad.com/fiction/12345/super-minion")
        assertNotNull("Should match royalroad.com", rr)
        assertEquals("royalroad", rr?.id)

        val rrWww = pluginManager.findPluginForUrl("https://www.royalroad.com/fiction/12345/super-minion")
        assertNotNull("Should match www.royalroad.com", rrWww)
        assertEquals("royalroad", rrWww?.id)

        val nfNet = pluginManager.findPluginForUrl("https://novelfull.net/reverend-insanity/chapter-1.html")
        assertNotNull("Should match novelfull.net via extraHosts", nfNet)
        assertEquals("novelfull", nfNet?.id)

        val ranobes = pluginManager.findPluginForUrl("https://ranobes.top/novels/123-novel.html")
        assertNotNull("Should match ranobes.top", ranobes)
        assertEquals("ranobes", ranobes?.id)

        val sh = pluginManager.findPluginForUrl("https://www.scribblehub.com/series/123/story/")
        assertNotNull("Should match scribblehub.com", sh)
        assertEquals("scribblehub", sh?.id)
    }

    @Test
    fun testDuplicatePlugin() = runBlocking {
        val dupResult = pluginManager.duplicatePlugin("royalroad", "My Royal Road Mod")
        assertTrue(dupResult.isSuccess)
        val copy = dupResult.getOrNull()
        assertNotNull(copy)
        assertEquals("royalroad_copy", copy?.id)
        assertEquals("My Royal Road Mod", copy?.name)
        assertFalse(copy?.isBuiltIn ?: true)

        // Ensure it appears in plugins list
        val currentPlugins = pluginManager.plugins.value
        val foundCopy = currentPlugins.find { it.id == "royalroad_copy" }
        assertNotNull(foundCopy)
        assertEquals("My Royal Road Mod", foundCopy?.name)
    }

    @Test
    fun testCreateDefaultPluginTemplates() {
        val standard = pluginManager.createDefaultPlugin("standard", "https://lightnovelworld.co")
        assertEquals("lightnovelworld_co", standard.id)
        assertEquals("none", standard.tocPaginationMode)
        assertTrue(standard.chapterListSelector.isNotBlank())

        val paginated = pluginManager.createDefaultPlugin("paginated", "https://readnovelfull.me")
        assertEquals("readnovelfull_me", paginated.id)
        assertEquals("query", paginated.tocPaginationMode)
        assertEquals("page", paginated.tocPageParam)

        val spa = pluginManager.createDefaultPlugin("spa", "https://spanovel.app")
        assertEquals("spanovel_app", spa.id)
        assertEquals(2500, spa.requestDelayMs)
    }

    @Test
    fun testExportPluginJsonRoundTrip() {
        val original = PluginConfig(
            id = "test_custom_site",
            name = "Test Custom Site",
            version = "2.0.0",
            baseUrl = "customnovel.org",
            author = "Hoarder Dev",
            description = "Custom test scraper with ad filtering",
            titleSelector = "h1.book-title",
            authorSelector = ".author-box a",
            synopsisSelector = ".summary-content",
            coverSelector = "img.poster",
            chapterListSelector = "ul.chapters a",
            chapterTitleSelector = "h2.chap-title",
            chapterBodySelector = "div.content-body",
            junkSelectors = listOf(".ad-banner", ".watermark", ".tip-box"),
            reverseOrder = true,
            chapterLinkFilterRegex = ".*\\/chapter-\\d+.*",
            chapterLinkExcludeRegex = ".*\\/comments.*",
            customUserAgent = "NovelHoarder/2.0 Custom Crawler"
        )

        val json = pluginManager.exportPluginJson(original)
        assertTrue(json.contains("\"test_custom_site\""))
        assertTrue(json.contains("\"junkSelectors\""))
        assertTrue(json.contains("\"reverseOrder\": true"))

        val parsed = pluginManager.validatePluginJson(json)
        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.junkSelectors, parsed.junkSelectors)
        assertEquals(true, parsed.reverseOrder)
        assertEquals(original.chapterLinkFilterRegex, parsed.chapterLinkFilterRegex)
        assertEquals(original.chapterLinkExcludeRegex, parsed.chapterLinkExcludeRegex)
        assertEquals(original.customUserAgent, parsed.customUserAgent)
    }
}
