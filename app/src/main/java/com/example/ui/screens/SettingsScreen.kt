package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.TextDefaults
import com.example.ui.theme.screenContentPadding
import com.example.viewmodel.MainViewModel
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
    var totalStorageText by remember { mutableStateOf("Calculating...") }

    // Calculate total storage
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath("novel_hoarder_db")
            val dbSize = if (dbFile.exists()) dbFile.length() else 0L
            val filesDirSize = getFolderSize(context.filesDir)
            val cacheDirSize = getFolderSize(context.cacheDir)
            val total = dbSize + filesDirSize + cacheDirSize
            val mb = total / (1024 * 1024)
            withContext(Dispatchers.Main) {
                totalStorageText = "$mb MB"
            }
        }
    }

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

                Text("Total Library Storage: $totalStorageText", style = MaterialTheme.typography.bodyMedium)

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 6. Your Data ---
            item {
                SectionHeader("Your Data & Backup")

                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportLauncher.launch("novel_hoarder_backup.json") },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export Metadata JSON", softWrap = false)
                    }

                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import Backup", softWrap = false)
                    }
                }

                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    modifier = Modifier.padding(vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Site Data & Cookies", softWrap = false)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // --- 7. About ---
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
        }
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
