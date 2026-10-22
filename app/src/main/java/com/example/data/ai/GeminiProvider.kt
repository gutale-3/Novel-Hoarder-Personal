package com.example.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional cloud AI through Google Gemini, using the user's own (free) API key from Google AI
 * Studio. Off until a key is entered; when on, recaps and other AI features can work without
 * importing a multi-gigabyte on-device model. The text sent is the chapter being summarised.
 */
class GeminiProvider(private val context: Context) : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"

    private val prefs get() = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_API_KEY, v.trim()).apply()

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    /** "lite" (fastest, most free requests a day) or "flash" (smarter, fewer free requests). */
    var modelChoice: String
        get() = prefs.getString(KEY_MODEL, "lite") ?: "lite"
        set(v) = prefs.edit().putString(KEY_MODEL, v).apply()

    private fun modelsToTry(): List<String> =
        if (modelChoice == "flash") listOf(MODEL_FLASH, MODEL_LITE) else listOf(MODEL_LITE, MODEL_FLASH)

    /**
     * Tries the chosen model, then the other one if Google says it is busy or out of free
     * requests (429/5xx) or no longer exists (404).
     */
    override suspend fun generate(prompt: String, jsonMode: Boolean): String = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) return@withContext "Error: no Gemini API key set"
        var last = "Error: no response"
        for (model in modelsToTry()) {
            val (code, text) = call(model, key, prompt, jsonMode)
            if (code in 200..299) return@withContext text
            last = text
            if (code != 429 && code != 404 && code < 500) break
        }
        last
    }

    /** A tiny request to confirm the key works; returns null when it does, else the reason. */
    suspend fun testKey(): String? = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) return@withContext "Enter a key first"
        val (code, text) = call(modelsToTry().first(), key, "Reply with OK", false)
        when {
            code in 200..299 -> null
            code == 400 || code == 403 -> "Google rejected this key"
            code == 429 -> "Key works, but today's free requests are used up"
            else -> text.removePrefix("Error: ")
        }
    }

    private fun call(model: String, key: String, prompt: String, jsonMode: Boolean): Pair<Int, String> {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (jsonMode) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return code to "Error: Gemini returned HTTP $code"
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            code to (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }
                .ifBlank { "Error: empty response" }
        } catch (e: Exception) {
            -1 to "Error: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val KEY_API_KEY = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        // Google's "latest" aliases follow the current models, so the app needn't be updated
        // when a version is retired.
        const val MODEL_LITE = "gemini-flash-lite-latest"
        const val MODEL_FLASH = "gemini-flash-latest"
    }
}
