package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.background.NotificationPrefs
import com.example.data.local.BookEntity

/** Turn each kind of notification on or off, space out chapter alerts, and mute single novels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsDialog(books: List<BookEntity>, onDismiss: () -> Unit) {
    val prefs = NotificationPrefs.get(LocalContext.current)
    var newChapters by remember { mutableStateOf(prefs.newChapters) }
    var hours by remember { mutableIntStateOf(prefs.minHoursBetween) }
    var needsYou by remember { mutableStateOf(prefs.downloadNeedsYou) }
    var siteHealth by remember { mutableStateOf(prefs.siteHealth) }
    var muted by remember { mutableStateOf(prefs.mutedBooks()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Notifications") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    ToggleRow("New chapters", "One summary for all novels", newChapters) {
                        newChapters = it; prefs.newChapters = it
                    }
                }
                if (newChapters) item {
                    Text("At most", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Every check", 6 to "Every 6 h", 12 to "Every 12 h", 24 to "Once a day", 72 to "Every 3 days", 168 to "Once a week")
                            .forEach { (h, label) ->
                                FilterChip(selected = hours == h, onClick = { hours = h; prefs.minHoursBetween = h }, label = { Text(label) })
                            }
                    }
                }
                item {
                    ToggleRow("Download needs you", "A bot check or page to look at paused a download", needsYou) {
                        needsYou = it; prefs.downloadNeedsYou = it
                    }
                }
                item {
                    ToggleRow("Site stopped working", "From the weekly site check", siteHealth) {
                        siteHealth = it; prefs.siteHealth = it
                    }
                }
                if (newChapters && books.isNotEmpty()) {
                    item {
                        Text(
                            "Novels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Text(
                            "Turn off a novel to stop its new-chapter alerts. It still shows in Updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(books, key = { it.id }) { book ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Switch(
                                checked = book.id !in muted,
                                onCheckedChange = { on ->
                                    prefs.setMuted(book.id, !on)
                                    muted = prefs.mutedBooks()
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
