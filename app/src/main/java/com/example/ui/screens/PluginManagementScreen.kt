package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.data.plugin.PluginConfig
import com.example.data.plugin.PluginManager
import com.example.ui.theme.MinTouchTarget
import com.example.ui.theme.TextDefaults
import com.example.ui.theme.screenContentPadding
import kotlinx.coroutines.launch

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

            if (plugins.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No custom plugins installed. Tap + or the file icon above to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(plugins, key = { it.id }) { plugin ->
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
                                        style = MaterialTheme.typography.bodySmall
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
}
