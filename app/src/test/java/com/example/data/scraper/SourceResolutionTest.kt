package com.example.data.scraper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginExecutionEngine
import com.example.data.plugin.PluginManager
import com.example.util.GenericScraper
import com.example.util.TomatoScraper
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
class SourceResolutionTest {

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
    fun testTomatoMtlWinsEvenWithConflictingPlugin() = runBlocking {
        // Save a conflicting plugin that matches "tomato"
        val conflictingJson = """
            {
              "id": "conflicting_tomato",
              "name": "Conflicting Tomato Plugin",
              "baseUrl": "tomatomtl.com"
            }
        """.trimIndent()
        pluginManager.savePlugin(conflictingJson)

        val url = "https://tomatomtl.com/novel/sweet-adventure"
        val resolvedSource = SourceManager.getSourceForUrl(url, pluginManager)

        // It must resolve to TomatoScraper, because hand-tuned scraper wins
        assertEquals(TomatoScraper, resolvedSource)
    }

    @Test
    fun testWuxiaBoxUrlResolvesToPluginEngine() {
        val url = "https://wuxiabox.com/novel/peerless-hero"
        val resolvedSource = SourceManager.getSourceForUrl(url, pluginManager)

        assertTrue(resolvedSource is PluginExecutionEngine)
        val engine = resolvedSource as PluginExecutionEngine
        assertEquals("wuxiabox", engine.config.id)
    }

    @Test
    fun testUnknownUrlResolvesToGenericScraper() {
        val url = "https://completely-unsupported-site.com/novel/123"
        val resolvedSource = SourceManager.getSourceForUrl(url, pluginManager)

        assertEquals(GenericScraper, resolvedSource)
    }
}
