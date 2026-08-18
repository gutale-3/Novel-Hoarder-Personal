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
            val names = context.assets.list("plugins").orEmpty().filter { it.endsWith(".json") }
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
            val json = context.assets.open("plugins/$pluginId.json")
                .bufferedReader().use { it.readText() }
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

        return _plugins.value.firstOrNull { plugin ->
            if (!plugin.isEnabled) return@firstOrNull false
            val candidates = (listOf(plugin.baseUrl) + plugin.extraHosts).mapNotNull { raw ->
                val cleaned = raw.lowercase()
                    .removePrefix("https://").removePrefix("http://")
                    .removePrefix("www.").substringBefore('/').trim()
                cleaned.ifBlank { null }
            }
            candidates.any { host == it || host.endsWith(".$it") }
        }
    }
}
