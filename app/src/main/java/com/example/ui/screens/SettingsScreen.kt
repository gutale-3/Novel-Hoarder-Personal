package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.TextDefaults
import com.example.ui.theme.screenContentPadding
import com.example.viewmodel.MainViewModel
import com.example.util.StorageBreakdown
import com.example.util.StorageMaintenanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = viewModel.settings
    val piperModelManager = viewModel.piperModelManager
    val aiModelManager = viewModel.aiModelManager

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showCrashLogDialog by remember { mutableStateOf(false) }
    var crashLogContent by remember { mutableStateOf<String?>(null) }
    var storageBreakdown by remember { mutableStateOf<StorageBreakdown?>(null) }
    var isCleaningStorage by remember { mutableStateOf(false) }
    var isPruningStorage by remember { mutableStateOf(false) }
    var showWatermarkFilterDialog by remember { mutableStateOf(false) }
    var showLocalBackupsDialog by remember { mutableStateOf(false) }
    var localBackupsList by remember { mutableStateOf<List<File>>(emptyList()) }

    fun refreshStorage() {
        coroutineScope.launch {
            storageBreakdown = StorageMaintenanceManager.computeStorageBreakdown(context, viewModel.repository)
        }
    }

    LaunchedEffect(Unit) {
        refreshStorage()
        try {
            val crashFile = File(context.filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                crashLogContent = crashFile.readText()
            }
        } catch (_: Exception) {}
    }

    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // Export metadata launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val json = settingsManager.exportLibraryMetadataJson(viewModel.repository)
                    context.contentResolver.openOutputStream(uri)?.use { stream: java.io.OutputStream ->
                        stream.write(json.toByteArray())
                    }
                    Toast.makeText(context, "Exported library metadata", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Import metadata launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (json != null) {
                        val count = settingsManager.importLibraryMetadataJson(viewModel.repository, json)
                        Toast.makeText(context, "Imported $count books", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Full ZIP Archive Export Launcher
    val fullZipExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isBackingUp = true
                val result = com.example.util.BackupRestoreManager.createFullBackupZip(
                    context = context,
                    repository = viewModel.repository,
                    outputUri = uri
                )
                isBackingUp = false
                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(context, "Full archive created ($count novels packaged)", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Backup failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // Full ZIP Archive Import Launcher
    val fullZipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isRestoring = true
                val result = com.example.util.BackupRestoreManager.restoreFullBackupZip(
                    context = context,
                    repository = viewModel.repository,
                    inputUri = uri
                )
                isRestoring = false
                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(context, "Successfully restored $count novels & database archive!", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Restore failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // Font import launcher
    val fontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            settingsManager.importCustomFont(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", maxLines = 1, overflow = TextDefaults.OVERFLOW) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = screenContentPadding()
        ) {
            // --- 1. Reading ---
            item {
                SectionHeader("Reading")
                
                // Theme picker
                Text("App Theme", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppTheme.values().take(4).forEach { theme ->
                        FilterChip(
                            selected = settingsManager.currentTheme == theme,
                            onClick = { settingsManager.updateTheme(theme) },
                            label = { Text(theme.name.replace("_", " "), maxLines = 1, softWrap = false) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Reader theme
                Text("Reader Theme", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("follow_app", "light", "sepia", "dark", "oled").forEach { theme ->
                        FilterChip(
                            selected = settingsManager.readerTheme == theme,
                            onClick = { settingsManager.updateReaderTheme(theme) },
                            label = { Text(theme.replace("_", " "), maxLines = 1, softWrap = false) }
                        )
                    }
                }

                // Font size slider
                Text("Font Size: ${settingsManager.readerFontSize} sp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settingsManager.readerFontSize.toFloat(),
                    onValueChange = { settingsManager.updateFontSize(it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 20
                )

                // Paragraph Spacing
                Text("Paragraph Spacing: ${settingsManager.readerParagraphSpacing} dp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settingsManager.readerParagraphSpacing.toFloat(),
                    onValueChange = { settingsManager.updateReaderParagraphSpacing(it.toInt()) },
                    valueRange = 0f..24f,
                    steps = 24
                )

                // First Line Indent
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("First-Line Paragraph Indent", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settingsManager.readerFirstLineIndentEnabled,
                        onCheckedChange = { settingsManager.updateReaderFirstLineIndentEnabled(it) }
                    )
                }

                // Keep screen on
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Keep Screen On While Reading", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settingsManager.readerKeepScreenOnEnabled,
                        onCheckedChange = { settingsManager.updateKeepScreenOnEnabled(it) }
                    )
                }

                // Tap zones
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Page Tap Zones (Left/Right scroll)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settingsManager.readerTapZonesEnabled,
                        onCheckedChange = { settingsManager.updateTapZonesEnabled(it) }
                    )
                }

                // Custom font import button
                OutlinedButton(
                    onClick = { fontLauncher.launch(arrayOf("font/*", "application/x-font-ttf", "*/*")) },
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.FontDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (settingsManager.readerCustomFontName.isNotBlank())
                            "Custom Font: ${settingsManager.readerCustomFontName}"
                        else "Import Custom Font (.ttf)",
                        maxLines = 1,
                        softWrap = false
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 2. Voice ---
            item {
                SectionHeader("Voice & Text-To-Speech")
                
                Text("TTS Voice Engine: Sherpa-ONNX (Offline)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Voices available: Amy Low, Ryan Medium, Kokoro (af_heart, af_bella, am_adam, am_michael).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 3. On-device AI ---
            item {
                SectionHeader("On-Device AI")
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "AI features run entirely on your device. Nothing you read is ever sent anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val modelInstalled = aiModelManager.checkModelExists()
                val modelSizeMb = aiModelManager.modelSizeBytes() / (1024 * 1024)
                Text(
                    "Model Status: " + if (modelInstalled) "Installed ($modelSizeMb MB)" else "Not Installed",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(modifier = Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.showAiSettings = true },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Text("Configure AI Prompts", softWrap = false)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 4. Downloads ---
            item {
                SectionHeader("Downloads & Web Scraping")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Download Next Chapter", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settingsManager.autoDownloadNextEnabled,
                        onCheckedChange = { settingsManager.updateAutoDownloadNextEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Aggressive Ad & Furniture Cleanup", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settingsManager.aggressiveCleanDefault,
                        onCheckedChange = { settingsManager.updateAggressiveCleanDefault(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Chapter Numbering Default", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settingsManager.chapterNumberingMode == "site",
                        onClick = { settingsManager.updateChapterNumberingMode("site") },
                        label = { Text("Site / Source Numbering", softWrap = false) }
                    )
                    FilterChip(
                        selected = settingsManager.chapterNumberingMode == "sequential",
                        onClick = { settingsManager.updateChapterNumberingMode("sequential") },
                        label = { Text("Sequential (1, 2, 3...)", softWrap = false) }
                    )
                }
                Text(
                    text = if (settingsManager.chapterNumberingMode == "sequential")
                        "New chapters will be numbered sequentially (1, 2, 3...)."
                    else
                        "Retain the chapter numbering provided by the source novel site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Table of Contents Subdivision Default", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settingsManager.defaultTocSubdivisionMode == "as_is",
                        onClick = { settingsManager.updateDefaultTocSubdivisionMode("as_is") },
                        label = { Text("Leave As Is", softWrap = false) }
                    )
                    FilterChip(
                        selected = settingsManager.defaultTocSubdivisionMode == "chapters",
                        onClick = { settingsManager.updateDefaultTocSubdivisionMode("chapters") },
                        label = { Text("Chapters (1–50...)", softWrap = false) }
                    )
                    FilterChip(
                        selected = settingsManager.defaultTocSubdivisionMode == "volumes",
                        onClick = { settingsManager.updateDefaultTocSubdivisionMode("volumes") },
                        label = { Text("Volumes", softWrap = false) }
                    )
                }
                Text(
                    text = when (settingsManager.defaultTocSubdivisionMode) {
                        "chapters" -> "Default to dividing novels into chapter sets (1-50, 51-100, etc.) with drop-down accordions."
                        "volumes" -> "Default to grouping novels by story volumes (Volume 1, Volume 2, etc.) with drop-down accordions."
                        else -> "Default to a continuous full chapter list without volume divisions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onNavigateToPlugins,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sites & Plugins Management", softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 5. Library ---
            item {
                SectionHeader("Library")

                Text("Default Sort", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("recently_read", "title", "author", "progress").forEach { sort ->
                        FilterChip(
                            selected = settingsManager.librarySort == sort,
                            onClick = { settingsManager.updateLibrarySort(sort) },
                            label = { Text(sort.replace("_", " "), softWrap = false) }
                        )
                    }
                }

                Text(
                    "Total App Storage: ${storageBreakdown?.let { StorageMaintenanceManager.formatBytes(it.totalSizeBytes) } ?: "Calculating..."}",
                    style = MaterialTheme.typography.bodyMedium
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 6. Advanced Tools & Reading Features (Non-AI) ---
            item {
                SectionHeader("Reading & Automation Features (Non-AI)")
                Text(
                    "All features are modular and run locally on your device. Everything is on by default and can be toggled to your preference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 1. Reading Stats
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reading Statistics & Insights", style = MaterialTheme.typography.bodyMedium)
                        Text("Track daily reading time, speed (WPM), streaks, and progress heatmaps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableReadingStats,
                        onCheckedChange = { settingsManager.updateEnableReadingStats(it) }
                    )
                }

                // 2. Text Replacement Rules
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automated Text Replacement & Regex Rules", style = MaterialTheme.typography.bodyMedium)
                        Text("Clean recurring typos, names, or translation artifacts across downloaded chapters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableAutoApplyTextRules,
                        onCheckedChange = { settingsManager.updateEnableAutoApplyTextRules(it) }
                    )
                }

                // 3. Bionic Reading
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bionic Reading Fixation", style = MaterialTheme.typography.bodyMedium)
                        Text("Bold the initial fixation characters of words to guide the eye and improve reading speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableBionicReading,
                        onCheckedChange = { settingsManager.updateEnableBionicReading(it) }
                    )
                }

                // 4. RSVP Speed Reading
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("RSVP Speed Reading Mode", style = MaterialTheme.typography.bodyMedium)
                        Text("Rapid Serial Visual Presentation with optimal recognition point highlighting", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableRsvpSpeedReading,
                        onCheckedChange = { settingsManager.updateEnableRsvpSpeedReading(it) }
                    )
                }

                // 5. Background Chapter Updates
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Chapter Updates Checker", style = MaterialTheme.typography.bodyMedium)
                        Text("Periodically query library sources in background for newly released chapters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableBackgroundChapterUpdates,
                        onCheckedChange = {
                            settingsManager.updateEnableBackgroundChapterUpdates(it)
                            com.example.background.ChapterUpdateWorker.schedulePeriodicUpdates(context)
                        }
                    )
                }

                if (settingsManager.enableBackgroundChapterUpdates) {
                    Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp)) {
                        Text("Check Frequency", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1, 3, 6, 12, 24).forEach { hours ->
                                FilterChip(
                                    selected = settingsManager.chapterUpdateIntervalHours == hours,
                                    onClick = {
                                        settingsManager.updateChapterUpdateIntervalHours(hours)
                                        com.example.background.ChapterUpdateWorker.schedulePeriodicUpdates(context)
                                    },
                                    label = { Text("${hours}h", softWrap = false) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Only on Unmetered Wi-Fi", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = settingsManager.chapterUpdatesOnlyWifi,
                                onCheckedChange = {
                                    settingsManager.updateChapterUpdatesOnlyWifi(it)
                                    com.example.background.ChapterUpdateWorker.schedulePeriodicUpdates(context)
                                }
                            )
                        }
                    }
                }

                // Automatic Chapter Grabbing (starts from latest chapter)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic Chapter Grabbing", style = MaterialTheme.typography.bodyMedium)
                        Text("Automatically download new chapters starting strictly after your last saved chapter (e.g. 501 if you have 500)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.autoGrabNewChapters,
                        onCheckedChange = {
                            settingsManager.updateAutoGrabNewChapters(it)
                        }
                    )
                }

                // 6. Source Migration
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Multi-Source Migration & Switcher", style = MaterialTheme.typography.bodyMedium)
                        Text("Seamlessly switch novel scraping source while preserving bookmarks, notes, and progress", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableSourceMigration,
                        onCheckedChange = { settingsManager.updateEnableSourceMigration(it) }
                    )
                }

                // 7. Custom Tap Zones
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Custom 9-Zone Reader Tap Zones", style = MaterialTheme.typography.bodyMedium)
                        Text("Map any screen grid sector to page turns, bookmarks, speed reader, or controls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableTapZonesCustomization,
                        onCheckedChange = { settingsManager.updateEnableTapZonesCustomization(it) }
                    )
                }

                // 8. Volume Keys Navigation
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Volume Keys Page Turn", style = MaterialTheme.typography.bodyMedium)
                        Text("Use hardware Volume Up/Down buttons to navigate pages in reader", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableVolumeKeysNavigation,
                        onCheckedChange = { settingsManager.updateEnableVolumeKeysNavigation(it) }
                    )
                }

                // 9. Reading Guide
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reading Focus Guide Overlay", style = MaterialTheme.typography.bodyMedium)
                        Text("Visual horizontal focus bar to guide tracking and reduce dyslexia or eye strain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.enableReadingGuide,
                        onCheckedChange = { settingsManager.updateEnableReadingGuide(it) }
                    )
                }

                // 10. Extra Dim (Sub-Zero Night Reading)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Extra Dim (Sub-Zero)", style = MaterialTheme.typography.bodyMedium)
                        Text("Software black filter for night reading below screen minimum brightness", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.readerExtraDimEnabled,
                        onCheckedChange = { settingsManager.updateReaderExtraDimEnabled(it) }
                    )
                }

                if (settingsManager.readerExtraDimEnabled) {
                    Column(modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Dim Level", style = MaterialTheme.typography.labelMedium)
                            Text("${settingsManager.readerExtraDimPercent}%", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(
                            value = settingsManager.readerExtraDimPercent.toFloat(),
                            onValueChange = { settingsManager.updateReaderExtraDimPercent(it.toInt()) },
                            valueRange = 5f..80f,
                            steps = 14
                        )
                    }
                }

                // 11. Watermark & Aggregator Noise Stripper
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Chapter Noise & Watermark Cleaner", style = MaterialTheme.typography.bodyMedium)
                        Text("Strip site promotional headers/footers, Patreon/Discord links, and aggregator spam", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settingsManager.watermarkRemovalEnabled,
                        onCheckedChange = { settingsManager.updateWatermarkRemovalEnabled(it) }
                    )
                }

                if (settingsManager.watermarkRemovalEnabled) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showWatermarkFilterDialog = true },
                            modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                        ) {
                            Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (settingsManager.customWatermarkPhrases.isNotBlank()) "Custom Watermarks (Configured)" else "Add Custom Watermark Phrases",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 7. Your Data & Full Backup ---
            item {
                SectionHeader("Your Data & Backup")

                Text(
                    "Create full offline zip archives of your entire library, downloaded chapters, bookmarks, glossaries, reading stats, and covers, or export light metadata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Full Archive Buttons
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { fullZipExportLauncher.launch("novel_hoarder_full_backup.zip") },
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Archiving...")
                        } else {
                            Icon(Icons.Default.Archive, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Full ZIP Archive", softWrap = false)
                        }
                    }

                    OutlinedButton(
                        onClick = { fullZipImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restoring...")
                        } else {
                            Icon(Icons.Default.Unarchive, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restore ZIP", softWrap = false)
                        }
                    }
                }

                // Local Offline Snapshots
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isBackingUp = true
                                val res = com.example.util.BackupRestoreManager.createLocalAutoBackup(context, viewModel.repository)
                                isBackingUp = false
                                res.fold(
                                    onSuccess = { file ->
                                        Toast.makeText(context, "Saved local snapshot: ${file.name}", Toast.LENGTH_SHORT).show()
                                        localBackupsList = com.example.util.BackupRestoreManager.listLocalBackups(context)
                                    },
                                    onFailure = { e ->
                                        Toast.makeText(context, "Snapshot failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Snapshot", softWrap = false)
                    }

                    OutlinedButton(
                        onClick = {
                            localBackupsList = com.example.util.BackupRestoreManager.listLocalBackups(context)
                            showLocalBackupsDialog = true
                        },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Snapshots List", softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Light Metadata Export Buttons
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("novel_hoarder_metadata.json") },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export JSON", softWrap = false)
                    }

                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import JSON", softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Storage Breakdown & Reclaim Space
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Storage & Cache Management", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }

                        val bd = storageBreakdown
                        if (bd != null) {
                            Text("Total App Storage: ${StorageMaintenanceManager.formatBytes(bd.totalSizeBytes)}", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("• Database: ${StorageMaintenanceManager.formatBytes(bd.databaseSizeBytes)} (${bd.totalBooksCount} books, ${bd.totalChaptersCount} chapters)", style = MaterialTheme.typography.bodySmall)
                            Text("• Book Covers: ${StorageMaintenanceManager.formatBytes(bd.coversSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("• Export & Temp Cache: ${StorageMaintenanceManager.formatBytes(bd.tempExportsSizeBytes + bd.otherCacheSizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Calculating storage...", style = MaterialTheme.typography.bodySmall)
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Smart Space Saver
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Smart Space Saver", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                                Text("Retain full chapter text for only the last X read chapters. Bookmarks, read flags, and stats are preserved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = settingsManager.autoPruneReadChaptersEnabled,
                                onCheckedChange = { settingsManager.updateAutoPruneReadChaptersEnabled(it) }
                            )
                        }

                        if (settingsManager.autoPruneReadChaptersEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Keep last:", style = MaterialTheme.typography.labelMedium)
                                listOf(20, 50, 100).forEach { count ->
                                    FilterChip(
                                        selected = settingsManager.keepLastReadChaptersCount == count,
                                        onClick = { settingsManager.updateKeepLastReadChaptersCount(count) },
                                        label = { Text("$count chapters") }
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isPruningStorage = true
                                    val count = viewModel.repository.pruneOldReadChapters(settingsManager.keepLastReadChaptersCount)
                                    StorageMaintenanceManager.vacuumDatabase(context)
                                    refreshStorage()
                                    isPruningStorage = false
                                    if (count > 0) {
                                        Toast.makeText(context, "Pruned text from $count read chapters. Storage reclaimed!", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Read chapters are already within the ${settingsManager.keepLastReadChaptersCount} chapter threshold.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isPruningStorage && !isCleaningStorage,
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = MinTouchTarget)
                        ) {
                            if (isPruningStorage) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pruning Chapters...")
                            } else {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Prune Read Content (Keep Last ${settingsManager.keepLastReadChaptersCount})", fontSize = 12.sp)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isCleaningStorage = true
                                        val freedExports = StorageMaintenanceManager.cleanTempExportFiles(context)
                                        val deletedCovers = StorageMaintenanceManager.cleanOrphanedCovers(context, viewModel.repository)
                                        StorageMaintenanceManager.vacuumDatabase(context)
                                        refreshStorage()
                                        isCleaningStorage = false
                                        Toast.makeText(context, "Cleaned: ${StorageMaintenanceManager.formatBytes(freedExports)} cache freed, $deletedCovers orphaned covers removed", Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !isCleaningStorage,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isCleaningStorage) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clean Cache", fontSize = 12.sp, softWrap = false)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isCleaningStorage = true
                                        StorageMaintenanceManager.vacuumDatabase(context)
                                        refreshStorage()
                                        isCleaningStorage = false
                                        Toast.makeText(context, "Database optimized and compacted", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isCleaningStorage,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Optimize DB", fontSize = 12.sp, softWrap = false)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    modifier = Modifier.padding(vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Site Data & Cookies", softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 8. About ---
            item {
                SectionHeader("About")

                Text("Novel Hoarder v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
                Text("Offline-first Web Novel Reader & Library", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This app makes no network calls except the ones you ask for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Open Source Libraries:", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Jetpack Compose, Room, WorkManager, Kotlin Coroutines, Jsoup, Sherpa-ONNX, ONNX Runtime, Apache Commons Compress, Moshi, Coil, MediaPipe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- 9. Diagnostics & Stability ---
            item {
                SectionHeader("Diagnostics & Stability")

                if (crashLogContent != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Previous Crash Log Saved",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { showCrashLogDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("View Crash Log")
                                }
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            File(context.filesDir, "last_crash.txt").delete()
                                            crashLogContent = null
                                            Toast.makeText(context, "Crash log cleared", Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) {}
                                    }
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "No crashes detected. App environment is running stably.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val report = buildString {
                            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                            appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
                            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        }
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Device Diagnostics", report))
                        Toast.makeText(context, "System diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Device Diagnostics")
                }
            }
        }
    }

    if (showCrashLogDialog && crashLogContent != null) {
        AlertDialog(
            onDismissRequest = { showCrashLogDialog = false },
            title = { Text("Crash Diagnostics Log") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    Text(
                        text = crashLogContent ?: "",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash Log", crashLogContent ?: ""))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCrashLogDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Clear Site Data & Cookies?") },
            text = { Text("This will clear cached web pages, cookies, and web storage used by scrapers. Your downloaded novels and reading history will NOT be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        showClearDataDialog = false
                        Toast.makeText(context, "Site data & cookies cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Watermark Filter Configuration Dialog
    if (showWatermarkFilterDialog) {
        var tempPhrases by remember { mutableStateOf(settingsManager.customWatermarkPhrases) }
        AlertDialog(
            onDismissRequest = { showWatermarkFilterDialog = false },
            title = { Text("Chapter Watermark Stripper") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter promotional phrases, aggregator watermarks, or site names to strip (separated by commas or newlines):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = tempPhrases,
                        onValueChange = { tempPhrases = it },
                        placeholder = { Text("e.g. read at novelupdates.com, chapter updated by bot") },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        settingsManager.updateCustomWatermarkPhrases(tempPhrases)
                        showWatermarkFilterDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkFilterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Local Snapshots Management Dialog
    if (showLocalBackupsDialog) {
        AlertDialog(
            onDismissRequest = { showLocalBackupsDialog = false },
            title = { Text("Local Offline Snapshots") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    if (localBackupsList.isEmpty()) {
                        Text(
                            "No local snapshots saved yet. Tap 'Save Snapshot' to create one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val sdf = remember { java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(localBackupsList.size) { index ->
                                val file = localBackupsList[index]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(file.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                                            Text(
                                                "${StorageMaintenanceManager.formatBytes(file.length())} • ${sdf.format(java.util.Date(file.lastModified()))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            IconButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        isRestoring = true
                                                        val res = com.example.util.BackupRestoreManager.restoreLocalBackup(context, viewModel.repository, file)
                                                        isRestoring = false
                                                        res.fold(
                                                            onSuccess = { count ->
                                                                Toast.makeText(context, "Restored snapshot ($count novels)", Toast.LENGTH_LONG).show()
                                                                showLocalBackupsDialog = false
                                                            },
                                                            onFailure = { e ->
                                                                Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                            }
                                                        )
                                                    }
                                                },
                                                enabled = !isRestoring && !isBackingUp
                                            ) {
                                                Icon(Icons.Default.Unarchive, contentDescription = "Restore Snapshot", tint = MaterialTheme.colorScheme.primary)
                                            }

                                            IconButton(
                                                onClick = {
                                                    com.example.util.BackupRestoreManager.deleteLocalBackup(file)
                                                    localBackupsList = com.example.util.BackupRestoreManager.listLocalBackups(context)
                                                    Toast.makeText(context, "Snapshot deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Snapshot", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocalBackupsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

private fun getFolderSize(file: File): Long {
    var size: Long = 0
    if (file.isDirectory) {
        val files = file.listFiles() ?: return 0
        for (f in files) {
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
    } else {
        size = file.length()
    }
    return size
}
