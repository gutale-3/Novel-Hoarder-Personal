package com.example.ui.screens

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginManager
import com.example.data.scraper.SourceManager
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.TextDefaults
import com.example.ui.theme.screenContentPadding
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val plugins by pluginManager.plugins.collectAsState()

    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteJsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pluginToDelete by remember { mutableStateOf<PluginConfig?>(null) }
    var showHelpCard by remember { mutableStateOf(false) }

    // Inspect Page tool state
    var showInspectDialog by remember { mutableStateOf(false) }
    var inspectUrl by remember { mutableStateOf("https://wtr-lab.com") }
    var isInspecting by remember { mutableStateOf(false) }
    var inspectResultText by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Diagnostic Site Tester state
    var diagnosticUrl by remember { mutableStateOf("") }
    var isRunningDiagnostic by remember { mutableStateOf(false) }
    var diagnosticOutput by remember { mutableStateOf("") }
    var diagnosticJob by remember { mutableStateOf<Job?>(null) }

    val builtInIds = remember {
        try {
            context.assets.list("plugins").orEmpty()
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // Partition plugins
    val builtInPlugins = plugins.filter { builtInIds.contains(it.id) }
    val myPlugins = plugins.filter { !builtInIds.contains(it.id) }

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
                            Toast.makeText(context, "Plugin '${it.name}' installed", Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sites & Plugins", maxLines = 1, overflow = TextDefaults.OVERFLOW) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showInspectDialog = true },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "Inspect Page")
                    }
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import Plugin File")
                    }
                    IconButton(
                        onClick = { showPasteDialog = true },
                        modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Paste Plugin JSON")
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
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "How to write a plugin",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showHelpCard = !showHelpCard }) {
                                Icon(
                                    if (showHelpCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle instructions"
                                )
                            }
                        }
                        AnimatedVisibility(visible = showHelpCard) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    "Plugins define CSS selectors for novel websites. Example JSON:\n",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    """
                                    {
                                      "id": "my_site",
                                      "name": "My Novel Site",
                                      "version": "1.0.0",
                                      "baseUrl": "example.com",
                                      "author": "User",
                                      "titleSelector": "h1.book-title",
                                      "authorSelector": ".author-name",
                                      "synopsisSelector": ".description",
                                      "chapterListSelector": "a.chapter-link",
                                      "chapterTitleSelector": "h1.chap-title",
                                      "chapterBodySelector": ".chapter-content"
                                    }
                                    """.trimIndent(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Site Diagnostic Tester",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Test any novel URL to check scraper detection, metadata, chapter list, and first chapter extraction without saving anything to the library.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = diagnosticUrl,
                            onValueChange = { diagnosticUrl = it },
                            label = { Text("Novel or Chapter URL") },
                            placeholder = { Text("https://example.com/novel/...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                            withTimeout(180_000L) { // 3-minute overall timeout
                                                // 1. Resolve source
                                                val scraper = SourceManager.getSourceForUrl(url, pluginManager)
                                                val scraperType = when (scraper) {
                                                    is com.example.util.TomatoScraper -> "TomatoMTL"
                                                    is com.example.data.plugin.PluginExecutionEngine -> "Plugin (${scraper.config.name})"
                                                    else -> "Universal Web Scraper"
                                                }
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "\n[STEP 1] Source Resolution:\n  Scraper Chosen: $scraperType\n  Source Name: ${scraper.sourceName}\n"
                                                }

                                                // 2. Create WebView & scrapeBookInfo
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "\n[STEP 2] Scraping Book Metadata...\n"
                                                }
                                                testWebView = withContext(Dispatchers.Main) {
                                                    WebViewFactory.create(context)
                                                }
                                                val bookInfo = scraper.scrapeBookInfo(testWebView!!, url)
                                                val hasCover = !bookInfo.coverUrl.isNullOrBlank()
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "  Title: ${bookInfo.title}\n  Author: ${bookInfo.author}\n  Cover URL Found: $hasCover (${bookInfo.coverUrl ?: "none"})\n  Parsed Book ID: ${bookInfo.id}\n"
                                                }

                                                // 3. scrapeChapterList
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "\n[STEP 3] Scraping Chapter List (TOC)...\n"
                                                }
                                                val chapterUrls = scraper.scrapeChapterList(testWebView!!, url)
                                                withContext(Dispatchers.Main) {
                                                    diagnosticOutput += "  Total Chapter URLs: ${chapterUrls.size}\n"
                                                    if (chapterUrls.isNotEmpty()) {
                                                        diagnosticOutput += "  First up to 3 URLs:\n"
                                                        chapterUrls.take(3).forEachIndexed { i, u ->
                                                            diagnosticOutput += "    [${i + 1}] $u\n"
                                                        }
                                                        if (chapterUrls.size > 3) {
                                                            diagnosticOutput += "  Last up to 3 URLs:\n"
                                                            val lastThree = chapterUrls.takeLast(3)
                                                            val startIdx = chapterUrls.size - lastThree.size + 1
                                                            lastThree.forEachIndexed { i, u ->
                                                                diagnosticOutput += "    [${startIdx + i}] $u\n"
                                                            }
                                                        }
                                                    }
                                                }

                                                // 4. scrapeChapterContent on first chapter
                                                if (chapterUrls.isNotEmpty()) {
                                                    val firstChapUrl = chapterUrls.first()
                                                    withContext(Dispatchers.Main) {
                                                        diagnosticOutput += "\n[STEP 4] Scraping First Chapter Content ($firstChapUrl)...\n"
                                                    }
                                                    val rawContent = scraper.scrapeChapterContent(testWebView!!, firstChapUrl) { false }
                                                    val chapTitle = rawContent.first
                                                    val chapBody = rawContent.second
                                                    val preview = chapBody.take(300)
                                                    withContext(Dispatchers.Main) {
                                                        diagnosticOutput += "  Chapter Title: $chapTitle\n"
                                                        diagnosticOutput += "  Body Length: ${chapBody.length} characters\n"
                                                        diagnosticOutput += "  Body Preview (First 300 chars):\n----------------------------------------\n$preview\n----------------------------------------\n"
                                                        diagnosticOutput += "\n=== DIAGNOSTIC FINISHED SUCCESSFULLY ==="
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        diagnosticOutput += "\n[STEP 4] Skipped (No chapters returned from TOC)\n"
                                                        diagnosticOutput += "\n=== DIAGNOSTIC FINISHED ==="
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            val exClass = e::class.qualifiedName ?: e::class.java.name
                                            val exMsg = e.message ?: "No error message"
                                            withContext(Dispatchers.Main) {
                                                diagnosticOutput += "\n[DIAGNOSTIC ERROR / EXCEPTION]\nClass: $exClass\nMessage: $exMsg\n"
                                                e.cause?.let { cause ->
                                                    diagnosticOutput += "Cause: ${cause::class.qualifiedName ?: cause::class.java.name}: ${cause.message}\n"
                                                }
                                                diagnosticOutput += "\n=== DIAGNOSTIC TERMINATED WITH ERROR ==="
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
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Testing...")
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Test this site")
                                }
                            }

                            if (isRunningDiagnostic) {
                                OutlinedButton(
                                    onClick = {
                                        diagnosticJob?.cancel()
                                        isRunningDiagnostic = false
                                        diagnosticOutput += "\n[CANCELLED BY USER]\n"
                                    }
                                ) {
                                    Text("Cancel")
                                }
                            } else if (diagnosticOutput.isNotBlank()) {
                                TextButton(
                                    onClick = { diagnosticOutput = "" }
                                ) {
                                    Text("Clear Output")
                                }
                            }
                        }

                        if (diagnosticOutput.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Diagnostic Results (Selectable):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = diagnosticOutput,
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

            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
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
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { errorMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }

            // Built-in Sites Section
            item {
                Text(
                    "Built-in Sites",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (builtInPlugins.isEmpty()) {
                item {
                    Text(
                        "No built-in plugins found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(builtInPlugins, key = { it.id }) { plugin ->
                    val isForked = !plugin.isBuiltIn
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        plugin.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextDefaults.OVERFLOW
                                    )
                                    if (isForked) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text("Edited", fontSize = 10.sp) },
                                            modifier = Modifier.height(20.dp)
                                        )
                                    }
                                }
                                Text(
                                    "Base URL: ${plugin.baseUrl} | v${plugin.version} by ${plugin.author}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (plugin.description.isNotBlank()) {
                                    Text(
                                        plugin.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (isForked) {
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    pluginManager.deletePlugin(plugin.id)
                                                    Toast.makeText(context, "Restored original ${plugin.name}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Restore original", fontSize = 12.sp)
                                        }
                                    } else {
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    pluginManager.forkBuiltIn(plugin.id)
                                                        .onSuccess {
                                                            Toast.makeText(context, "Created editable copy of ${plugin.name}", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .onFailure {
                                                            errorMessage = "Fork failed: ${it.message}"
                                                        }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Edit a copy", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Switch(
                                checked = plugin.isEnabled,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch { pluginManager.togglePlugin(plugin.id, enabled) }
                                }
                            )
                        }
                    }
                }
            }

            // My Sites Section
            item {
                Text(
                    "My Sites",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            if (myPlugins.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No custom sites installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(myPlugins, key = { it.id }) { plugin ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    plugin.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextDefaults.OVERFLOW
                                )
                                Text(
                                    "Base URL: ${plugin.baseUrl} | v${plugin.version} by ${plugin.author}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (plugin.description.isNotBlank()) {
                                    Text(
                                        plugin.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = plugin.isEnabled,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch { pluginManager.togglePlugin(plugin.id, enabled) }
                                }
                            )
                            IconButton(
                                onClick = { pluginToDelete = plugin },
                                modifier = Modifier.defaultMinSize(minHeight = MinTouchTarget)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete plugin")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste Plugin JSON") },
            text = {
                OutlinedTextField(
                    value = pasteJsonText,
                    onValueChange = { pasteJsonText = it },
                    label = { Text("Plugin JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 10
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            val result = pluginManager.savePlugin(pasteJsonText)
                            result.onSuccess {
                                showPasteDialog = false
                                pasteJsonText = ""
                                Toast.makeText(context, "Plugin '${it.name}' installed", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                errorMessage = it.message
                            }
                        }
                    }
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (pluginToDelete != null) {
        AlertDialog(
            onDismissRequest = { pluginToDelete = null },
            title = { Text("Delete Plugin") },
            text = { Text("Are you sure you want to delete '${pluginToDelete?.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pluginToDelete
                        if (target != null) {
                            coroutineScope.launch {
                                pluginManager.deletePlugin(target.id)
                                pluginToDelete = null
                            }
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pluginToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Inspect Page Debug Tool Dialog
    if (showInspectDialog) {
        AlertDialog(
            onDismissRequest = { showInspectDialog = false },
            title = { Text("Inspect WTR Lab / SPA Metadata") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Enter the WTR Lab book URL below to run the Next.js '__NEXT_DATA__' inspector tool.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = inspectUrl,
                        onValueChange = { inspectUrl = it },
                        label = { Text("URL to inspect") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isInspecting) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Loading page & extracting...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (inspectResultText.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Inspector Output:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = inspectResultText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Hidden WebView to perform the actual load and JavaScript execution
                    Box(modifier = Modifier.size(1.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            val inspectScript = """
                                                (() => {
                                                  const el = document.getElementById('__NEXT_DATA__');
                                                  if (!el) return JSON.stringify({ found: false, reason: 'no __NEXT_DATA__ script tag' });
                                                  let data;
                                                  try { data = JSON.parse(el.textContent); }
                                                  catch (e) { return JSON.stringify({ found: false, reason: 'not valid JSON' }); }

                                                  const hits = [];
                                                  const walk = (node, path, depth) => {
                                                    if (depth > 8 || node === null || typeof node !== 'object') return;
                                                    for (const k of Object.keys(node)) {
                                                      const v = node[k];
                                                      const p = path + '.' + k;
                                                      if (/^(raw_id|rawId|serie_id|serieId|id|slug)$/.test(k) &&
                                                          (typeof v === 'number' || typeof v === 'string')) {
                                                        hits.push(p + ' = ' + v);
                                                      }
                                                      if (Array.isArray(v) && v.length && typeof v[0] === 'object' &&
                                                          v[0] !== null && ('order' in v[0] || 'title' in v[0] || 'name' in v[0])) {
                                                        hits.push(p + ' [] len=' + v.length + ' keys=' + Object.keys(v[0]).join(','));
                                                      }
                                                      walk(v, p, depth + 1);
                                                    }
                                                  };
                                                  walk(data, '$', 0);
                                                  return JSON.stringify({ found: true, path: location.pathname, hits: hits.slice(0, 60) });
                                                })()
                                            """.trimIndent()

                                            view?.evaluateJavascript(inspectScript) { evalResult ->
                                                coroutineScope.launch {
                                                    isInspecting = false
                                                    inspectResultText = if (evalResult.isNullOrBlank() || evalResult == "null") {
                                                        "Error: Returned empty or null output."
                                                    } else {
                                                        // Pretty format
                                                        evalResult.replace("\\n", "\n")
                                                            .replace("\\\"", "\"")
                                                            .trim('\"')
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    webViewInstance = this
                                }
                            },
                            update = { /* handled dynamically */ }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isInspecting = true
                        inspectResultText = ""
                        webViewInstance?.loadUrl(inspectUrl)
                    },
                    enabled = !isInspecting && inspectUrl.isNotBlank()
                ) {
                    Text("Inspect")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInspectDialog = false
                        isInspecting = false
                        inspectResultText = ""
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}
