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
class BundledPluginTest {

    private lateinit var context: Context
    private lateinit var pluginManager: PluginManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear previous user plugins to start clean
        val pluginDir = File(context.filesDir, "plugins")
        pluginDir.deleteRecursively()
        pluginDir.mkdirs()
        pluginManager = PluginManager(context)
    }

    @Test
    fun testBundledPluginsExistAndParseCorrectly() {
        // Assets are packaged in the app, so they should be read by loadPlugins() under Robolectric
        val plugins = pluginManager.plugins.value
        val wuxia = plugins.find { it.id == "wuxiabox" }
        val wtr = plugins.find { it.id == "wtrlab" }

        assertNotNull("Bundled plugin 'wuxiabox' should exist", wuxia)
        assertNotNull("Bundled plugin 'wtrlab' should exist", wtr)

        assertEquals("WuxiaBox", wuxia?.name)
        assertEquals("WTR Lab", wtr?.name)
        assertTrue(wuxia?.isBuiltIn ?: false)
        assertTrue(wtr?.isBuiltIn ?: false)
    }

    @Test
    fun testUserPluginOverridesBundledAndRestoreWorks() = runBlocking {
        // Verify initially built-in is loaded
        val initialList = pluginManager.plugins.value
        val initialWuxia = initialList.find { it.id == "wuxiabox" }
        assertNotNull(initialWuxia)
        assertTrue(initialWuxia?.isBuiltIn ?: false)

        // Save a user plugin with same ID but different name/author
        val overrideJson = """
            {
              "id": "wuxiabox",
              "name": "WuxiaBox Custom Override",
              "baseUrl": "wuxiabox.com",
              "author": "Custom Author"
            }
        """.trimIndent()

        val saveRes = pluginManager.savePlugin(overrideJson)
        assertTrue(saveRes.isSuccess)

        // Loaded list should now have the custom override
        val overrideList = pluginManager.plugins.value
        val overriddenWuxia = overrideList.find { it.id == "wuxiabox" }
        assertNotNull(overriddenWuxia)
        assertEquals("WuxiaBox Custom Override", overriddenWuxia?.name)
        assertEquals("Custom Author", overriddenWuxia?.author)
        assertFalse(overriddenWuxia?.isBuiltIn ?: true)

        // Delete the plugin - should restore original built-in
        val deleted = pluginManager.deletePlugin("wuxiabox")
        assertTrue("deletePlugin should return true indicating user copy was deleted", deleted)

        val restoredList = pluginManager.plugins.value
        val restoredWuxia = restoredList.find { it.id == "wuxiabox" }
        assertNotNull(restoredWuxia)
        assertEquals("WuxiaBox", restoredWuxia?.name)
        assertTrue(restoredWuxia?.isBuiltIn ?: false)
    }
}
