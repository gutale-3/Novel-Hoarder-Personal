package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginExecutionEngine
import com.example.data.plugin.PluginManager
import com.example.util.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginStudioDialog(
    initialConfig: PluginConfig,
    isNewPlugin: Boolean,
    pluginManager: PluginManager,
    onDismiss: () -> Unit,
    onSave: (PluginConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Visual Builder, 1 = JSON Code, 2 = Live Tester

    // Form states
    var id by remember { mutableStateOf(initialConfig.id) }
    var name by remember { mutableStateOf(initialConfig.name) }
    var version by remember { mutableStateOf(initialConfig.version) }
    var baseUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var extraHostsText by remember { mutableStateOf(initialConfig.extraHosts.joinToString(", ")) }
    var author by remember { mutableStateOf(initialConfig.author) }
    var description by remember { mutableStateOf(initialConfig.description) }

    // Selectors
    var titleSelector by remember { mutableStateOf(initialConfig.titleSelector) }
    var authorSelector by remember { mutableStateOf(initialConfig.authorSelector) }
    var synopsisSelector by remember { mutableStateOf(initialConfig.synopsisSelector) }
    var coverSelector by remember { mutableStateOf(initialConfig.coverSelector) }

    // Chapter List & TOC
    var chapterListSelector by remember { mutableStateOf(initialConfig.chapterListSelector) }
    var reverseOrder by remember { mutableStateOf(initialConfig.reverseOrder) }
    var linkFilterRegex by remember { mutableStateOf(initialConfig.chapterLinkFilterRegex) }
    var linkExcludeRegex by remember { mutableStateOf(initialConfig.chapterLinkExcludeRegex) }
    var tocPaginationMode by remember { mutableStateOf(initialConfig.tocPaginationMode) }
    var tocPageParam by remember { mutableStateOf(initialConfig.tocPageParam) }
    var tocFirstPage by remember { mutableIntStateOf(initialConfig.tocFirstPage) }
    var tocLastPageSelector by remember { mutableStateOf(initialConfig.tocLastPageSelector) }
    var tocMaxPages by remember { mutableIntStateOf(initialConfig.tocMaxPages) }

    // Chapter Reader Content
    var chapterTitleSelector by remember { mutableStateOf(initialConfig.chapterTitleSelector) }
    var chapterBodySelector by remember { mutableStateOf(initialConfig.chapterBodySelector) }
    var chapterReadySelector by remember { mutableStateOf(initialConfig.chapterReadySelector) }
    var junkSelectorsText by remember { mutableStateOf(initialConfig.junkSelectors.joinToString(", ")) }
    var requestDelayMs by remember { mutableIntStateOf(initialConfig.requestDelayMs) }
    var customUserAgent by remember { mutableStateOf(initialConfig.customUserAgent) }

    // Advanced Scripts
    var customJs by remember { mutableStateOf(initialConfig.customJs ?: "") }
    var customTocJs by remember { mutableStateOf(initialConfig.customTocJs ?: "") }
    var customChapterJs by remember { mutableStateOf(initialConfig.customChapterJs ?: "") }

    // JSON editor text state
    var jsonEditorText by remember { mutableStateOf(pluginManager.exportPluginJson(initialConfig)) }
    var jsonError by remember { mutableStateOf<String?>(null) }

    // Live Tester state
    var testUrl by remember {
        mutableStateOf(
            if (initialConfig.baseUrl.isNotBlank() && initialConfig.baseUrl != "example.com") {
                "https://${initialConfig.baseUrl}"
            } else ""
        )
    }
    var isTesting by remember { mutableStateOf(false) }
    var testLogs by remember { mutableStateOf("") }
    var testBookCover by remember { mutableStateOf<String?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }

    // Fields the editor doesn't show (search, browse, language, TOC URL template, test URL,
    // enabled state…) are carried over from here instead of being reset to defaults.
    var baseConfig by remember { mutableStateOf(initialConfig) }

    fun buildCurrentConfig(): PluginConfig {
        val extraHosts = extraHostsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val junkList = junkSelectorsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return baseConfig.copy(
            id = id.trim(),
            name = name.trim(),
            version = version.trim().ifBlank { "1.0.0" },
            baseUrl = baseUrl.trim(),
            author = author.trim().ifBlank { "User" },
            description = description.trim(),
            titleSelector = titleSelector.trim(),
            authorSelector = authorSelector.trim(),
            synopsisSelector = synopsisSelector.trim(),
            coverSelector = coverSelector.trim(),
            chapterListSelector = chapterListSelector.trim(),
            chapterTitleSelector = chapterTitleSelector.trim(),
            chapterBodySelector = chapterBodySelector.trim(),
            customJs = customJs.trim().ifBlank { null },
            extraHosts = extraHosts,
            tocPaginationMode = tocPaginationMode,
            tocPageParam = tocPageParam.trim(),
            tocFirstPage = tocFirstPage,
            tocLastPageSelector = tocLastPageSelector.trim(),
            tocMaxPages = tocMaxPages,
            customTocJs = customTocJs.trim().ifBlank { null },
            customChapterJs = customChapterJs.trim().ifBlank { null },
            chapterReadySelector = chapterReadySelector.trim(),
            requestDelayMs = requestDelayMs,
            junkSelectors = junkList,
            reverseOrder = reverseOrder,
            chapterLinkFilterRegex = linkFilterRegex.trim(),
            chapterLinkExcludeRegex = linkExcludeRegex.trim(),
            customUserAgent = customUserAgent.trim(),
            isBuiltIn = false
        )
    }

    fun syncFromCurrentConfig() {
        val cfg = buildCurrentConfig()
        jsonEditorText = pluginManager.exportPluginJson(cfg)
    }

    fun syncFromJson(jsonStr: String): Boolean {
        return try {
            val cfg = pluginManager.validatePluginJson(jsonStr)
            baseConfig = cfg
            id = cfg.id
            name = cfg.name
            version = cfg.version
            baseUrl = cfg.baseUrl
            extraHostsText = cfg.extraHosts.joinToString(", ")
            author = cfg.author
            description = cfg.description
            titleSelector = cfg.titleSelector
            authorSelector = cfg.authorSelector
            synopsisSelector = cfg.synopsisSelector
            coverSelector = cfg.coverSelector
            chapterListSelector = cfg.chapterListSelector
            chapterTitleSelector = cfg.chapterTitleSelector
            chapterBodySelector = cfg.chapterBodySelector
            customJs = cfg.customJs ?: ""
            tocPaginationMode = cfg.tocPaginationMode
            tocPageParam = cfg.tocPageParam
            tocFirstPage = cfg.tocFirstPage
            tocLastPageSelector = cfg.tocLastPageSelector
            tocMaxPages = cfg.tocMaxPages
            customTocJs = cfg.customTocJs ?: ""
            customChapterJs = cfg.customChapterJs ?: ""
            chapterReadySelector = cfg.chapterReadySelector
            requestDelayMs = cfg.requestDelayMs
            junkSelectorsText = cfg.junkSelectors.joinToString(", ")
            reverseOrder = cfg.reverseOrder
            linkFilterRegex = cfg.chapterLinkFilterRegex
            linkExcludeRegex = cfg.chapterLinkExcludeRegex
            customUserAgent = cfg.customUserAgent
            jsonError = null
            true
        } catch (e: Exception) {
            jsonError = e.message ?: "Invalid JSON"
            false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (isNewPlugin) "Create Site Add-on" else "Edit Add-on: $name",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                if (baseUrl.isNotBlank()) baseUrl else "Novel Website Plugin",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                if (activeTab == 1) {
                                    if (!syncFromJson(jsonEditorText)) {
                                        Toast.makeText(context, "Fix JSON errors before saving", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                }
                                val configToSave = buildCurrentConfig()
                                if (configToSave.id.isBlank() || configToSave.name.isBlank() || configToSave.baseUrl.isBlank()) {
                                    Toast.makeText(context, "ID, Name, and Base URL are required.", Toast.LENGTH_SHORT).show()
                                    activeTab = 0
                                    return@Button
                                }
                                onSave(configToSave)
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save")
                        }
                    }
                )

                // Tabs: Visual Builder, JSON Code, Live Tester
                TabRow(selectedTabIndex = activeTab) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = {
                            if (activeTab == 1) syncFromJson(jsonEditorText)
                            activeTab = 0
                        },
                        text = { Text("Visual Studio") },
                        icon = { Icon(Icons.Default.Build, contentDescription = null) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = {
                            syncFromCurrentConfig()
                            activeTab = 1
                        },
                        text = { Text("JSON Code") },
                        icon = { Icon(Icons.Default.Code, contentDescription = null) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = {
                            if (activeTab == 1) syncFromJson(jsonEditorText)
                            activeTab = 2
                        },
                        text = { Text("Live Tester") },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                    )
                }

                // Tab Contents
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (activeTab) {
                        0 -> VisualBuilderTab(
                            id = id, onIdChange = { id = it },
                            name = name, onNameChange = { name = it },
                            baseUrl = baseUrl, onBaseUrlChange = { baseUrl = it },
                            extraHostsText = extraHostsText, onExtraHostsChange = { extraHostsText = it },
                            author = author, onAuthorChange = { author = it },
                            version = version, onVersionChange = { version = it },
                            description = description, onDescriptionChange = { description = it },
                            titleSelector = titleSelector, onTitleSelectorChange = { titleSelector = it },
                            authorSelector = authorSelector, onAuthorSelectorChange = { authorSelector = it },
                            synopsisSelector = synopsisSelector, onSynopsisSelectorChange = { synopsisSelector = it },
                            coverSelector = coverSelector, onCoverSelectorChange = { coverSelector = it },
                            chapterListSelector = chapterListSelector, onChapterListSelectorChange = { chapterListSelector = it },
                            reverseOrder = reverseOrder, onReverseOrderChange = { reverseOrder = it },
                            linkFilterRegex = linkFilterRegex, onLinkFilterRegexChange = { linkFilterRegex = it },
                            linkExcludeRegex = linkExcludeRegex, onLinkExcludeRegexChange = { linkExcludeRegex = it },
                            tocPaginationMode = tocPaginationMode, onTocPaginationModeChange = { tocPaginationMode = it },
                            tocPageParam = tocPageParam, onTocPageParamChange = { tocPageParam = it },
                            tocFirstPage = tocFirstPage, onTocFirstPageChange = { tocFirstPage = it },
                            tocLastPageSelector = tocLastPageSelector, onTocLastPageSelectorChange = { tocLastPageSelector = it },
                            tocMaxPages = tocMaxPages, onTocMaxPagesChange = { tocMaxPages = it },
                            chapterTitleSelector = chapterTitleSelector, onChapterTitleSelectorChange = { chapterTitleSelector = it },
                            chapterBodySelector = chapterBodySelector, onChapterBodySelectorChange = { chapterBodySelector = it },
                            chapterReadySelector = chapterReadySelector, onChapterReadySelectorChange = { chapterReadySelector = it },
                            junkSelectorsText = junkSelectorsText, onJunkSelectorsChange = { junkSelectorsText = it },
                            requestDelayMs = requestDelayMs, onRequestDelayChange = { requestDelayMs = it },
                            customUserAgent = customUserAgent, onCustomUserAgentChange = { customUserAgent = it }
                        )

                        1 -> JsonEditorTab(
                            jsonText = jsonEditorText,
                            onJsonTextChange = {
                                jsonEditorText = it
                                jsonError = null
                            },
                            errorMessage = jsonError,
                            onFormat = {
                                try {
                                    val cfg = pluginManager.validatePluginJson(jsonEditorText)
                                    jsonEditorText = pluginManager.exportPluginJson(cfg)
                                    jsonError = null
                                    Toast.makeText(context, "Formatted JSON", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    jsonError = e.message
                                }
                            },
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Plugin JSON", jsonEditorText))
                                Toast.makeText(context, "Copied JSON to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )

                        2 -> LiveTesterTab(
                            testUrl = testUrl,
                            onTestUrlChange = { testUrl = it },
                            isTesting = isTesting,
                            testLogs = testLogs,
                            testBookCover = testBookCover,
                            onStartTest = {
                                val url = testUrl.trim()
                                if (url.isBlank()) return@LiveTesterTab
                                isTesting = true
                                testLogs = "=== STARTING LIVE PLUGIN TEST ===\nURL: $url\n"
                                testBookCover = null

                                val currentConfig = buildCurrentConfig()
                                testJob?.cancel()
                                testJob = coroutineScope.launch(Dispatchers.IO) {
                                    var testWebView: WebView? = null
                                    try {
                                        withTimeout(120_000L) {
                                            val engine = PluginExecutionEngine(currentConfig)
                                            withContext(Dispatchers.Main) {
                                                testLogs += "Scraper Engine: ${currentConfig.name} (Plugin ID: ${currentConfig.id})\n"
                                                testLogs += "\n[TEST 1] Extracting Book Info...\n"
                                                testWebView = WebViewFactory.create(context)
                                            }

                                            val book = engine.scrapeBookInfo(testWebView!!, url)
                                            withContext(Dispatchers.Main) {
                                                testLogs += "  ✓ Title: ${book.title}\n"
                                                testLogs += "  ✓ Author: ${book.author}\n"
                                                testLogs += "  ✓ Synopsis: ${book.synopsis.take(120)}...\n"
                                                testLogs += "  ✓ Cover URL: ${book.coverUrl ?: "None found"}\n"
                                                testBookCover = book.coverUrl
                                                testLogs += "\n[TEST 2] Scraping Chapter List (TOC)...\n"
                                            }

                                            val chapters = engine.scrapeChapterList(testWebView!!, url)
                                            withContext(Dispatchers.Main) {
                                                testLogs += "  ✓ Total Chapters Extracted: ${chapters.size}\n"
                                                if (chapters.isNotEmpty()) {
                                                    testLogs += "    First: ${chapters.first()}\n"
                                                    if (chapters.size > 1) {
                                                        testLogs += "    Last:  ${chapters.last()}\n"
                                                    }
                                                    testLogs += "\n[TEST 3] Scraping Chapter 1 Content...\n"
                                                } else {
                                                    testLogs += "  ⚠️ Warning: No chapters found. Check chapterListSelector.\n"
                                                }
                                            }

                                            if (chapters.isNotEmpty()) {
                                                val chContent = engine.scrapeChapterContent(testWebView!!, chapters.first()) { false }
                                                withContext(Dispatchers.Main) {
                                                    testLogs += "  ✓ Chapter Title: ${chContent.first}\n"
                                                    testLogs += "  ✓ Clean Body Length: ${chContent.second.length} characters\n"
                                                    testLogs += "  ✓ Preview (first 250 chars):\n------------------------------------\n"
                                                    testLogs += chContent.second.take(250) + "...\n------------------------------------\n"
                                                    testLogs += "\n🎉 ALL TESTS COMPLETED SUCCESSFULLY! Configuration is working."
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            testLogs += "\n❌ TEST FAILED: ${e::class.simpleName}: ${e.message}\n"
                                        }
                                    } finally {
                                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                                            try {
                                                testWebView?.stopLoading()
                                                testWebView?.destroy()
                                            } catch (_: Exception) {}
                                            isTesting = false
                                        }
                                    }
                                }
                            },
                            onCancelTest = {
                                testJob?.cancel()
                                isTesting = false
                                testLogs += "\n[Test cancelled by user]\n"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualBuilderTab(
    id: String, onIdChange: (String) -> Unit,
    name: String, onNameChange: (String) -> Unit,
    baseUrl: String, onBaseUrlChange: (String) -> Unit,
    extraHostsText: String, onExtraHostsChange: (String) -> Unit,
    author: String, onAuthorChange: (String) -> Unit,
    version: String, onVersionChange: (String) -> Unit,
    description: String, onDescriptionChange: (String) -> Unit,
    titleSelector: String, onTitleSelectorChange: (String) -> Unit,
    authorSelector: String, onAuthorSelectorChange: (String) -> Unit,
    synopsisSelector: String, onSynopsisSelectorChange: (String) -> Unit,
    coverSelector: String, onCoverSelectorChange: (String) -> Unit,
    chapterListSelector: String, onChapterListSelectorChange: (String) -> Unit,
    reverseOrder: Boolean, onReverseOrderChange: (Boolean) -> Unit,
    linkFilterRegex: String, onLinkFilterRegexChange: (String) -> Unit,
    linkExcludeRegex: String, onLinkExcludeRegexChange: (String) -> Unit,
    tocPaginationMode: String, onTocPaginationModeChange: (String) -> Unit,
    tocPageParam: String, onTocPageParamChange: (String) -> Unit,
    tocFirstPage: Int, onTocFirstPageChange: (Int) -> Unit,
    tocLastPageSelector: String, onTocLastPageSelectorChange: (String) -> Unit,
    tocMaxPages: Int, onTocMaxPagesChange: (Int) -> Unit,
    chapterTitleSelector: String, onChapterTitleSelectorChange: (String) -> Unit,
    chapterBodySelector: String, onChapterBodySelectorChange: (String) -> Unit,
    chapterReadySelector: String, onChapterReadySelectorChange: (String) -> Unit,
    junkSelectorsText: String, onJunkSelectorsChange: (String) -> Unit,
    requestDelayMs: Int, onRequestDelayChange: (Int) -> Unit,
    customUserAgent: String, onCustomUserAgentChange: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Basic Site Details
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Site Identification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("Site Name *") },
                        placeholder = { Text("e.g. Royal Road") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = id,
                            onValueChange = onIdChange,
                            label = { Text("Plugin ID *") },
                            placeholder = { Text("e.g. royalroad") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = version,
                            onValueChange = onVersionChange,
                            label = { Text("Version") },
                            modifier = Modifier.width(100.dp),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = onBaseUrlChange,
                        label = { Text("Base Domain *") },
                        placeholder = { Text("e.g. royalroad.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = extraHostsText,
                        onValueChange = onExtraHostsChange,
                        label = { Text("Alternative Hostnames (comma-separated)") },
                        placeholder = { Text("e.g. www.royalroad.com, m.royalroad.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = onDescriptionChange,
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Section: Book Metadata Selectors
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. Book Metadata Selectors", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = titleSelector,
                        onValueChange = onTitleSelectorChange,
                        label = { Text("Novel Title CSS Selector") },
                        placeholder = { Text("e.g. h1.title, h1") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authorSelector,
                        onValueChange = onAuthorSelectorChange,
                        label = { Text("Author CSS Selector") },
                        placeholder = { Text("e.g. .author a, span.author") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = synopsisSelector,
                        onValueChange = onSynopsisSelectorChange,
                        label = { Text("Synopsis CSS Selector") },
                        placeholder = { Text("e.g. .description, .synopsis") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = coverSelector,
                        onValueChange = onCoverSelectorChange,
                        label = { Text("Cover Image CSS Selector") },
                        placeholder = { Text("e.g. .cover img, meta[property='og:image']") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Section: Table of Contents & Pagination
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. Table of Contents & Chapter List", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = chapterListSelector,
                        onValueChange = onChapterListSelectorChange,
                        label = { Text("Chapter Links CSS Selector *") },
                        placeholder = { Text("e.g. ul.chapters li a, a[href*='chapter']") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reverse Chapter Order", fontWeight = FontWeight.Medium)
                            Text("Enable if the site displays newest chapters first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = reverseOrder, onCheckedChange = onReverseOrderChange)
                    }

                    OutlinedTextField(
                        value = linkFilterRegex,
                        onValueChange = onLinkFilterRegexChange,
                        label = { Text("URL Include Regex Filter (Optional)") },
                        placeholder = { Text("e.g. .*\\/chapter-\\d+.*") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = linkExcludeRegex,
                        onValueChange = onLinkExcludeRegexChange,
                        label = { Text("URL Exclude Regex Filter (Optional)") },
                        placeholder = { Text("e.g. .*\\/comments.*") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("TOC Pagination Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tocPaginationMode == "none",
                            onClick = { onTocPaginationModeChange("none") },
                            label = { Text("Single Page (None)") }
                        )
                        FilterChip(
                            selected = tocPaginationMode == "query",
                            onClick = { onTocPaginationModeChange("query") },
                            label = { Text("Query (?page=N)") }
                        )
                    }

                    if (tocPaginationMode == "query") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tocPageParam,
                                onValueChange = onTocPageParamChange,
                                label = { Text("Page Param") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = tocFirstPage.toString(),
                                onValueChange = { onTocFirstPageChange(it.toIntOrNull() ?: 1) },
                                label = { Text("First Page #") },
                                modifier = Modifier.width(110.dp),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = tocLastPageSelector,
                            onValueChange = onTocLastPageSelectorChange,
                            label = { Text("Pagination Links CSS Selector") },
                            placeholder = { Text("e.g. .pagination a, ul.pagination li a") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Max TOC Pages Cap: $tocMaxPages", modifier = Modifier.weight(1f))
                            Slider(
                                value = tocMaxPages.toFloat(),
                                onValueChange = { onTocMaxPagesChange(it.toInt()) },
                                valueRange = 10f..500f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Section: Chapter Reader Content
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("4. Chapter Content & Ad Cleaning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = chapterTitleSelector,
                        onValueChange = onChapterTitleSelectorChange,
                        label = { Text("Chapter Title CSS Selector") },
                        placeholder = { Text("e.g. h1.chapter-title, h1") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = chapterBodySelector,
                        onValueChange = onChapterBodySelectorChange,
                        label = { Text("Chapter Body CSS Selector *") },
                        placeholder = { Text("e.g. .chapter-content, #content, article") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = chapterReadySelector,
                        onValueChange = onChapterReadySelectorChange,
                        label = { Text("JS Render Gate Selector (Optional)") },
                        placeholder = { Text("Element that must appear when chapter is decrypted") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = junkSelectorsText,
                        onValueChange = onJunkSelectorsChange,
                        label = { Text("Junk & Ad Removal Selectors (comma-separated)") },
                        placeholder = { Text("e.g. .ad, .author-note, .watermark, .share-box") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column {
                        Text("Rate-Limit Page Delay: ${requestDelayMs}ms")
                        Slider(
                            value = requestDelayMs.toFloat(),
                            onValueChange = { onRequestDelayChange(it.toInt()) },
                            valueRange = 0f..5000f,
                            steps = 9
                        )
                    }
                    OutlinedTextField(
                        value = customUserAgent,
                        onValueChange = onCustomUserAgentChange,
                        label = { Text("Custom User-Agent (Optional)") },
                        placeholder = { Text("Leave blank for default mobile browser") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonEditorTab(
    jsonText: String,
    onJsonTextChange: (String) -> Unit,
    errorMessage: String?,
    onFormat: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Plugin JSON Specification", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFormat) {
                    Icon(Icons.Default.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Format")
                }
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
            }
        }

        if (errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = jsonText,
            onValueChange = onJsonTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        )
    }
}

@Composable
private fun LiveTesterTab(
    testUrl: String,
    onTestUrlChange: (String) -> Unit,
    isTesting: Boolean,
    testLogs: String,
    testBookCover: String?,
    onStartTest: () -> Unit,
    onCancelTest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Test Current Config Against Live Website", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "Enter any novel URL from this website to execute live metadata, TOC chapter list, and first-chapter content extraction in real-time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = testUrl,
            onValueChange = onTestUrlChange,
            label = { Text("Novel URL to Test") },
            placeholder = { Text("https://example.com/novel/...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onStartTest,
                enabled = !isTesting && testUrl.isNotBlank()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Live Test")
                }
            }

            if (isTesting) {
                OutlinedButton(onClick = onCancelTest) {
                    Text("Cancel")
                }
            }

            if (testBookCover != null) {
                Spacer(modifier = Modifier.weight(1f))
                AsyncImage(
                    model = testBookCover,
                    contentDescription = "Cover preview",
                    modifier = Modifier
                        .size(width = 44.dp, height = 58.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = if (testLogs.isBlank()) "Tap 'Run Live Test' to view live scraper output..." else testLogs,
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
