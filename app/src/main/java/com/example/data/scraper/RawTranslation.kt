package com.example.data.scraper

import android.content.Context
import com.example.data.local.GlossaryEntity
import com.example.util.TranslatorEngine

/**
 * Raw (Chinese) sources: chapters are translated to English on download with the on-device
 * ML Kit model, using the novel's glossary so names stay consistent. A novel can switch this off
 * (keep the raw text); when the model is unavailable the raw text is saved and can be translated
 * later from the novel's menu.
 */
object RawTranslation {

    private const val PREFS = "raw_translation"

    /** Language of a source, from its plugin ("zh" for raw Chinese sites). */
    fun sourceLanguage(url: String): String =
        (SourceManager.getSourceForUrl(url) as? com.example.data.plugin.PluginExecutionEngine)?.config?.language ?: "en"

    fun isRawSource(url: String) = sourceLanguage(url) == "zh"

    fun isEnabled(context: Context, bookId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on_$bookId", true)

    fun setEnabled(context: Context, bookId: String, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on_$bookId", on).apply()

    /** True when most letters are Chinese characters, i.e. the text is still untranslated. */
    fun looksUntranslated(text: String): Boolean {
        val sample = text.take(2000)
        val letters = sample.count { it.isLetter() }
        if (letters == 0) return false
        val cjk = sample.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return cjk.toDouble() / letters > 0.3
    }

    /**
     * Translates title and body when [url] is a raw source and the novel wants translation.
     * Returns the input unchanged otherwise, or if translation fails.
     */
    suspend fun localize(
        context: Context,
        bookId: String,
        url: String,
        title: String,
        body: String,
        glossary: List<GlossaryEntity>,
        allowMobileData: Boolean
    ): Pair<String, String> {
        if (!isRawSource(url) || !isEnabled(context, bookId) || !looksUntranslated(body)) return title to body
        return try {
            val t = if (looksUntranslated(title)) TranslatorEngine.translateText(context, title, glossary = glossary, allowMobileData = allowMobileData) else title
            val b = TranslatorEngine.translateText(context, body, glossary = glossary, allowMobileData = allowMobileData)
            t.trim() to b
        } catch (e: Exception) {
            android.util.Log.w("RawTranslation", "Translation failed; keeping raw text", e)
            title to body
        }
    }
}
