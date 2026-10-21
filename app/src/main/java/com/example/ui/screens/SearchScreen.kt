package com.example.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.NovelHoarderApp
import com.example.data.scraper.NovelSearch
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Search every site at once and add a result straight to the Scrape screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPickResult: () -> Unit
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val search = remember { NovelHoarderApp.getContainer(context.applicationContext as Application).novelSearch }
    var query by rememberSaveable { mutableStateOf("") }
    var sources by remember { mutableStateOf(search.sources()) }
    var browsing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, NovelSearch.SourceResults>>(emptyMap()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true && results.size < sources.size

    fun run() {
        if (query.isBlank()) return
        focus.clearFocus()
        job?.cancel()
        browsing = false
        sources = search.sources()
        results = emptyMap()
        val list = sources
        job = scope.launch {
            search.searchAll(query, list) { r -> results = results + (r.source.key to r) }
        }
    }

    /** Discover: each site's popular list, side by side. */
    fun browse() {
        focus.clearFocus()
        job?.cancel()
        browsing = true
        sources = search.browseSources()
        results = emptyMap()
        val list = sources
        job = scope.launch {
            search.searchAll("", list) { r -> results = results + (r.source.key to r) }
        }
    }

    fun pick(r: NovelSearch.Result) {
        viewModel.scraping.scrapeUrl = r.url
        viewModel.scraping.scrapeBookName = r.title
        onPickResult()
    }

    fun openSiteSearch(src: NovelSearch.SearchSource) {
        val url = src.urlTemplate.replace("{query}", java.net.URLEncoder.encode(query.trim(), "UTF-8"))
        viewModel.scraping.manualBrowserUrl = if (src.postBody.isBlank()) url else url.substringBefore("/e/search")
        viewModel.scraping.showManualBrowser = true
        onPickResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (browsing) "Popular now" else "Search all sites") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Novel title…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { run() }, enabled = query.isNotBlank()) { Text("Search") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = browsing,
                    onClick = { browse() },
                    label = { Text("Popular now") },
                    leadingIcon = { Icon(Icons.Default.Whatshot, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(sources, key = { it.key }) { src ->
                    val r = results[src.key]
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(src.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            val label = when {
                                r == null && job != null -> "searching…"
                                r == null -> ""
                                r.status == NovelSearch.Status.OK -> "${r.results.size} found"
                                r.status == NovelSearch.Status.EMPTY -> "nothing found"
                                r.status == NovelSearch.Status.BLOCKED -> "needs verification"
                                else -> "failed"
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r != null && r.status != NovelSearch.Status.OK) {
                                IconButton(onClick = { openSiteSearch(src) }) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open site search in browser")
                                }
                            }
                        }
                        if (r != null && r.message.isNotBlank()) {
                            Text(r.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r != null && r.results.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(r.results, key = { it.url }) { item ->
                                    Column(
                                        Modifier.width(110.dp).clickable { pick(item) }
                                    ) {
                                        AsyncImage(
                                            model = item.cover.ifBlank { null },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 110.dp, height = 155.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                item {
                    Text(
                        "Tap a result to open it on the Scrape screen, ready to download. " +
                            "Sites without search show a browser button to search them by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
