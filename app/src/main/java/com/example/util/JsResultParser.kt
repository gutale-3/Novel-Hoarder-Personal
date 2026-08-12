package com.example.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * WebView.evaluateJavascript() hands back the JS return value *JSON-encoded*.
 *
 *  - `return {a:1}`              -> raw result is  {"a":1}                 (one level)
 *  - `return JSON.stringify({})` -> raw result is  "{\"a\":1}"             (two levels)
 *
 * These helpers unwrap either shape, so scripts can return whatever they like.
 */
object JsResultParser {

    fun toJsonObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"null\"" || raw == "undefined") return null
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> JSONTokener(value).nextValue() as? JSONObject
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun toJsonArray(raw: String?): JSONArray? {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"null\"" || raw == "undefined") return null
        return try {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONArray -> value
                is String -> JSONTokener(value).nextValue() as? JSONArray
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun toStringList(raw: String?): List<String> {
        val array = toJsonArray(raw) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val s = array.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** True when a readiness probe reports the page is ready to be scraped. */
    fun isReady(raw: String?): Boolean {
        if (raw == "true" || raw == "\"true\"") return true
        toJsonObject(raw)?.let { return it.optBoolean("ready", false) }
        toJsonArray(raw)?.let { return it.length() > 0 }
        return false
    }

    /** Escapes an arbitrary string for safe interpolation into a JS source literal. */
    fun jsLiteral(value: String?): String = JSONObject.quote(value ?: "")
}
