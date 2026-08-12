package com.example.data.plugin

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun loadPlugins(): List<PluginConfig> {
        val list = mutableListOf<PluginConfig>()
        val files = pluginDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        for (file in files) {
            try {
                val json = file.readText()
                val config = adapter.fromJson(json)
                if (config != null) list.add(config)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _plugins.value = list
        return list
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
        return try {
            val config = validatePluginJson(jsonContent)
            val file = pluginFile(config.id)
            file.writeText(adapter.toJson(config))
            loadPlugins()
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePluginConfig(config: PluginConfig): Result<Unit> {
        return try {
            require(idPattern.matches(config.id)) { "Invalid plugin id: ${config.id}" }
            val file = pluginFile(config.id)
            file.writeText(adapter.toJson(config))
            loadPlugins()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlugin(pluginId: String): Boolean {
        return try {
            val file = pluginFile(pluginId)
            val deleted = file.delete()
            loadPlugins()
            deleted
        } catch (e: Exception) {
            false
        }
    }

    suspend fun togglePlugin(pluginId: String, enabled: Boolean) {
        val existing = _plugins.value.find { it.id == pluginId } ?: return
        val updated = existing.copy(isEnabled = enabled)
        savePluginConfig(updated)
    }

    fun findPluginForUrl(url: String): PluginConfig? {
        val lowerUrl = url.lowercase()
        return _plugins.value.firstOrNull { plugin ->
            plugin.isEnabled && lowerUrl.contains(plugin.baseUrl.lowercase())
        }
    }
}
