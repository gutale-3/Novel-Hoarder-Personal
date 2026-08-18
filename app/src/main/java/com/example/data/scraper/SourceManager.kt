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
        val lowerUrl = url.lowercase()

        // TomatoMTL is hand-tuned and must beat both plugins and the generic fallback.
        if (lowerUrl.contains("tomatomtl") || lowerUrl.contains("tomatoy") || lowerUrl.contains("tomato")) {
            return TomatoScraper
        }

        val plugins = pluginManager ?: pluginManagerProvider?.invoke()
        plugins?.findPluginForUrl(url)?.let { return PluginExecutionEngine(it) }

        return GenericScraper
    }
}
