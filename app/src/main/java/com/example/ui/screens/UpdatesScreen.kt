package com.example.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.example.background.ChapterUpdateWorker
import com.example.background.ChapterUpdatesStore
import com.example.data.local.BookEntity
import com.example.viewmodel.MainViewModel
import java.io.File

/**
 * Every book with new chapters in one place, like Mihon's Updates tab. Fed by the periodic
 * background check and by "Check now"; "Download" queues the new chapters immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    viewModel: MainViewModel,
    onOpenBook: (String) -> Unit
) {
    val context = LocalContext.current
    val store = remember { ChapterUpdatesStore.get(context) }
    val entries by store.entries.collectAsState()
    val books by viewModel.repository.allBooks.collectAsState(initial = emptyList())

    val checkNowInfo by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ChapterUpdateWorker.CHECK_NOW_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val isChecking = checkNowInfo.any { it.state == WorkInfo.State.RUNNING }
    // Queued but not started: usually waiting for a connection.
    val isWaiting = !isChecking && checkNowInfo.any { it.state == WorkInfo.State.ENQUEUED }

    val withNew = books.mapNotNull { b -> entries[b.id]?.takeIf { it.newCount > 0 }?.let { b to it } }
        .sortedByDescending { it.second.checkedAt }
    val withErrors = books.mapNotNull { b -> entries[b.id]?.takeIf { it.error != null }?.let { b to it } }
    val lastChecked = entries.values.maxOfOrNull { it.checkedAt }
    var showLimits by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    if (showNotifications) {
        com.example.ui.components.NotificationSettingsDialog(books.filter { !it.isDeleted && !it.url.startsWith("local://") }) {
            showNotifications = false
        }
    }
    if (showLimits) {
        com.example.ui.components.DownloadLimitsDialog(viewModel.pluginManager) { showLimits = false }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Updates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (lastChecked == null) "Not checked yet"
                    else "Last checked ${DateUtils.getRelativeTimeSpanString(lastChecked)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showLimits = true }) { Text("Limits") }
            FilledTonalButton(
                onClick = { ChapterUpdateWorker.checkNow(context) },
                enabled = !isChecking && !isWaiting
            ) {
                if (isWaiting) {
                    Text("Waiting for network")
                } else if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Check now")
                }
            }
        }

        // Plain on/off for new-chapter alerts, always visible; "Options" for timing, sound, novels.
        run {
            val nPrefs = remember { com.example.background.NotificationPrefs.get(context) }
            var alertsOn by remember { mutableStateOf(nPrefs.newChapters) }
            LaunchedEffect(showNotifications) { if (!showNotifications) alertsOn = nPrefs.newChapters }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (alertsOn) Icons.Default.Notifications else Icons.Default.NotificationsOff, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (alertsOn) "New-chapter notifications on" else "New-chapter notifications off",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 10.dp)
                )
                TextButton(onClick = { showNotifications = true }) { Text("Options") }
                Switch(checked = alertsOn, onCheckedChange = { alertsOn = it; nPrefs.newChapters = it })
            }
        }

        DownloadQueueSection(viewModel)

        if (withNew.isEmpty() && withErrors.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.NewReleases, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("You're all caught up", fontWeight = FontWeight.SemiBold)
                    Text(
                        "New chapters from your library will show up here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp + com.example.ui.components.LocalPlayerOverlayPadding.current),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(withNew, key = { it.first.id }) { (book, entry) ->
                // Swipe either way to mark it seen (it leaves the list and the badge).
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v != SwipeToDismissBoxValue.Settled) store.clear(book.id)
                    v != SwipeToDismissBoxValue.Settled
                })
                SwipeToDismissBox(
                    state = swipe,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterStart
                        ) { Text("Mark as seen", color = MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                ) {
                UpdateRow(
                    book = book,
                    entry = entry,
                    busy = viewModel.checkingNewChaptersBookId == book.id,
                    onOpen = { onOpenBook(book.id) },
                    onDownload = {
                        if (viewModel.isScraping || viewModel.scraping.isCheckingNewChapters) {
                            Toast.makeText(context, "A download is already running", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.scraping.checkForNewChapters(book, autoGrab = true)
                            Toast.makeText(context, "Downloading new chapters of ${book.title}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                }
            }
            if (withErrors.isNotEmpty()) {
                item {
                    Text(
                        "Couldn't check",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(withErrors, key = { "err_" + it.first.id }) { (book, entry) ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenBook(book.id) }) {
                        Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            entry.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(
    book: BookEntity,
    entry: ChapterUpdatesStore.Entry,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val cover: Any? = book.coverLocalPath?.takeIf { File(it).exists() }?.let { File(it) } ?: book.coverUrl
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.newCount} new · from ch. ${entry.fromChapter}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(entry.checkedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Download new chapters")
                }
            }
        }
    }
}

/** Novels waiting for their remaining chapters, worked through in the background. */
@Composable
private fun DownloadQueueSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val queue = remember { com.example.background.DownloadQueue.get(context) }
    val queued by queue.books.collectAsState()
    if (queued.isEmpty()) return
    val books by viewModel.repository.allBooks.collectAsState(initial = emptyList())
    val running by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(com.example.background.DownloadQueue.WORK_NOW)
    }.collectAsState(initial = emptyList())
    val isRunning = running.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download queue", fontWeight = FontWeight.Bold)
                    Text(
                        queue.lastRunSummary.ifBlank { "Runs hourly on Wi-Fi, a few chapters per novel in turn, within site limits" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(enabled = !isRunning, onClick = { com.example.background.DownloadQueue.runNow(context) }) {
                    Text(if (isRunning) "Running…" else "Run now", maxLines = 1, softWrap = false)
                }
            }
            queued.forEach { id ->
                val book = books.firstOrNull { it.id == id } ?: return@forEach
                val pending by produceState(initialValue = -1, id, isRunning) {
                    value = viewModel.repository.getPendingVisibleCount(id)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (pending < 0) "…" else "$pending left",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { queue.remove(id); com.example.background.DownloadQueue.schedule(context) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                    }
                }
            }
        }
    }
}
