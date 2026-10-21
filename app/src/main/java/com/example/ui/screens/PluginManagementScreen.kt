package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginManager
import com.example.data.plugin.SourceHealthChecker
import com.example.data.scraper.SourceManager
import com.example.ui.components.PluginStudioDialog
import com.example.ui.components.WebInspectorDialog
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.TextDefaults
import com.example.util.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen(
    pluginManager: PluginManager,
    onBack: () -> Unit,
    libraryUrls: suspend () -> List<String> = { emptyList() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val plugins by pluginManager.plugins.collectAsState()

    val healthChecker = remember { SourceHealthChecker(context.applicationContext, pluginManager) }
    var healthResults by remember { mutableStateOf(healthChecker.lastResults()) }
    var healthProgress by remember { mutableStateOf<String?>(null) }
    var healthJob by remember { mutableStateOf<Job?>(null) }
    var isUpdatingRemote by remember { mutableStateOf(false) }
    var showLimitsDialog by remember { mutableStateOf(false) }

    var activeTopTab by remember { mutableIntStateOf(0) } // 0 = Installed, 1 = Catalog & Presets, 2 = Diagnostic Lab
    var searchQuery by remember { mutableStateOf("") }

    // Dialog & Studio States
    var showStudioDialog by remember { mutableStateOf(false) }
    var studioConfigToEdit by remember { mutableStateOf<PluginConfig?>(null) }
    var studioIsNew by remember { mutableStateOf(false) }

    var showInspectorDialog by remember { mutableStateOf(false) }
    var inspectorInitialUrl by remember { mutableStateOf("") }

    var showTemplatePickerDialog by remember { mutableStateOf(false) }
    var showPasteJsonDialog by remember { mutableStateOf(false) }
    var pasteJsonText by remember { mutableStateOf("") }

    var pluginToDelete by remember { mutableStateOf<PluginConfig?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Export plugin launcher
    var pendingExportPlugin by remember { mutableStateOf<PluginConfig?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingExportPlugin != null) {
            coroutineScope.launch {
                try {
                    val json = pluginManager.exportPluginJson(pendingExportPlugin!!)
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray())
                    }
                    Toast.makeText(context, "Exported ${pendingExportPlugin!!.name} add-on", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    errorMessage = "Export failed: ${e.message}"
                } finally {
                    pendingExportPlugin = null
                }
            }
        }
    }

    // Import plugin file launcher
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content != null) {
                        val result = pluginManager.savePlugin(content)
                        result.onSuccess {
                            Toast.makeText(context, "Add-on '${it.name}' installed successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            errorMessage = it.message
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }
        }
    }

    // Diagnostic Site Tester state
    var diagnosticUrl by remember { mutableStateOf("") }
    var isRunningDiagnostic by remember { mutableStateOf(false) }
    var diagnosticOutput by remember { mutableStateOf("") }
    var diagnosticJob by remember { mutableStateOf<Job?>(null) }

    // Filter plugins
    val filteredPlugins = remember(plugins, searchQuery) {
        if (searchQuery.isBlank()) plugins
        else {
            val q = searchQuery.trim().lowercase()
            plugins.filter {
                it.name.lowercase().contains(q) ||
                it.id.lowercase().contains(q) ||
                it.baseUrl.lowercase().contains(q) ||
                it.extraHosts.any { host -> host.lowercase().contains(q) }
            }
        }
    }

    val builtInPlugins = filteredPlugins.filter { it.isBuiltIn }
    val customPlugins = filteredPlugins.filter { !it.isBuiltIn }
    val catalogPresets = remember { pluginManager.getBuiltInCatalog() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sites & Add-ons Studio", maxLines = 1, overflow = TextDefaults.OVERFLOW) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLimitsDialog = true },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = "Download limits")
                    }
                    IconButton(
                        onClick = {
                            if (isUpdatingRemote) return@IconButton
                            isUpdatingRemote = true
                            coroutineScope.launch {
                                pluginManager.updateFromRemote()
                                    .onSuccess { summary ->
                                        val msg = when {
                                            summary.updated.isNotEmpty() -> "Updated: ${summary.updated.joinToString()}"
                                            summary.failed.isNotEmpty() -> "Some add-ons failed: ${summary.failed.joinToString()}"
                                            else -> "All site add-ons are up to date"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                    .onFailure { errorMessage = "Update check failed: ${it.message}" }
                                isUpdatingRemote = false
                            }
                        },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        if (isUpdatingRemote) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Update site add-ons")
                        }
                    }
                    IconButton(
                        onClick = {
                            inspectorInitialUrl = ""
                            showInspectorDialog = true
                        },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.TravelExplore, contentDescription = "Inspect Web Page")
                    }
                    IconButton(
                        onClick = { showTemplatePickerDialog = true },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add New Site")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top Tabs
            TabRow(selectedTabIndex = activeTopTab) {
                Tab(
                    selected = activeTopTab == 0,
                    onClick = { activeTopTab = 0 },
                    text = { Text("Installed (${plugins.size})") },
                    icon = { Icon(Icons.Default.Extension, contentDescription = null) }
                )
                Tab(
                    selected = activeTopTab == 1,
                    onClick = { activeTopTab = 1 },
                    text = { Text("Catalog & Presets") },
                    icon = { Icon(Icons.Default.AutoStories, contentDescription = null) }
                )
                Tab(
                    selected = activeTopTab == 2,
                    onClick = { activeTopTab = 2 },
                    text = { Text("Diagnostic Lab") },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) }
                )
            }

            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { errorMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            when (activeTopTab) {
                // Tab 0: Installed Sites
                0 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Search bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("Search installed sites by name or domain...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Section: Custom Sites
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "My Custom Add-ons (${customPlugins.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TextButton(onClick = { showTemplatePickerDialog = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("New Add-on")
                                    }
                                }
                            }

                            if (customPlugins.isEmpty()) {
                                item {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(Icons.Default.DesignServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("No custom add-ons yet", fontWeight = FontWeight.Bold)
                                            Text(
                                                "Tap '+ New Add-on' or pick from the Catalog to create tailored scrapers for any website.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(customPlugins, key = { it.id }) { plugin ->
                                    PluginCardItem(
                                        plugin = plugin,
                                        isBuiltIn = false,
                                        health = healthResults[plugin.id],
                                        onToggle = { enabled ->
                                            coroutineScope.launch { pluginManager.togglePlugin(plugin.id, enabled) }
                                        },
                                        onEdit = {
                                            studioConfigToEdit = plugin
                                            studioIsNew = false
                                            showStudioDialog = true
                                        },
                                        onDuplicate = {
                                            coroutineScope.launch {
                                                pluginManager.duplicatePlugin(plugin.id)
                                                    .onSuccess {
                                                        Toast.makeText(context, "Duplicated '${plugin.name}'", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .onFailure { errorMessage = it.message }
                                            }
                                        },
                                        onExport = {
                                            pendingExportPlugin = plugin
                                            exportLauncher.launch("${plugin.id}.json")
                                        },
                                        onCopyJson = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Plugin JSON", pluginManager.exportPluginJson(plugin)))
                                            Toast.makeText(context, "Copied JSON to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        onTest = {
                                            diagnosticUrl = "https://${plugin.baseUrl}"
                                            activeTopTab = 2
                                        },
                                        onDelete = { pluginToDelete = plugin }
                                    )
                                }
                            }

                            // Section: Built-in Site Engines
                            item {
                                Text(
                                    "Built-in Site Engines (${builtInPlugins.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }

                            items(builtInPlugins, key = { it.id }) { plugin ->
                                PluginCardItem(
                                    plugin = plugin,
                                    isBuiltIn = true,
                                    health = healthResults[plugin.id],
                                    onToggle = { enabled ->
                                        coroutineScope.launch { pluginManager.togglePlugin(plugin.id, enabled) }
                                    },
                                    onEdit = {
                                        coroutineScope.launch {
                                            pluginManager.forkBuiltIn(plugin.id)
                                                .onSuccess { forked ->
                                                    studioConfigToEdit = forked
                                                    studioIsNew = false
                                                    showStudioDialog = true
                                                }
                                                .onFailure { errorMessage = "Could not edit copy: ${it.message}" }
                                        }
                                    },
                                    onDuplicate = {
                                        coroutineScope.launch {
                                            pluginManager.duplicatePlugin(plugin.id)
                                                .onSuccess { Toast.makeText(context, "Cloned '${plugin.name}' to custom add-ons", Toast.LENGTH_SHORT).show() }
                                                .onFailure { errorMessage = it.message }
                                        }
                                    },
                                    onExport = {
                                        pendingExportPlugin = plugin
                                        exportLauncher.launch("${plugin.id}.json")
                                    },
                                    onCopyJson = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Plugin JSON", pluginManager.exportPluginJson(plugin)))
                                        Toast.makeText(context, "Copied JSON to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    onTest = {
                                        diagnosticUrl = "https://${plugin.baseUrl}"
                                        activeTopTab = 2
                                    },
                                    onDelete = {
                                        // Restores original if modified
                                        coroutineScope.launch {
                                            pluginManager.deletePlugin(plugin.id)
                                            Toast.makeText(context, "Restored original ${plugin.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Tab 1: Catalog & Presets Gallery
                1 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Curated Site Add-ons Gallery", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Pre-configured, battle-tested scrapers for top novel platforms. Tap 'Install / Customize' to customize any site in the Visual Studio.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        items(catalogPresets, key = { it.id }) { preset ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text(preset.baseUrl, fontSize = 11.sp) }
                                        )
                                    }

                                    if (preset.description.isNotBlank()) {
                                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    // Tags & features
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        if (preset.tocPaginationMode == "query") {
                                            AssistChip(onClick = {}, label = { Text("Paginated TOC", fontSize = 10.sp) })
                                        }
                                        if (preset.junkSelectors.isNotEmpty()) {
                                            AssistChip(onClick = {}, label = { Text("Ad Filtering", fontSize = 10.sp) })
                                        }
                                        if (preset.customTocJs != null) {
                                            AssistChip(onClick = {}, label = { Text("API Engine", fontSize = 10.sp) })
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                studioConfigToEdit = preset.copy(
                                                    id = "${preset.id}_custom",
                                                    name = "${preset.name} (Custom)",
                                                    isBuiltIn = false
                                                )
                                                studioIsNew = true
                                                showStudioDialog = true
                                            }
                                        ) {
                                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Customize in Studio")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    pluginManager.savePluginConfig(preset.copy(isBuiltIn = false))
                                                    Toast.makeText(context, "Installed ${preset.name} Add-on!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Install")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Tab 2: Diagnostic Lab
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SourceHealthCard(
                            results = plugins.filter { it.isEnabled }.mapNotNull { p -> healthResults[p.id]?.let { p to it } },
                            progress = healthProgress,
                            onCheckAll = {
                                healthJob?.cancel()
                                healthJob = coroutineScope.launch {
                                    val urls = libraryUrls()
                                    val targets = plugins.filter { it.isEnabled }
                                    targets.forEachIndexed { i, plugin ->
                                        healthProgress = "Checking ${plugin.name} (${i + 1}/${targets.size})…"
                                        val r = healthChecker.check(plugin, urls)
                                        healthResults = healthResults + (plugin.id to r)
                                    }
                                    healthProgress = null
                                }
                            },
                            onCancel = {
                                healthJob?.cancel()
                                healthProgress = null
                            }
                        )

                        Text("End-to-End Scraper Diagnostic Lab", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Validate any novel URL against all installed add-ons and scrapers. Tests metadata parsing, TOC link extraction, and first chapter scraping without saving to library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = diagnosticUrl,
                            onValueChange = { diagnosticUrl = it },
                            label = { Text("Novel or Chapter URL") },
                            placeholder = { Text("https://royalroad.com/fiction/12345/...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val url = diagnosticUrl.trim()
                                    if (url.isBlank()) return@Button
                                    isRunningDiagnostic = true
                                    diagnosticOutput = "=== STARTING SITE DIAGNOSTIC ===\nTarget URL: $url\n"

                                    diagnosticJob?.cancel()
                                    diagnosticJob = coroutineScope.launch(Dispatchers.IO) {
                                        var testWebView: WebView? = null
                                        try {
                                            withTimeout(180_000L) {
                                                val scraper = SourceManager.getSourceForUrl(url, pluginManager)
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "\n[STEP 1] Source Match: ${scraper.sourceName}\n"
                                                    diagnosticOutput += "\n[STEP 2] Scraping Metadata...\n"
                                                    testWebView = WebViewFactory.create(context)
                                                }

                                                val book = scraper.scrapeBookInfo(testWebView!!, url)
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "  • Title: ${book.title}\n  • Author: ${book.author}\n  • Cover: ${book.coverUrl ?: "none"}\n"
                                                    diagnosticOutput += "\n[STEP 3] Scraping Table of Contents (Chapter List)...\n"
                                                }

                                                val chapterUrls = scraper.scrapeChapterList(testWebView!!, url)
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "  • Total Chapters Found: ${chapterUrls.size}\n"
                                                    if (chapterUrls.isNotEmpty()) {
                                                        diagnosticOutput += "  • First 3 Chapters:\n"
                                                        chapterUrls.take(3).forEachIndexed { i, u -> diagnosticOutput += "    [${i+1}] $u\n" }
                                                    }
                                                }

                                                if (chapterUrls.isNotEmpty()) {
                                                    val firstCh = chapterUrls.first()
                                                    withContext(Dispatchers.Main) {
                                                        diagnosticOutput += "\n[STEP 4] Scraping Chapter Content ($firstCh)...\n"
                                                    }
                                                    val content = scraper.scrapeChapterContent(testWebView!!, firstCh) { false }
                                                    withContext(Dispatchers.Main) {
                                                        diagnosticOutput += "  • Chapter Title: ${content.first}\n"
                                                        diagnosticOutput += "  • Text Length: ${content.second.length} characters\n"
                                                        diagnosticOutput += "  • Snippet Preview:\n------------------------------------\n${content.second.take(300)}\n------------------------------------\n"
                                                        diagnosticOutput += "\n=== DIAGNOSTIC FINISHED SUCCESSFULLY ==="
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                diagnosticOutput += "\n[DIAGNOSTIC ERROR] ${e::class.simpleName}: ${e.message}\n"
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    testWebView?.stopLoading()
                                                    testWebView?.destroy()
                                                } catch (_: Exception) {}
                                                isRunningDiagnostic = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isRunningDiagnostic && diagnosticUrl.isNotBlank()
                            ) {
                                if (isRunningDiagnostic) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Testing...")
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Run Diagnostic")
                                }
                            }

                            if (isRunningDiagnostic) {
                                OutlinedButton(onClick = {
                                    diagnosticJob?.cancel()
                                    isRunningDiagnostic = false
                                    diagnosticOutput += "\n[Cancelled by user]\n"
                                }) { Text("Cancel") }
                            } else if (diagnosticOutput.isNotBlank()) {
                                TextButton(onClick = { diagnosticOutput = "" }) { Text("Clear Output") }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                SelectionContainer {
                                    Text(
                                        text = if (diagnosticOutput.isBlank()) "Enter a novel URL and tap 'Run Diagnostic'..." else diagnosticOutput,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLimitsDialog) {
        com.example.ui.components.DownloadLimitsDialog(pluginManager) { showLimitsDialog = false }
    }

    // Modal: Template Picker Dialog
    if (showTemplatePickerDialog) {
        AlertDialog(
            onDismissRequest = { showTemplatePickerDialog = false },
            title = { Text("Create New Site Add-on", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select a template to configure your add-on:", style = MaterialTheme.typography.bodySmall)

                    ListItem(
                        headlineContent = { Text("Standard Novel Site", fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("For single-page TOC sites with standard CSS selectors") },
                        leadingContent = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        trailingContent = {
                            Button(onClick = {
                                showTemplatePickerDialog = false
                                studioConfigToEdit = pluginManager.createDefaultPlugin("standard")
                                studioIsNew = true
                                showStudioDialog = true
                            }) { Text("Use") }
                        }
                    )

                    ListItem(
                        headlineContent = { Text("Paginated Table of Contents", fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("For novel sites with multi-page chapter lists (?page=1, 2, ...)") },
                        leadingContent = { Icon(Icons.Default.FilterList, contentDescription = null) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        trailingContent = {
                            Button(onClick = {
                                showTemplatePickerDialog = false
                                studioConfigToEdit = pluginManager.createDefaultPlugin("paginated")
                                studioIsNew = true
                                showStudioDialog = true
                            }) { Text("Use") }
                        }
                    )

                    ListItem(
                        headlineContent = { Text("Single-Page App / JS Heavy", fontWeight = FontWeight.Bold) },
                        supportingContent = { Text("For client-rendered sites requiring content readiness probes") },
                        leadingContent = { Icon(Icons.Default.Javascript, contentDescription = null) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        trailingContent = {
                            Button(onClick = {
                                showTemplatePickerDialog = false
                                studioConfigToEdit = pluginManager.createDefaultPlugin("spa")
                                studioIsNew = true
                                showStudioDialog = true
                            }) { Text("Use") }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(onClick = {
                            showTemplatePickerDialog = false
                            filePicker.launch(arrayOf("application/json", "text/plain"))
                        }) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import File")
                        }
                        OutlinedButton(onClick = {
                            showTemplatePickerDialog = false
                            pasteJsonText = ""
                            showPasteJsonDialog = true
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste JSON")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTemplatePickerDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Modal: Paste JSON Dialog
    if (showPasteJsonDialog) {
        AlertDialog(
            onDismissRequest = { showPasteJsonDialog = false },
            title = { Text("Paste Add-on JSON") },
            text = {
                OutlinedTextField(
                    value = pasteJsonText,
                    onValueChange = { pasteJsonText = it },
                    label = { Text("JSON String") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        pluginManager.savePlugin(pasteJsonText)
                            .onSuccess {
                                showPasteJsonDialog = false
                                Toast.makeText(context, "Installed '${it.name}' add-on", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { errorMessage = it.message }
                    }
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteJsonDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Modal: Plugin Studio Dialog (Interactive Form + JSON Editor + Live Tester)
    if (showStudioDialog && studioConfigToEdit != null) {
        PluginStudioDialog(
            initialConfig = studioConfigToEdit!!,
            isNewPlugin = studioIsNew,
            pluginManager = pluginManager,
            onDismiss = {
                showStudioDialog = false
                studioConfigToEdit = null
            },
            onSave = { updated ->
                coroutineScope.launch {
                    pluginManager.savePluginConfig(updated)
                        .onSuccess {
                            showStudioDialog = false
                            studioConfigToEdit = null
                            Toast.makeText(context, "Saved '${updated.name}' add-on!", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { errorMessage = "Save failed: ${it.message}" }
                }
            }
        )
    }

    // Modal: Live Web Inspector & Selector Lab
    if (showInspectorDialog) {
        WebInspectorDialog(
            initialUrl = inspectorInitialUrl,
            onDismiss = { showInspectorDialog = false },
            onCreatePluginFromInspector = { prefilled ->
                showInspectorDialog = false
                studioConfigToEdit = prefilled
                studioIsNew = true
                showStudioDialog = true
            }
        )
    }

    // Modal: Confirm Delete Dialog
    if (pluginToDelete != null) {
        AlertDialog(
            onDismissRequest = { pluginToDelete = null },
            title = { Text("Delete Add-on") },
            text = { Text("Are you sure you want to delete '${pluginToDelete?.name}'? If this was a built-in add-on, its factory version will be restored.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pluginToDelete
                        if (target != null) {
                            coroutineScope.launch {
                                pluginManager.deletePlugin(target.id)
                                pluginToDelete = null
                                Toast.makeText(context, "Deleted ${target.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pluginToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PluginCardItem(
    plugin: PluginConfig,
    isBuiltIn: Boolean,
    health: SourceHealthChecker.Result? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onCopyJson: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("v${plugin.version}", fontSize = 10.sp) },
                        modifier = Modifier.height(22.dp)
                    )
                }

                Switch(checked = plugin.isEnabled, onCheckedChange = onToggle)
            }

            Text(
                "Domain: ${plugin.baseUrl}" + if (plugin.extraHosts.isNotEmpty()) " (${plugin.extraHosts.joinToString()})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            if (health != null) {
                HealthLine(health)
            }

            if (plugin.description.isNotBlank()) {
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Quick Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", fontSize = 12.sp)
                    }

                    TextButton(onClick = onTest, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test", fontSize = 12.sp)
                    }

                    TextButton(onClick = onDuplicate, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clone", fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onExport, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export JSON", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onCopyJson, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Code, contentDescription = "Copy JSON", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (isBuiltIn) Icons.Default.Undo else Icons.Default.Delete,
                            contentDescription = if (isBuiltIn) "Restore" else "Delete",
                            tint = if (isBuiltIn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthLine(result: SourceHealthChecker.Result) {
    val (label, color) = when (result.status) {
        SourceHealthChecker.Status.OK -> "✅ Working" to MaterialTheme.colorScheme.primary
        SourceHealthChecker.Status.BLOCKED -> "🛡️ Needs verification" to MaterialTheme.colorScheme.tertiary
        SourceHealthChecker.Status.BROKEN -> "❌ Broken at ${result.stage}" to MaterialTheme.colorScheme.error
        SourceHealthChecker.Status.NO_TEST_URL -> "➖ Not tested" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ago = android.text.format.DateUtils.getRelativeTimeSpanString(result.checkedAt).toString()
    Column {
        Text("$label · $ago", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        if (result.status != SourceHealthChecker.Status.OK && result.message.isNotBlank()) {
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SourceHealthCard(
    results: List<Pair<PluginConfig, SourceHealthChecker.Result>>,
    progress: String?,
    onCheckAll: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Source Health", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tests every enabled site against a known book (or one from your library).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress == null) {
                    Button(onClick = onCheckAll) { Text("Check all") }
                } else {
                    OutlinedButton(onClick = onCancel) { Text("Stop") }
                }
            }
            if (progress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (results.isNotEmpty()) {
                val ok = results.count { it.second.status == SourceHealthChecker.Status.OK }
                Text("$ok of ${results.size} checked sites working", style = MaterialTheme.typography.labelMedium)
                results.filter { it.second.status != SourceHealthChecker.Status.OK }.forEach { (plugin, r) ->
                    Text("• ${plugin.name}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    HealthLine(r)
                }
            }
        }
    }
}
