package com.example.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
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
    val isChecking = checkNowInfo.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    val withNew = books.mapNotNull { b -> entries[b.id]?.takeIf { it.newCount > 0 }?.let { b to it } }
        .sortedByDescending { it.second.checkedAt }
    val withErrors = books.mapNotNull { b -> entries[b.id]?.takeIf { it.error != null }?.let { b to it } }
    val lastChecked = entries.values.maxOfOrNull { it.checkedAt }
    var showLimits by remember { mutableStateOf(false) }
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
                enabled = !isChecking
            ) {
                if (isChecking) {
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(withNew, key = { it.first.id }) { (book, entry) ->
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
