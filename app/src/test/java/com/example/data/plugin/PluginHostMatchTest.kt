package com.example.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginHostMatchTest {

    private lateinit var context: Context
    private lateinit var pluginManager: PluginManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure directories exist
        File(context.filesDir, "plugins").mkdirs()
        pluginManager = PluginManager(context)
    }

    @Test
    fun testFindPluginForUrlMatchesHostAndExtraHosts() {
        val plugin = PluginConfig(
            id = "wuxiabox",
            name = "WuxiaBox",
            baseUrl = "wuxiabox.com",
            extraHosts = listOf("www.wuxiabox.com", "alt.wuxiabox.com"),
            isEnabled = true
        )

        // Injecting the plugin into pluginManager's list by saving it
        val json = """
            {
              "id": "wuxiabox",
              "name": "WuxiaBox",
              "baseUrl": "wuxiabox.com",
              "extraHosts": ["www.wuxiabox.com", "alt.wuxiabox.com"],
              "isEnabled": true
            }
        """.trimIndent()

        val saveResult = kotlinx.coroutines.runBlocking {
            pluginManager.savePlugin(json)
        }
        assertTrue(saveResult.isSuccess)

        // Test matches base URL
        val matched1 = pluginManager.findPluginForUrl("https://wuxiabox.com/novel/123")
        assertNotNull(matched1)
        assertEquals("wuxiabox", matched1?.id)

        // Test matches www
        val matched2 = pluginManager.findPluginForUrl("https://www.wuxiabox.com/novel/123")
        assertNotNull(matched2)
        assertEquals("wuxiabox", matched2?.id)

        // Test matches extra hosts
        val matched3 = pluginManager.findPluginForUrl("https://alt.wuxiabox.com/novel/123")
        assertNotNull(matched3)
        assertEquals("wuxiabox", matched3?.id)

        // Test does NOT match a completely different or hostile domain containing base URL
        val matched4 = pluginManager.findPluginForUrl("https://notwuxiabox.com/novel/123")
        assertNull(matched4)

        val matched5 = pluginManager.findPluginForUrl("https://wuxiabox.com.evil.example/novel/123")
        assertNull(matched5)
    }

    @Test
    fun testFindPluginForUrlIgnoresDisabledPlugins() {
        val json = """
            {
              "id": "disabled_site",
              "name": "Disabled Site",
              "baseUrl": "disabled.com",
              "isEnabled": false
            }
        """.trimIndent()

        val saveResult = kotlinx.coroutines.runBlocking {
            pluginManager.savePlugin(json)
        }
        assertTrue(saveResult.isSuccess)

        val matched = pluginManager.findPluginForUrl("https://disabled.com/novel/123")
        assertNull(matched)
    }
}
