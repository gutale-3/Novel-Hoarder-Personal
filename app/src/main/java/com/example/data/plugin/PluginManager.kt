package com.example.data.plugin

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class PluginManager(private val context: Context) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(PluginConfig::class.java)

    private val pluginDir: File = File(context.filesDir, "plugins").apply { mkdirs() }

    /** Newer copies of built-in plugins downloaded by [updateFromRemote]. */
    private val remoteDir: File = File(context.filesDir, "plugins_remote").apply { mkdirs() }

    /** Version of each built-in plugin as shipped or remotely updated, ignoring user edits. */
    @Volatile
    private var builtInVersions: Map<String, String> = emptyMap()

    private val _plugins = MutableStateFlow<List<PluginConfig>>(emptyList())
    val plugins: StateFlow<List<PluginConfig>> = _plugins.asStateFlow()

    private val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    init {
        loadPlugins()
    }

    private fun pluginFile(id: String): File {
        require(idPattern.matches(id)) { "Invalid plugin id: $id" }
        val file = File(pluginDir, "$id.json")
        if (!file.canonicalPath.startsWith(pluginDir.canonicalPath + File.separator)) {
            throw IllegalArgumentException("Invalid plugin id: $id")
        }
        return file
    }

    /**
     * Loads bundled plugins from assets, then user plugins from internal storage. A user plugin with
     * the same id replaces the bundled one, so a built-in site can be edited and later restored by
     * deleting the edited copy.
     */
    fun loadPlugins(): List<PluginConfig> {
        val byId = LinkedHashMap<String, PluginConfig>()

        // 1. bundled
        try {
            val names = context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") && it != INDEX_FILE }
            for (name in names) {
                try {
                    val json = context.assets.open("plugins/$name")
                        .bufferedReader().use { it.readText() }
                    adapter.fromJson(json)?.let { cfg ->
                        val enabled = prefs.getBoolean("plugin_enabled_${cfg.id}", true)
                        byId[cfg.id] = cfg.copy(isBuiltIn = true, isEnabled = enabled)
                    }
                } catch (e: Exception) {
                    Log.e("PluginManager", "Bundled plugin $name is invalid", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Could not list bundled plugins", e)
        }

        // 1b. remotely updated built-ins replace the shipped copy only when newer, so an app
        // update that ships a fresher plugin is never shadowed by an older download.
        try {
            val files = remoteDir.listFiles { _, n -> n.endsWith(".json") } ?: emptyArray()
            for (file in files) {
                try {
                    adapter.fromJson(file.readText())?.let { cfg ->
                        val shipped = byId[cfg.id]
                        if (shipped == null || compareVersions(cfg.version, shipped.version) > 0) {
                            val enabled = prefs.getBoolean("plugin_enabled_${cfg.id}", true)
                            byId[cfg.id] = cfg.copy(isBuiltIn = true, isEnabled = enabled)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PluginManager", "Remote plugin ${file.name} is invalid", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to list remote plugins", e)
        }
        builtInVersions = byId.mapValues { it.value.version }

        // 2. user plugins override bundled ones with the same id
        try {
            val files = pluginDir.listFiles { _, n -> n.endsWith(".json") } ?: emptyArray()
            for (file in files) {
                try {
                    adapter.fromJson(file.readText())?.let { cfg ->
                        byId[cfg.id] = cfg.copy(isBuiltIn = false)
                    }
                } catch (e: Exception) {
                    Log.e("PluginManager", "User plugin ${file.name} is invalid", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to list user plugins", e)
        }

        val loaded = byId.values.toList()
        _plugins.value = loaded
        return loaded
    }

    fun validatePluginJson(jsonStr: String): PluginConfig {
        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException("Plugin content must be a valid JSON object.")
        }

        val jsonObject = JSONObject(trimmed)
        val id = jsonObject.optString("id", "").trim()
        if (id.isEmpty()) throw IllegalArgumentException("Missing required field: 'id'")
        if (!idPattern.matches(id)) {
            throw IllegalArgumentException(
                "Plugin 'id' may only contain letters, numbers, '_' and '-' (max 64 characters)."
            )
        }
        val name = jsonObject.optString("name", "").trim()
        if (name.isEmpty()) throw IllegalArgumentException("Missing required field: 'name'")
        val baseUrl = jsonObject.optString("baseUrl", "").trim()
        if (baseUrl.isEmpty()) throw IllegalArgumentException("Missing required field: 'baseUrl'")

        return adapter.fromJson(trimmed)
            ?: throw IllegalArgumentException("Failed to parse plugin structure.")
    }

    suspend fun savePlugin(jsonContent: String): Result<PluginConfig> {
        return withContext(Dispatchers.IO) {
            try {
                val config = validatePluginJson(jsonContent)
                val file = pluginFile(config.id)
                file.writeText(adapter.toJson(config))
                loadPlugins()
                Result.success(config)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun savePluginConfig(config: PluginConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                require(idPattern.matches(config.id)) { "Invalid plugin id: ${config.id}" }
                val file = pluginFile(config.id)
                file.writeText(adapter.toJson(config))
                loadPlugins()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun deletePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val file = pluginFile(pluginId)
        val hadUserCopy = file.exists() && file.delete()
        loadPlugins()
        // Deleting the user copy of a built-in restores the shipped version rather than removing it.
        hadUserCopy
    }

    suspend fun togglePlugin(pluginId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = _plugins.value.find { it.id == pluginId } ?: return@withContext
        if (current.isBuiltIn) {
            prefs.edit().putBoolean("plugin_enabled_$pluginId", enabled).apply()
            loadPlugins()
        } else {
            savePluginConfig(current.copy(isEnabled = enabled))
        }
    }

    /**
     * Copies a bundled plugin into user storage so it can be edited.
     */
    suspend fun forkBuiltIn(pluginId: String): Result<PluginConfig> = withContext(Dispatchers.IO) {
        try {
            val remote = File(remoteDir, "$pluginId.json")
            val shipped = runCatching {
                context.assets.open("plugins/$pluginId.json").bufferedReader().use { it.readText() }
            }.getOrNull()
            val remoteJson = remote.takeIf { it.exists() }?.readText()
            val json = when {
                remoteJson == null -> shipped ?: throw IllegalArgumentException("Unknown built-in plugin $pluginId")
                shipped == null -> remoteJson
                else -> {
                    val r = adapter.fromJson(remoteJson)?.version.orEmpty()
                    val b = adapter.fromJson(shipped)?.version.orEmpty()
                    if (compareVersions(r, b) > 0) remoteJson else shipped
                }
            }
            val config = adapter.fromJson(json) ?: throw IllegalArgumentException("Invalid bundled plugin")
            val file = pluginFile(config.id)
            file.writeText(adapter.toJson(config.copy(isBuiltIn = false)))
            loadPlugins()
            Result.success(config.copy(isBuiltIn = false))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolves a plugin by hostname. Substring matching over the whole URL was too loose — a URL
     * merely containing the site's name anywhere would match.
     */
    fun findPluginForUrl(url: String): PluginConfig? {
        val host = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (e: Exception) {
            null
        } ?: return null

        return _plugins.value.firstOrNull { plugin -> plugin.isEnabled && hostMatches(plugin, host) }
    }

    /** True when [url] belongs to [plugin]'s site, whether or not the plugin is enabled. */
    fun matches(plugin: PluginConfig, url: String): Boolean {
        val host = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (e: Exception) {
            null
        } ?: return false
        return hostMatches(plugin, host)
    }

    private fun hostMatches(plugin: PluginConfig, host: String): Boolean {
        val candidates = (listOf(plugin.baseUrl) + plugin.extraHosts).mapNotNull { raw ->
            val cleaned = raw.lowercase()
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").substringBefore('/').trim()
            cleaned.ifBlank { null }
        }
        return candidates.any { host == it || host.endsWith(".$it") }
    }

    fun exportPluginJson(config: PluginConfig): String {
        return adapter.indent("  ").toJson(config)
    }

    suspend fun duplicatePlugin(pluginId: String, newName: String? = null): Result<PluginConfig> = withContext(Dispatchers.IO) {
        val original = _plugins.value.find { it.id == pluginId }
            ?: return@withContext Result.failure(IllegalArgumentException("Plugin $pluginId not found"))

        var baseId = "${original.id}_copy"
        var counter = 1
        while (pluginFile(baseId).exists() || _plugins.value.any { it.id == baseId }) {
            counter++
            baseId = "${original.id}_copy$counter"
        }

        val copyConfig = original.copy(
            id = baseId,
            name = newName ?: "${original.name} (Copy)",
            isBuiltIn = false
        )
        val file = pluginFile(copyConfig.id)
        file.writeText(adapter.toJson(copyConfig))
        loadPlugins()
        Result.success(copyConfig)
    }

    data class RemoteUpdateSummary(val updated: List<String>, val failed: List<String>)

    /**
     * Downloads built-in plugins that are newer in the GitHub repo than on this device, so a site
     * fix is a JSON edit pushed to the repo instead of a new app build. User-edited copies are
     * left alone; they keep overriding the built-in version.
     */
    suspend fun updateFromRemote(baseUrl: String = DEFAULT_REMOTE_BASE): Result<RemoteUpdateSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                val entries = parseRemoteIndex(httpGet(base + INDEX_FILE))
                val updated = mutableListOf<String>()
                val failed = mutableListOf<String>()
                for (entry in entries) {
                    val known = builtInVersions[entry.id]
                    if (known != null && compareVersions(entry.version, known) <= 0) continue
                    try {
                        val json = httpGet(base + entry.file)
                        val cfg = validatePluginJson(json)
                        require(cfg.id == entry.id) { "id mismatch: index says ${entry.id}, file says ${cfg.id}" }
                        File(remoteDir, "${cfg.id}.json").writeText(json)
                        updated += "${cfg.name} ${cfg.version}"
                    } catch (e: Exception) {
                        Log.w("PluginManager", "Remote plugin ${entry.id} failed", e)
                        failed += "${entry.id}: ${e.message}"
                    }
                }
                prefs.edit().putLong(KEY_LAST_REMOTE_CHECK, System.currentTimeMillis()).apply()
                loadPlugins()
                RemoteUpdateSummary(updated, failed)
            }
        }

    /** Runs [updateFromRemote] at most once a day; failures are silent because it is a background nicety. */
    suspend fun updateFromRemoteIfDue() {
        val last = prefs.getLong(KEY_LAST_REMOTE_CHECK, 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        updateFromRemote()
    }

    private fun httpGet(url: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun getBuiltInCatalog(): List<PluginConfig> {
        val list = mutableListOf<PluginConfig>()
        try {
            val names = context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") && it != INDEX_FILE }
            for (name in names) {
                try {
                    val json = context.assets.open("plugins/$name").bufferedReader().use { it.readText() }
                    adapter.fromJson(json)?.let { cfg ->
                        list.add(cfg.copy(isBuiltIn = true))
                    }
                } catch (e: Exception) {
                    Log.e("PluginManager", "Failed to load catalog plugin $name", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PluginManager", "Failed to list catalog plugins", e)
        }
        return list
    }

    fun createDefaultPlugin(template: String = "standard", initialUrl: String = ""): PluginConfig {
        val host = try {
            java.net.URI(initialUrl).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        val safeId = if (host.isNotBlank()) host.replace(".", "_") else "custom_site"
        val displayName = if (host.isNotBlank()) host.replaceFirstChar { it.uppercase() } else "Custom Novel Site"

        return when (template) {
            "paginated" -> PluginConfig(
                id = safeId,
                name = displayName,
                version = "1.0.0",
                baseUrl = host.ifBlank { "example.com" },
                author = "User",
                description = "Custom add-on for $displayName with paginated table of contents",
                titleSelector = "h3.title, h1.title, h1",
                authorSelector = ".author, span.author, a[href*='author']",
                synopsisSelector = ".description, .synopsis, meta[name='description']",
                coverSelector = ".cover img, img.cover, meta[property='og:image']",
                chapterListSelector = "ul.chapter-list li a, .chapters a, a[href*='chapter']",
                chapterTitleSelector = "h1.chapter-title, h1",
                chapterBodySelector = ".chapter-content, #content, article",
                chapterReadySelector = ".chapter-content, #content",
                tocPaginationMode = "query",
                tocPageParam = "page",
                tocFirstPage = 1,
                tocLastPageSelector = ".pagination a, ul.pagination li a",
                tocMaxPages = 100,
                requestDelayMs = 1500,
                isEnabled = true
            )
            "spa" -> PluginConfig(
                id = safeId,
                name = displayName,
                version = "1.0.0",
                baseUrl = host.ifBlank { "example.com" },
                author = "User",
                description = "Custom add-on for $displayName Single-Page App with dynamic content",
                titleSelector = "h1, .book-title",
                authorSelector = ".author, .book-author",
                synopsisSelector = ".description, .summary",
                coverSelector = "img.cover, meta[property='og:image']",
                chapterListSelector = "a[href*='chapter'], a[href*='read']",
                chapterTitleSelector = "h1.title, h1",
                chapterBodySelector = "#chapter-content, .reader-body, main",
                chapterReadySelector = "#chapter-content, .reader-body",
                requestDelayMs = 2500,
                isEnabled = true
            )
            else -> PluginConfig(
                id = safeId,
                name = displayName,
                version = "1.0.0",
                baseUrl = host.ifBlank { "example.com" },
                author = "User",
                description = "Custom add-on for $displayName",
                titleSelector = "h1, .title",
                authorSelector = ".author, a[href*='author']",
                synopsisSelector = ".description, .synopsis, meta[name='description']",
                coverSelector = "img.cover, meta[property='og:image']",
                chapterListSelector = "a[href*='chapter'], a[href*='ch-']",
                chapterTitleSelector = "h1, .chapter-title",
                chapterBodySelector = "article, main, .chapter-content, #content",
                chapterReadySelector = ".chapter-content, #content",
                requestDelayMs = 1200,
                isEnabled = true
            )
        }
    }

    companion object {
        const val INDEX_FILE = "index.json"
        const val DEFAULT_REMOTE_BASE =
            "https://raw.githubusercontent.com/gutale-3/Novel-Hoarder-Personal/main/app/src/main/assets/plugins/"
        private const val KEY_LAST_REMOTE_CHECK = "plugin_remote_last_check"

        data class RemoteEntry(val id: String, val version: String, val file: String)

        /** Parses `{ "plugins": [ { "id", "version", "file" } ] }`. */
        fun parseRemoteIndex(json: String): List<RemoteEntry> {
            val arr = JSONObject(json).getJSONArray("plugins")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                RemoteEntry(id, o.getString("version"), o.optString("file", "$id.json"))
            }
        }

        /** Numeric dotted-version comparison; missing or non-numeric parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
