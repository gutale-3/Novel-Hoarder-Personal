package com.example.data.scraper

import com.example.data.plugin.PluginExecutionEngine
import com.example.data.plugin.PluginManager
import com.example.util.GenericScraper
import com.example.util.TomatoScraper

object SourceManager {

    /**
     * Resolves the right NovelSource for a URL.
     *
     * Order matters: the built-in TomatoMTL adapter is tuned for that site and must win over both
     * user plugins and the universal scraper. User plugins come next, then the universal fallback.
     */
    fun getSourceForUrl(url: String, pluginManager: PluginManager? = null): NovelSource {
        val lowerUrl = url.lowercase()

        if (lowerUrl.contains("tomatomtl") || lowerUrl.contains("tomato") || lowerUrl.contains("tomatoy")) {
            return TomatoScraper
        }

        pluginManager?.findPluginForUrl(url)?.let { plugin ->
            return PluginExecutionEngine(plugin)
        }

        return GenericScraper
    }
}
