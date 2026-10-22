package com.example.ui.components

import android.webkit.WebView
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.plugin.PluginConfig
import com.example.util.JsResultParser
import com.example.util.WebViewFactory
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DomMatchItem(
    val tag: String,
    val className: String,
    val id: String,
    val text: String,
    val href: String?,
    val src: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebInspectorDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onCreatePluginFromInspector: (PluginConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf(initialUrl.ifBlank { "https://royalroad.com" }) }
    var currentLoadedUrl by remember { mutableStateOf("") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoadingPage by remember { mutableStateOf(false) }

    var cssQueryInput by remember { mutableStateOf("h1, .title, a[href*='chapter']") }
    var queryResults by remember { mutableStateOf<List<DomMatchItem>>(emptyList()) }
    var queryMessage by remember { mutableStateOf("") }
    var isRunningQuery by remember { mutableStateOf(false) }

    var autoDetectedConfig by remember { mutableStateOf<PluginConfig?>(null) }
    var showAutoDetectPanel by remember { mutableStateOf(false) }
    var activeInspectorTab by remember { mutableIntStateOf(0) } // 0 = Selector Query, 1 = Auto-Detect, 2 = Next.js / SPA Data

    var spaDataOutput by remember { mutableStateOf("") }

    fun runCssQuery(selector: String) {
        val wv = webViewInstance ?: return
        if (selector.isBlank()) return
        isRunningQuery = true
        val safeSel = JsResultParser.jsLiteral(selector)

        val script = """
            (() => {
                try {
                    const nodes = Array.from(document.querySelectorAll($safeSel)).slice(0, 100);
                    return JSON.stringify(nodes.map(n => ({
                        tag: n.tagName ? n.tagName.toLowerCase() : '',
                        className: (n.className && typeof n.className === 'string') ? n.className.trim() : '',
                        id: n.id || '',
                        text: (n.innerText || n.textContent || '').trim().slice(0, 140),
                        href: n.getAttribute('href') || '',
                        src: n.getAttribute('src') || n.getAttribute('data-src') || ''
                    })));
                } catch(e) {
                    return JSON.stringify({ error: e.message });
                }
            })()
        """.trimIndent()

        wv.evaluateJavascript(script) { evalResult ->
            coroutineScope.launch {
                isRunningQuery = false
                try {
                    val raw = if (evalResult.startsWith("\"") && evalResult.endsWith("\"")) {
                        org.json.JSONTokener(evalResult).nextValue().toString()
                    } else evalResult

                    if (raw.trim().startsWith("[")) {
                        val arr = JSONArray(raw)
                        val list = mutableListOf<DomMatchItem>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(
                                DomMatchItem(
                                    tag = obj.optString("tag"),
                                    className = obj.optString("className"),
                                    id = obj.optString("id"),
                                    text = obj.optString("text"),
                                    href = obj.optString("href").ifBlank { null },
                                    src = obj.optString("src").ifBlank { null }
                                )
                            )
                        }
                        queryResults = list
                        queryMessage = "Found ${list.size} matching elements on page"
                    } else if (raw.contains("error")) {
                        val obj = JSONObject(raw)
                        queryMessage = "CSS Error: ${obj.optString("error")}"
                        queryResults = emptyList()
                    }
                } catch (e: Exception) {
                    queryMessage = "Parse error: ${e.message}"
                    queryResults = emptyList()
                }
            }
        }
    }

    fun runAutoDetect() {
        val wv = webViewInstance ?: return
        showAutoDetectPanel = true

        val detectScript = """
            (() => {
                const getBest = (selectors) => {
                    for (const s of selectors) {
                        try {
                            const el = document.querySelector(s);
                            if (el && (el.innerText || el.textContent || '').trim().length > 0) return s;
                        } catch(e) {}
                    }
                    return selectors[0];
                };

                const titleSels = ["h1.novel-title", "h1.title", ".fiction-title h1", "h1.book-title", ".series-title", "h1", "h2.title"];
                const authorSels = [".author a", "span[itemprop='author']", "a[href*='/author/']", ".author-name", ".author", "h4 span a"];
                const descSels = [".description", ".synopsis", ".summary", ".desc-text", "meta[name='description']", ".entry-content"];
                const coverSels = [".cover img", "img.cover", ".poster img", ".fiction-cover img", "meta[property='og:image']", "img.thumbnail"];
                const chapterSels = ["#chapters tr td a", "ul.chapter-list li a", "a[href*='chapter']", ".chapters a", "a.chapter-link", "a[href*='/read/']"];
                const bodySels = [".chapter-inner.chapter-content", ".chapter-content", "#chapter-content", "#content", "article", ".entry-content", ".reader-content"];

                let host = location.hostname.replace(/^www\./, '');
                return JSON.stringify({
                    host: host,
                    titleSel: getBest(titleSels),
                    authorSel: getBest(authorSels),
                    descSel: getBest(descSels),
                    coverSel: getBest(coverSels),
                    chapterSel: getBest(chapterSels),
                    bodySel: getBest(bodySels)
                });
            })()
        """.trimIndent()

        wv.evaluateJavascript(detectScript) { evalResult ->
            coroutineScope.launch {
                try {
                    val raw = if (evalResult.startsWith("\"") && evalResult.endsWith("\"")) {
                        org.json.JSONTokener(evalResult).nextValue().toString()
                    } else evalResult

                    val obj = JSONObject(raw)
                    val host = obj.optString("host", "example.com")
                    val detected = PluginConfig(
                        id = host.replace(".", "_"),
                        name = host.replaceFirstChar { it.uppercase() },
                        baseUrl = host,
                        titleSelector = obj.optString("titleSel", "h1"),
                        authorSelector = obj.optString("authorSel", ".author"),
                        synopsisSelector = obj.optString("descSel", ".description"),
                        coverSelector = obj.optString("coverSel", "img.cover"),
                        chapterListSelector = obj.optString("chapterSel", "a[href*='chapter']"),
                        chapterTitleSelector = "h1",
                        chapterBodySelector = obj.optString("bodySel", "#chapter-content, article"),
                        isEnabled = true
                    )
                    autoDetectedConfig = detected
                } catch (_: Exception) {}
            }
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
                    title = { Text("Web Inspector & Selector Lab", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                autoDetectedConfig?.let {
                                    onCreatePluginFromInspector(it)
                                } ?: run {
                                    runAutoDetect()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Create Add-on From Page")
                        }
                    }
                )

                // Browser Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { if (webViewInstance?.canGoBack() == true) webViewInstance?.goBack() },
                        enabled = webViewInstance?.canGoBack() == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { if (webViewInstance?.canGoForward() == true) webViewInstance?.goForward() },
                        enabled = webViewInstance?.canGoForward() == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = { webViewInstance?.reload() }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("https://example.com/novel/...") }
                    )

                    Button(
                        onClick = {
                            var target = urlInput.trim()
                            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                target = "https://$target"
                                urlInput = target
                            }
                            isLoadingPage = true
                            webViewInstance?.loadUrl(target)
                        }
                    ) {
                        if (isLoadingPage) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Go")
                        }
                    }
                }

                // Split Layout: Top = Live WebView, Bottom = Inspector Console
                Column(modifier = Modifier.weight(1f)) {
                    // Embedded WebView
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.1f)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    WebViewFactory.applySafeDefaults(this)
                                    webViewClient = object : WebViewFactory.SafeWebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isLoadingPage = false
                                            // A new page: the previous page's detection no longer applies.
                                            if ((url ?: "") != currentLoadedUrl) autoDetectedConfig = null
                                            currentLoadedUrl = url ?: ""
                                            if (currentLoadedUrl.isNotBlank()) {
                                                urlInput = currentLoadedUrl
                                            }
                                        }
                                    }
                                    webViewInstance = this
                                    loadUrl(urlInput)
                                }
                            },
                            update = { /* dynamic */ },
                            onRelease = { view -> WebViewFactory.destroySafely(view) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Inspector Tools Console
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.9f)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex = activeInspectorTab, modifier = Modifier.height(42.dp)) {
                                Tab(
                                    selected = activeInspectorTab == 0,
                                    onClick = { activeInspectorTab = 0 },
                                    text = { Text("CSS Selector Probe", fontSize = 12.sp) }
                                )
                                Tab(
                                    selected = activeInspectorTab == 1,
                                    onClick = {
                                        activeInspectorTab = 1
                                        runAutoDetect()
                                    },
                                    text = { Text("Smart Auto-Detect", fontSize = 12.sp) }
                                )
                                Tab(
                                    selected = activeInspectorTab == 2,
                                    onClick = {
                                        activeInspectorTab = 2
                                        webViewInstance?.evaluateJavascript("(() => { return document.getElementById('__NEXT_DATA__') ? document.getElementById('__NEXT_DATA__').textContent.slice(0, 1000) : 'No __NEXT_DATA__ found'; })()") {
                                            spaDataOutput = it ?: ""
                                        }
                                    },
                                    text = { Text("SPA / Next.js Data", fontSize = 12.sp) }
                                )
                            }

                            when (activeInspectorTab) {
                                0 -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = cssQueryInput,
                                                onValueChange = { cssQueryInput = it },
                                                label = { Text("CSS Selector to Probe") },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true
                                            )
                                            Button(
                                                onClick = { runCssQuery(cssQueryInput) },
                                                enabled = !isRunningQuery
                                            ) {
                                                Text("Query")
                                            }
                                        }

                                        // Preset quick-chips
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            SuggestionChip(onClick = { cssQueryInput = "h1, .title"; runCssQuery(cssQueryInput) }, label = { Text("Title", fontSize = 11.sp) })
                                            SuggestionChip(onClick = { cssQueryInput = ".author, span.author, a[href*='author']"; runCssQuery(cssQueryInput) }, label = { Text("Author", fontSize = 11.sp) })
                                            SuggestionChip(onClick = { cssQueryInput = "a[href*='chapter'], ul.chapters a"; runCssQuery(cssQueryInput) }, label = { Text("Chapters", fontSize = 11.sp) })
                                            SuggestionChip(onClick = { cssQueryInput = ".chapter-content, #content, article"; runCssQuery(cssQueryInput) }, label = { Text("Body", fontSize = 11.sp) })
                                        }

                                        if (queryMessage.isNotBlank()) {
                                            Text(queryMessage, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }

                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (queryResults.isEmpty()) {
                                                item {
                                                    Text(
                                                        "No elements inspected yet. Enter a selector and tap 'Query'.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                items(queryResults) { item ->
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                                            .padding(6.dp)
                                                    ) {
                                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Text("<${item.tag}>", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                                            if (item.className.isNotBlank()) Text(".${item.className.take(30)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                                            if (item.id.isNotBlank()) Text("#${item.id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                                        }
                                                        if (item.text.isNotBlank()) {
                                                            Text("\"${item.text}\"", fontSize = 11.sp, maxLines = 2)
                                                        }
                                                        if (item.href != null) {
                                                            Text("href: ${item.href}", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                                        }
                                                        if (item.src != null) {
                                                            Text("src: ${item.src}", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                1 -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("DOM Heuristic Analysis", fontWeight = FontWeight.Bold)
                                        autoDetectedConfig?.let { cfg ->
                                            Text("Detected Selectors for ${cfg.baseUrl}:", style = MaterialTheme.typography.labelMedium)
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("• Title: ${cfg.titleSelector}", fontSize = 12.sp)
                                                    Text("• Author: ${cfg.authorSelector}", fontSize = 12.sp)
                                                    Text("• Synopsis: ${cfg.synopsisSelector}", fontSize = 12.sp)
                                                    Text("• Cover: ${cfg.coverSelector}", fontSize = 12.sp)
                                                    Text("• Chapters: ${cfg.chapterListSelector}", fontSize = 12.sp)
                                                    Text("• Body: ${cfg.chapterBodySelector}", fontSize = 12.sp)
                                                }
                                            }
                                            Button(
                                                onClick = { onCreatePluginFromInspector(cfg) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Open in Plugin Studio with Detected Selectors")
                                            }
                                        } ?: run {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            Text("Analyzing DOM tree...", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                2 -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            SelectionContainer {
                                                Text(
                                                    text = if (spaDataOutput.isBlank()) "Analyzing SPA state..." else spaDataOutput,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
