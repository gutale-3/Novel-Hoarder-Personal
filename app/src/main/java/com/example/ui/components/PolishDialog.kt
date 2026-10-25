package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.background.PolishWorker
import com.example.data.ai.ChapterPolisher
import com.example.data.local.ChapterEntity
import kotlinx.coroutines.launch

/** Per-novel AI polish: on/off, this chapter, polish ahead, the daily limit and the name list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PolishDialog(
    bookId: String,
    chapter: ChapterEntity?,
    polisher: ChapterPolisher,
    hasPolishedCopy: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { available = polisher.isAvailable() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(PolishWorker.lastResult(context, bookId)) }
    var limit by remember { mutableIntStateOf(polisher.dailyLimit) }
    var names by remember { mutableStateOf(polisher.names(bookId)) }
    var showNames by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Polish translation") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Gemini rewrites the machine translation into natural English as you read, keeping every " +
                            "event and line. The original text is kept, and you can switch back any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!available) item {
                    Text(
                        "Add your Gemini key in Settings → AI to use this.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Polished text for this novel", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (enabled) "Showing polished text where ready" else "Showing the original",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    }
                }
                if (chapter != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (busy) "Polishing this chapter…" else if (hasPolishedCopy) "This chapter: polished ✓" else "This chapter: not polished yet",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else OutlinedButton(enabled = available, onClick = {
                            busy = true
                            scope.launch {
                                status = polisher.polish(chapter, force = hasPolishedCopy) ?: "Chapter polished"
                                if (!enabled) onEnabledChange(true)
                                names = polisher.names(bookId)
                                busy = false
                            }
                        }) { Text(if (hasPolishedCopy) "Redo" else "Polish now") }
                    }
                }
                item {
                    FilledTonalButton(
                        enabled = available,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            PolishWorker.enqueue(context, bookId, 50)
                            if (!enabled) onEnabledChange(true)
                            status = "Polishing the next 50 chapters in the background…"
                        }
                    ) { Text("Polish the next 50 chapters") }
                }
                status?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                item {
                    Text(
                        "Today: ${polisher.usedToday()} of $limit chapters",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 300, 600, 1000).forEach { n ->
                            FilterChip(
                                selected = limit == n,
                                onClick = { limit = n; polisher.dailyLimit = n },
                                label = { Text("$n / day") }
                            )
                        }
                    }
                    Text(
                        "Keeps you inside Gemini's free allowance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    TextButton(onClick = { showNames = !showNames }) {
                        Text(if (showNames) "Hide names" else "Names kept consistent (${names.size})")
                    }
                }
                if (showNames) {
                    if (names.isEmpty()) item {
                        Text("Names are collected as chapters are polished.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(names, key = { it.name }) { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.name, fontWeight = FontWeight.SemiBold)
                                if (n.aliases.isNotEmpty()) Text(
                                    "instead of: " + n.aliases.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { polisher.removeName(bookId, n.name); names = polisher.names(bookId) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove ${n.name}")
                            }
                        }
                    }
                }
            }
        }
    )
}
