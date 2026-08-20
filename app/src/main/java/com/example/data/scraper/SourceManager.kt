package com.example.data.scraper

import com.example.data.plugin.PluginExecutionEngine
import com.example.data.plugin.PluginManager
import com.example.util.GenericScraper
import com.example.util.TomatoScraper

object SourceManager {

    /**
     * Set once by AppContainer. Held here so every call site resolves plugins without each of
     * them having to thread a PluginManager through — the previous design meant a forgotten
     * parameter silently disabled every user plugin.
     */
    @Volatile
    var pluginManagerProvider: (() -> PluginManager?)? = null

    fun getSourceForUrl(url: String, pluginManager: PluginManager? = null): NovelSource {
        val host = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }

        // TomatoMTL is hand-tuned and must beat both plugins and the generic fallback.
        val tomatoHosts = listOf("tomatomtl.com", "tomatoy.com")
        if (host != null && tomatoHosts.any { host == it || host.endsWith(".$it") }) {
            return TomatoScraper
        }

        val plugins = pluginManager ?: pluginManagerProvider?.invoke()
        plugins?.findPluginForUrl(url)?.let { return PluginExecutionEngine(it) }

        return GenericScraper
    }
}
