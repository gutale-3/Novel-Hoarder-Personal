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

    override suspend fun generate(prompt: String, jsonMode: Boolean): String = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) return@withContext "Error: no Gemini API key set"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        if (jsonMode) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        try {
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
            if (code !in 200..299) return@withContext "Error: Gemini returned HTTP $code"
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.getJSONObject(it).optString("text") }
                .ifBlank { "Error: empty response" }
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val KEY_API_KEY = "gemini_api_key"
        const val MODEL = "gemini-2.5-flash"
    }
}
