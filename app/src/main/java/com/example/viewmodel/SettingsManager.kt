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
    var requestDelayMs by mutableStateOf(3000)     // pause between consecutive page loads
    var batchSize by mutableStateOf(10)            // default "download next N"
    var autoFetchWhileReading by mutableStateOf(true)

    // --- Reader Settings ---
    var readerTheme by mutableStateOf("follow_app") // "follow_app", "light", "dark", "sepia", "night", "mint", "nord", "oled"
    var readerPagingMode by mutableStateOf("paged") // "paged" (Single Chapter with Next Button), "continuous" (Infinite Vertical Scroll)
    var readerLineHeight by mutableStateOf(1.4f)
    var readerParagraphSpacing by mutableStateOf(12) // in dp (0..24)
    var readerFirstLineIndentEnabled by mutableStateOf(false)
    var readerMargin by mutableStateOf(16) // in dp padding
    var readerLetterSpacing by mutableStateOf(0.0f)
    var readerWordSpacing by mutableStateOf(0.0f)
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
    var activeLibraryCategory by mutableStateOf("All")
    var aggressiveCleanDefault by mutableStateOf(false)
    var chapterNumberingMode by mutableStateOf("site") // "site" (keep original), "sequential" (1, 2, 3...)
    var lastBrowserUrl by mutableStateOf("https://www.google.com")

    // --- Customizable AI Prompts & Translation ---
    var glossaryPrompt by mutableStateOf("")
    var polishPrompt by mutableStateOf("")
    var recapPrompt by mutableStateOf("")
    var translationRequireWifi by mutableStateOf(true)

    // --- 7 Core Non-AI Feature Toggles & Preferences (On by default) ---
    var enableReadingStats by mutableStateOf(true)
    var userReadingWpm by mutableStateOf(240)

    var enableBionicReading by mutableStateOf(true)
    var bionicReadingActiveInReader by mutableStateOf(false)

    var enableRsvpSpeedReading by mutableStateOf(true)
    var rsvpWpm by mutableStateOf(300)

    var enableVolumeKeysNavigation by mutableStateOf(true)

    var enableTapZonesCustomization by mutableStateOf(true)
    var tapZoneTopLeftAction by mutableStateOf("PREV_PAGE")
    var tapZoneTopCenterAction by mutableStateOf("TOGGLE_CONTROLS")
    var tapZoneTopRightAction by mutableStateOf("NEXT_PAGE")
    var tapZoneMidLeftAction by mutableStateOf("PREV_PAGE")
    var tapZoneMidCenterAction by mutableStateOf("TOGGLE_CONTROLS")
    var tapZoneMidRightAction by mutableStateOf("NEXT_PAGE")
    var tapZoneBottomLeftAction by mutableStateOf("PREV_PAGE")
    var tapZoneBottomCenterAction by mutableStateOf("TOGGLE_CONTROLS")
    var tapZoneBottomRightAction by mutableStateOf("NEXT_PAGE")

    var enableReadingGuide by mutableStateOf(false)
    var readingGuideHeight by mutableStateOf(80)
    var readingGuideColor by mutableStateOf("amber")
    var readingGuideOpacity by mutableStateOf(0.15f)

    var enableAutoCheckUpdates by mutableStateOf(true)
    var updateCheckIntervalHours by mutableStateOf(12) // 6, 12, 24
    var updateCheckWifiOnly by mutableStateOf(true)

    val enableBackgroundChapterUpdates get() = enableAutoCheckUpdates
    val chapterUpdateIntervalHours get() = updateCheckIntervalHours
    val chapterUpdatesOnlyWifi get() = updateCheckWifiOnly

    var enableAutoApplyTextRules by mutableStateOf(true)
    var enableFullBackupRestore by mutableStateOf(true)
    var enableSourceMigration by mutableStateOf(true)

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
        requestDelayMs = prefs.getInt("request_delay_ms", 3000)
        batchSize = prefs.getInt("batch_size", 10)
        autoFetchWhileReading = prefs.getBoolean("auto_fetch_while_reading", true)
        readerTheme = prefs.getString("reader_theme", "follow_app") ?: "follow_app"
        readerPagingMode = prefs.getString("reader_paging_mode", "paged") ?: "paged"
        readerLineHeight = prefs.getFloat("reader_line_height", 1.4f)
        readerParagraphSpacing = prefs.getInt("reader_paragraph_spacing", 12)
        readerFirstLineIndentEnabled = prefs.getBoolean("reader_first_line_indent_enabled", false)
        readerMargin = prefs.getInt("reader_margin", 16)
        readerLetterSpacing = prefs.getFloat("reader_letter_spacing", 0.0f)
        readerWordSpacing = prefs.getFloat("reader_word_spacing", 0.0f)
        readerCustomFontPath = prefs.getString("reader_custom_font_path", "") ?: ""
        readerCustomFontName = prefs.getString("reader_custom_font_name", "") ?: ""
        readerJustificationEnabled = prefs.getBoolean("reader_justification_enabled", false)
        readerHyphenationEnabled = prefs.getBoolean("reader_hyphenation_enabled", false)
        readerAmbientSyncEnabled = prefs.getBoolean("reader_ambient_sync_enabled", false)
        readerTapZonesEnabled = prefs.getBoolean("reader_tap_zones_enabled", true)
        readerKeepScreenOnEnabled = prefs.getBoolean("reader_keep_screen_on_enabled", false)

        librarySort = prefs.getString("library_sort", "recently_read") ?: "recently_read"
        libraryView = prefs.getString("library_view", "grid") ?: "grid"
        activeLibraryCategory = prefs.getString("active_library_category", "All") ?: "All"
        aggressiveCleanDefault = prefs.getBoolean("aggressive_clean_default", false)
        chapterNumberingMode = prefs.getString("chapter_numbering_mode", "site") ?: "site"
        lastBrowserUrl = prefs.getString("last_browser_url", "https://www.google.com") ?: "https://www.google.com"

        glossaryPrompt = prefs.getString("glossary_prompt", "Analyze the following novel content and identify character names, locations, and unique terms that are poorly machine-translated or require a consistent translation glossary.") ?: "Analyze the following novel content and identify character names, locations, and unique terms that are poorly machine-translated or require a consistent translation glossary."
        polishPrompt = prefs.getString("polish_prompt", "Rewrite this machine-translated chapter to be in fluent, literary, highly readable English. Preserve the exact original plot, character actions, and meaning. Do not add any commentary or prefix/suffix notes. Only return the polished story text.") ?: "Rewrite this machine-translated chapter to be in fluent, literary, highly readable English. Preserve the exact original plot, character actions, and meaning. Do not add any commentary or prefix/suffix notes. Only return the polished story text."
        recapPrompt = prefs.getString("recap_prompt", "Provide a concise summary ('Previously on...') of the following chapter. Focus on key plot points and character actions in 2-3 sentences. Do not add metadata or conversational padding.") ?: "Provide a concise summary ('Previously on...') of the following chapter. Focus on key plot points and character actions in 2-3 sentences. Do not add metadata or conversational padding."
        translationRequireWifi = prefs.getBoolean("translation_require_wifi", true)

        // Load 7 features
        enableReadingStats = prefs.getBoolean("enable_reading_stats", true)
        userReadingWpm = prefs.getInt("user_reading_wpm", 240)

        enableBionicReading = prefs.getBoolean("enable_bionic_reading", true)
        bionicReadingActiveInReader = prefs.getBoolean("bionic_reading_active", false)

        enableRsvpSpeedReading = prefs.getBoolean("enable_rsvp_speed_reading", true)
        rsvpWpm = prefs.getInt("rsvp_wpm", 300)

        enableVolumeKeysNavigation = prefs.getBoolean("enable_volume_keys_nav", true)

        enableTapZonesCustomization = prefs.getBoolean("enable_tap_zones_custom", true)
        tapZoneTopLeftAction = prefs.getString("tap_zone_tl", "PREV_PAGE") ?: "PREV_PAGE"
        tapZoneTopCenterAction = prefs.getString("tap_zone_tc", "TOGGLE_CONTROLS") ?: "TOGGLE_CONTROLS"
        tapZoneTopRightAction = prefs.getString("tap_zone_tr", "NEXT_PAGE") ?: "NEXT_PAGE"
        tapZoneMidLeftAction = prefs.getString("tap_zone_ml", "PREV_PAGE") ?: "PREV_PAGE"
        tapZoneMidCenterAction = prefs.getString("tap_zone_mc", "TOGGLE_CONTROLS") ?: "TOGGLE_CONTROLS"
        tapZoneMidRightAction = prefs.getString("tap_zone_mr", "NEXT_PAGE") ?: "NEXT_PAGE"
        tapZoneBottomLeftAction = prefs.getString("tap_zone_bl", "PREV_PAGE") ?: "PREV_PAGE"
        tapZoneBottomCenterAction = prefs.getString("tap_zone_bc", "TOGGLE_CONTROLS") ?: "TOGGLE_CONTROLS"
        tapZoneBottomRightAction = prefs.getString("tap_zone_br", "NEXT_PAGE") ?: "NEXT_PAGE"

        enableReadingGuide = prefs.getBoolean("enable_reading_guide", false)
        readingGuideHeight = prefs.getInt("reading_guide_height", 80)

        enableAutoCheckUpdates = prefs.getBoolean("enable_auto_check_updates", true)
        updateCheckIntervalHours = prefs.getInt("update_check_interval_hours", 12)
        updateCheckWifiOnly = prefs.getBoolean("update_check_wifi_only", true)

        enableAutoApplyTextRules = prefs.getBoolean("enable_auto_apply_text_rules", true)
        enableFullBackupRestore = prefs.getBoolean("enable_full_backup_restore", true)
        enableSourceMigration = prefs.getBoolean("enable_source_migration", true)
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

    fun updateReaderPagingMode(mode: String) {
        readerPagingMode = mode
        prefs.edit().putString("reader_paging_mode", mode).apply()
    }

    fun updateReaderWordSpacing(spacing: Float) {
        readerWordSpacing = spacing.coerceIn(-0.1f, 0.5f)
        prefs.edit().putFloat("reader_word_spacing", readerWordSpacing).apply()
    }

    fun updateActiveLibraryCategory(category: String) {
        activeLibraryCategory = category
        prefs.edit().putString("active_library_category", category).apply()
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

    fun updateChapterNumberingMode(mode: String) {
        chapterNumberingMode = mode
        prefs.edit().putString("chapter_numbering_mode", mode).apply()
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

    fun updateRequestDelayMs(delayMs: Int) {
        requestDelayMs = delayMs.coerceIn(500, 30000)
        prefs.edit().putInt("request_delay_ms", requestDelayMs).apply()
    }

    fun updateBatchSize(size: Int) {
        batchSize = size.coerceIn(1, 200)
        prefs.edit().putInt("batch_size", batchSize).apply()
    }

    fun updateAutoFetchWhileReading(enabled: Boolean) {
        autoFetchWhileReading = enabled
        prefs.edit().putBoolean("auto_fetch_while_reading", enabled).apply()
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

    // --- 7 Feature Toggles & Preferences Updaters ---
    fun updateEnableReadingStats(enabled: Boolean) {
        enableReadingStats = enabled
        prefs.edit().putBoolean("enable_reading_stats", enabled).apply()
    }

    fun updateUserReadingWpm(wpm: Int) {
        userReadingWpm = wpm.coerceIn(100, 1000)
        prefs.edit().putInt("user_reading_wpm", userReadingWpm).apply()
    }

    fun updateEnableBionicReading(enabled: Boolean) {
        enableBionicReading = enabled
        prefs.edit().putBoolean("enable_bionic_reading", enabled).apply()
    }

    fun updateBionicReadingActiveInReader(active: Boolean) {
        bionicReadingActiveInReader = active
        prefs.edit().putBoolean("bionic_reading_active", active).apply()
    }

    fun updateEnableRsvpSpeedReading(enabled: Boolean) {
        enableRsvpSpeedReading = enabled
        prefs.edit().putBoolean("enable_rsvp_speed_reading", enabled).apply()
    }

    fun updateRsvpWpm(wpm: Int) {
        rsvpWpm = wpm.coerceIn(100, 1000)
        prefs.edit().putInt("rsvp_wpm", rsvpWpm).apply()
    }

    fun updateEnableVolumeKeysNavigation(enabled: Boolean) {
        enableVolumeKeysNavigation = enabled
        prefs.edit().putBoolean("enable_volume_keys_nav", enabled).apply()
    }

    fun updateEnableTapZonesCustomization(enabled: Boolean) {
        enableTapZonesCustomization = enabled
        prefs.edit().putBoolean("enable_tap_zones_custom", enabled).apply()
    }

    fun updateTapZoneAction(zoneKey: String, action: String) {
        when (zoneKey) {
            "tl" -> { tapZoneTopLeftAction = action; prefs.edit().putString("tap_zone_tl", action).apply() }
            "tc" -> { tapZoneTopCenterAction = action; prefs.edit().putString("tap_zone_tc", action).apply() }
            "tr" -> { tapZoneTopRightAction = action; prefs.edit().putString("tap_zone_tr", action).apply() }
            "ml" -> { tapZoneMidLeftAction = action; prefs.edit().putString("tap_zone_ml", action).apply() }
            "mc" -> { tapZoneMidCenterAction = action; prefs.edit().putString("tap_zone_mc", action).apply() }
            "mr" -> { tapZoneMidRightAction = action; prefs.edit().putString("tap_zone_mr", action).apply() }
            "bl" -> { tapZoneBottomLeftAction = action; prefs.edit().putString("tap_zone_bl", action).apply() }
            "bc" -> { tapZoneBottomCenterAction = action; prefs.edit().putString("tap_zone_bc", action).apply() }
            "br" -> { tapZoneBottomRightAction = action; prefs.edit().putString("tap_zone_br", action).apply() }
        }
    }

    fun updateEnableReadingGuide(enabled: Boolean) {
        enableReadingGuide = enabled
        prefs.edit().putBoolean("enable_reading_guide", enabled).apply()
    }

    fun updateReadingGuideHeight(height: Int) {
        readingGuideHeight = height.coerceIn(40, 200)
        prefs.edit().putInt("reading_guide_height", readingGuideHeight).apply()
    }

    fun updateReadingGuideColor(color: String) {
        readingGuideColor = color
        prefs.edit().putString("reading_guide_color", color).apply()
    }

    fun updateReadingGuideOpacity(opacity: Float) {
        readingGuideOpacity = opacity.coerceIn(0.05f, 0.5f)
        prefs.edit().putFloat("reading_guide_opacity", readingGuideOpacity).apply()
    }

    fun updateEnableAutoCheckUpdates(enabled: Boolean) {
        enableAutoCheckUpdates = enabled
        prefs.edit().putBoolean("enable_auto_check_updates", enabled).apply()
    }

    fun updateEnableBackgroundChapterUpdates(enabled: Boolean) = updateEnableAutoCheckUpdates(enabled)

    fun updateCheckIntervalHours(hours: Int) {
        updateCheckIntervalHours = hours
        prefs.edit().putInt("update_check_interval_hours", hours).apply()
    }

    fun updateChapterUpdateIntervalHours(hours: Int) = updateCheckIntervalHours(hours)

    fun updateCheckWifiOnly(wifiOnly: Boolean) {
        updateCheckWifiOnly = wifiOnly
        prefs.edit().putBoolean("update_check_wifi_only", wifiOnly).apply()
    }

    fun updateChapterUpdatesOnlyWifi(wifiOnly: Boolean) = updateCheckWifiOnly(wifiOnly)

    fun updateEnableAutoApplyTextRules(enabled: Boolean) {
        enableAutoApplyTextRules = enabled
        prefs.edit().putBoolean("enable_auto_apply_text_rules", enabled).apply()
    }

    fun updateEnableFullBackupRestore(enabled: Boolean) {
        enableFullBackupRestore = enabled
        prefs.edit().putBoolean("enable_full_backup_restore", enabled).apply()
    }

    fun updateEnableSourceMigration(enabled: Boolean) {
        enableSourceMigration = enabled
        prefs.edit().putBoolean("enable_source_migration", enabled).apply()
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
        com.example.util.BackupRestoreManager.generateBackupMetadataJson(repository)
    }

    suspend fun importLibraryMetadataJson(repository: NovelRepository, jsonStr: String): Int = withContext(Dispatchers.IO) {
        com.example.util.BackupRestoreManager.importJsonString(repository, jsonStr)
    }
}
