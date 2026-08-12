package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.repository.NovelRepository
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SettingsManager(val application: Application) {
    private val prefs = application.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var currentTheme by mutableStateOf(AppTheme.CLASSIC_LIGHT)
    var readerFontSize by mutableStateOf(16)
    var readerFontFamily by mutableStateOf("serif")
    var defaultUserAgent by mutableStateOf("")

    // --- Auto-download / Reading Queue ---
    var autoDownloadNextEnabled by mutableStateOf(true)

    // --- Reader Settings ---
    var readerTheme by mutableStateOf("follow_app") // "follow_app", "light", "dark", "sepia", "night", "mint", "nord", "oled"
    var readerLineHeight by mutableStateOf(1.4f)
    var readerParagraphSpacing by mutableStateOf(12) // in dp (0..24)
    var readerFirstLineIndentEnabled by mutableStateOf(false)
    var readerMargin by mutableStateOf(16) // in dp padding
    var readerLetterSpacing by mutableStateOf(0.0f)
    var readerCustomFontPath by mutableStateOf("")
    var readerCustomFontName by mutableStateOf("")
    var readerJustificationEnabled by mutableStateOf(false)
    var readerHyphenationEnabled by mutableStateOf(false)
    var readerAmbientSyncEnabled by mutableStateOf(false)
    var readerTapZonesEnabled by mutableStateOf(true)
    var readerKeepScreenOnEnabled by mutableStateOf(false)

    // --- Library & Browse Settings ---
    var librarySort by mutableStateOf("recently_read") // "recently_read", "recently_updated", "title", "author", "unread_count", "progress"
    var libraryView by mutableStateOf("grid") // "grid", "list"
    var aggressiveCleanDefault by mutableStateOf(false)
    var lastBrowserUrl by mutableStateOf("https://tomatoy.com")

    // --- Customizable AI Prompts & Translation ---
    var glossaryPrompt by mutableStateOf("")
    var polishPrompt by mutableStateOf("")
    var recapPrompt by mutableStateOf("")
    var translationRequireWifi by mutableStateOf(true)

    init {
        val themeName = prefs.getString("selected_theme", AppTheme.CLASSIC_LIGHT.name)
        currentTheme = AppTheme.valueOf(themeName ?: AppTheme.CLASSIC_LIGHT.name)
        readerFontSize = prefs.getInt("reader_font_size", 18)
        readerFontFamily = prefs.getString("reader_font_family", "serif") ?: "serif"
        defaultUserAgent = prefs.getString("user_agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36") ?: ""

        if (prefs.contains("gemini_api_key") || prefs.contains("active_ai_provider")) {
            prefs.edit().remove("gemini_api_key").remove("active_ai_provider").apply()
        }

        autoDownloadNextEnabled = prefs.getBoolean("auto_download_next", true)
        readerTheme = prefs.getString("reader_theme", "follow_app") ?: "follow_app"
        readerLineHeight = prefs.getFloat("reader_line_height", 1.4f)
        readerParagraphSpacing = prefs.getInt("reader_paragraph_spacing", 12)
        readerFirstLineIndentEnabled = prefs.getBoolean("reader_first_line_indent_enabled", false)
        readerMargin = prefs.getInt("reader_margin", 16)
        readerLetterSpacing = prefs.getFloat("reader_letter_spacing", 0.0f)
        readerCustomFontPath = prefs.getString("reader_custom_font_path", "") ?: ""
        readerCustomFontName = prefs.getString("reader_custom_font_name", "") ?: ""
        readerJustificationEnabled = prefs.getBoolean("reader_justification_enabled", false)
        readerHyphenationEnabled = prefs.getBoolean("reader_hyphenation_enabled", false)
        readerAmbientSyncEnabled = prefs.getBoolean("reader_ambient_sync_enabled", false)
        readerTapZonesEnabled = prefs.getBoolean("reader_tap_zones_enabled", true)
        readerKeepScreenOnEnabled = prefs.getBoolean("reader_keep_screen_on_enabled", false)

        librarySort = prefs.getString("library_sort", "recently_read") ?: "recently_read"
        libraryView = prefs.getString("library_view", "grid") ?: "grid"
        aggressiveCleanDefault = prefs.getBoolean("aggressive_clean_default", false)
        lastBrowserUrl = prefs.getString("last_browser_url", "https://tomatoy.com") ?: "https://tomatoy.com"

        glossaryPrompt = prefs.getString("glossary_prompt", "Analyze the following novel content and identify character names, locations, and unique terms that are poorly machine-translated or require a consistent translation glossary.") ?: "Analyze the following novel content and identify character names, locations, and unique terms that are poorly machine-translated or require a consistent translation glossary."
        polishPrompt = prefs.getString("polish_prompt", "Rewrite this machine-translated chapter to be in fluent, literary, highly readable English. Preserve the exact original plot, character actions, and meaning. Do not add any commentary or prefix/suffix notes. Only return the polished story text.") ?: "Rewrite this machine-translated chapter to be in fluent, literary, highly readable English. Preserve the exact original plot, character actions, and meaning. Do not add any commentary or prefix/suffix notes. Only return the polished story text."
        recapPrompt = prefs.getString("recap_prompt", "Provide a concise summary ('Previously on...') of the following chapter. Focus on key plot points and character actions in 2-3 sentences. Do not add metadata or conversational padding.") ?: "Provide a concise summary ('Previously on...') of the following chapter. Focus on key plot points and character actions in 2-3 sentences. Do not add metadata or conversational padding."
        translationRequireWifi = prefs.getBoolean("translation_require_wifi", true)
    }

    fun updateTheme(theme: AppTheme) {
        currentTheme = theme
        prefs.edit().putString("selected_theme", theme.name).apply()
    }

    fun updateFontSize(size: Int) {
        readerFontSize = size.coerceIn(12, 32)
        prefs.edit().putInt("reader_font_size", readerFontSize).apply()
    }

    fun updateFontFamily(family: String) {
        readerFontFamily = family
        prefs.edit().putString("reader_font_family", family).apply()
    }

    fun updateReaderTheme(theme: String) {
        readerTheme = theme
        prefs.edit().putString("reader_theme", theme).apply()
    }

    fun updateReaderLineHeight(height: Float) {
        readerLineHeight = height
        prefs.edit().putFloat("reader_line_height", height).apply()
    }

    fun updateReaderParagraphSpacing(spacing: Int) {
        readerParagraphSpacing = spacing.coerceIn(0, 24)
        prefs.edit().putInt("reader_paragraph_spacing", readerParagraphSpacing).apply()
    }

    fun updateReaderFirstLineIndentEnabled(enabled: Boolean) {
        readerFirstLineIndentEnabled = enabled
        prefs.edit().putBoolean("reader_first_line_indent_enabled", enabled).apply()
    }

    fun updateReaderMargin(margin: Int) {
        readerMargin = margin
        prefs.edit().putInt("reader_margin", margin).apply()
    }

    fun updateReaderLetterSpacing(spacing: Float) {
        readerLetterSpacing = spacing.coerceIn(-0.05f, 0.25f)
        prefs.edit().putFloat("reader_letter_spacing", readerLetterSpacing).apply()
    }

    fun updateCustomFont(path: String, name: String) {
        readerCustomFontPath = path
        readerCustomFontName = name
        prefs.edit()
            .putString("reader_custom_font_path", path)
            .putString("reader_custom_font_name", name)
            .apply()
    }

    fun updateJustificationEnabled(enabled: Boolean) {
        readerJustificationEnabled = enabled
        prefs.edit().putBoolean("reader_justification_enabled", enabled).apply()
    }

    fun updateHyphenationEnabled(enabled: Boolean) {
        readerHyphenationEnabled = enabled
        prefs.edit().putBoolean("reader_hyphenation_enabled", enabled).apply()
    }

    fun updateAmbientSyncEnabled(enabled: Boolean) {
        readerAmbientSyncEnabled = enabled
        prefs.edit().putBoolean("reader_ambient_sync_enabled", enabled).apply()
    }

    fun updateTapZonesEnabled(enabled: Boolean) {
        readerTapZonesEnabled = enabled
        prefs.edit().putBoolean("reader_tap_zones_enabled", enabled).apply()
    }

    fun updateKeepScreenOnEnabled(enabled: Boolean) {
        readerKeepScreenOnEnabled = enabled
        prefs.edit().putBoolean("reader_keep_screen_on_enabled", enabled).apply()
    }

    fun updateLibrarySort(sort: String) {
        librarySort = sort
        prefs.edit().putString("library_sort", sort).apply()
    }

    fun updateLibraryView(view: String) {
        libraryView = view
        prefs.edit().putString("library_view", view).apply()
    }

    fun updateAggressiveCleanDefault(enabled: Boolean) {
        aggressiveCleanDefault = enabled
        prefs.edit().putBoolean("aggressive_clean_default", enabled).apply()
    }

    fun updateLastBrowserUrl(url: String) {
        lastBrowserUrl = url
        prefs.edit().putString("last_browser_url", url).apply()
    }

    fun importCustomFont(context: Context, uri: Uri) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                var displayName = "CustomFont.ttf"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }

                val fontsDir = File(context.filesDir, "fonts")
                if (!fontsDir.exists()) {
                    fontsDir.mkdirs()
                }
                fontsDir.listFiles()?.forEach { it.delete() }

                val destFile = File(fontsDir, displayName)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (destFile.exists()) {
                    withContext(Dispatchers.Main) {
                        updateCustomFont(destFile.absolutePath, displayName)
                        updateFontFamily("custom")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateAutoDownloadNextEnabled(enabled: Boolean) {
        autoDownloadNextEnabled = enabled
        prefs.edit().putBoolean("auto_download_next", enabled).apply()
    }

    fun updateGlossaryPrompt(prompt: String) {
        glossaryPrompt = prompt
        prefs.edit().putString("glossary_prompt", prompt).apply()
    }

    fun updatePolishPrompt(prompt: String) {
        polishPrompt = prompt
        prefs.edit().putString("polish_prompt", prompt).apply()
    }

    fun updateRecapPrompt(prompt: String) {
        recapPrompt = prompt
        prefs.edit().putString("recap_prompt", prompt).apply()
    }

    fun updateTranslationRequireWifi(requireWifi: Boolean) {
        translationRequireWifi = requireWifi
        prefs.edit().putBoolean("translation_require_wifi", requireWifi).apply()
    }

    fun resetReaderDefaults() {
        updateFontSize(18)
        updateFontFamily("serif")
        updateReaderTheme("follow_app")
        updateReaderLineHeight(1.4f)
        updateReaderParagraphSpacing(12)
        updateReaderFirstLineIndentEnabled(false)
        updateReaderMargin(16)
        updateReaderLetterSpacing(0.0f)
        updateJustificationEnabled(false)
        updateHyphenationEnabled(false)
        updateTapZonesEnabled(true)
        updateKeepScreenOnEnabled(false)
    }

    suspend fun exportLibraryMetadataJson(repository: NovelRepository): String = withContext(Dispatchers.IO) {
        val books = repository.getAllBooks()
        val jsonArray = JSONArray()

        for (book in books) {
            val bookObj = JSONObject()
            bookObj.put("id", book.id)
            bookObj.put("title", book.title)
            bookObj.put("author", book.author)
            bookObj.put("synopsis", book.synopsis)
            bookObj.put("coverUrl", book.coverUrl ?: "")
            bookObj.put("url", book.url)
            bookObj.put("lastReadChapterId", book.lastReadChapterId ?: "")

            val chapters = repository.getChapters(book.id)
            val chArray = JSONArray()
            for (ch in chapters) {
                val chObj = JSONObject()
                chObj.put("id", ch.id)
                chObj.put("chapterId", ch.chapterId)
                chObj.put("title", ch.title)
                chObj.put("content", ch.content)
                chObj.put("url", ch.url)
                chObj.put("chapterNumber", ch.chapterNumber)
                chObj.put("hash", ch.hash)
                chArray.put(chObj)
            }
            bookObj.put("chapters", chArray)

            val glossaries = repository.getGlossary(book.id)
            val glArray = JSONArray()
            for (gl in glossaries) {
                val glObj = JSONObject()
                glObj.put("id", gl.id)
                glObj.put("originalText", gl.originalText)
                glObj.put("replacementText", gl.replacementText)
                glArray.put(glObj)
            }
            bookObj.put("glossaries", glArray)

            jsonArray.put(bookObj)
        }

        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("books", jsonArray)
        root.toString(2)
    }

    suspend fun importLibraryMetadataJson(repository: NovelRepository, jsonStr: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonStr)
        val booksArray = root.getJSONArray("books")
        var importedCount = 0

        for (i in 0 until booksArray.length()) {
            val bookObj = booksArray.getJSONObject(i)
            val book = com.example.data.local.BookEntity(
                id = bookObj.getString("id"),
                url = bookObj.optString("url", ""),
                title = bookObj.getString("title"),
                author = bookObj.optString("author", "Unknown Author"),
                synopsis = bookObj.optString("synopsis", ""),
                coverUrl = bookObj.optString("coverUrl", null).takeIf { !it.isNullOrEmpty() },
                coverLocalPath = null,
                lastReadChapterId = bookObj.optString("lastReadChapterId", null).takeIf { !it.isNullOrEmpty() },
                totalChapters = 0
            )
            repository.insertBook(book)

            val chArray = bookObj.optJSONArray("chapters")
            if (chArray != null) {
                for (j in 0 until chArray.length()) {
                    val chObj = chArray.getJSONObject(j)
                    val ch = com.example.data.local.ChapterEntity(
                        id = chObj.getString("id"),
                        bookId = book.id,
                        chapterId = chObj.optString("chapterId", "ch_${j+1}"),
                        chapterNumber = chObj.optInt("chapterNumber", j + 1),
                        title = chObj.getString("title"),
                        url = chObj.optString("url", ""),
                        content = chObj.optString("content", ""),
                        hash = chObj.optString("hash", "")
                    )
                    repository.insertChapter(ch)
                }
            }

            val glArray = bookObj.optJSONArray("glossaries")
            if (glArray != null) {
                for (j in 0 until glArray.length()) {
                    val glObj = glArray.getJSONObject(j)
                    val gl = com.example.data.local.GlossaryEntity(
                        id = 0,
                        bookId = book.id,
                        originalText = glObj.getString("originalText"),
                        replacementText = glObj.getString("replacementText")
                    )
                    repository.insertGlossary(gl)
                }
            }

            importedCount++
        }
        importedCount
    }
}
