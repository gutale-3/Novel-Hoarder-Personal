package com.example.data.plugin

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class PluginConfigDefaultsTest {

    @Test
    fun testMinimalJsonParsesWithCorrectDefaults() {
        val json = """
            {
              "id": "minimal",
              "name": "Minimal Site",
              "baseUrl": "minimal.com"
            }
        """.trimIndent()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(PluginConfig::class.java)

        val config = adapter.fromJson(json)
        assertNotNull(config)
        assertEquals("minimal", config?.id)
        assertEquals("Minimal Site", config?.name)
        assertEquals("minimal.com", config?.baseUrl)

        // Verify defaults for other fields
        assertEquals("1.0.0", config?.version)
        assertEquals("User", config?.author)
        assertEquals("", config?.description)
        assertEquals("h1", config?.titleSelector)
        assertEquals("", config?.authorSelector)
        assertEquals(".synopsis, .description, meta[name='description']", config?.synopsisSelector)
        assertEquals("img.cover, meta[property='og:image']", config?.coverSelector)
        assertEquals("a[href*='chapter'], a[href*='ch-']", config?.chapterListSelector)
        assertEquals("h1, .chapter-title", config?.chapterTitleSelector)
        assertEquals("article, main, .chapter-content, #content, .entry-content", config?.chapterBodySelector)
        assertNull(config?.customJs)
        assertTrue(config?.isEnabled ?: false)

        // Verify new defaults
        assertTrue(config?.extraHosts?.isEmpty() ?: false)
        assertEquals("none", config?.tocPaginationMode)
        assertEquals("page", config?.tocPageParam)
        assertEquals(1, config?.tocFirstPage)
        assertEquals(".pagination a, ul.pagination li a", config?.tocLastPageSelector)
        assertEquals(200, config?.tocMaxPages)
        assertNull(config?.customTocJs)
        assertNull(config?.customChapterJs)
        assertEquals("", config?.chapterReadySelector)
        assertEquals(0, config?.requestDelayMs)
        assertFalse(config?.isBuiltIn ?: true)
    }
}
