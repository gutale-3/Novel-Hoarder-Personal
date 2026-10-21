package com.example.ui.screens

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.BookEntity
import com.example.data.local.BookStats
import com.example.data.local.GlossaryEntity
import com.example.ui.components.*
import com.example.viewmodel.MainViewModel
import java.io.File
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import com.example.ui.theme.AppTheme

@Composable
fun LibraryBookItem(
    book: BookEntity,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onGlossary: () -> Unit,
    onExportEpub: () -> Unit,
    onExportPdf: () -> Unit,
    onDelete: () -> Unit,
    onRescrapeCorrupted: () -> Unit,
    onCheckNewChapters: () -> Unit,
    onDownloadBatch: (Int) -> Unit = {},
    onDownloadAllRemaining: () -> Unit = {},
    onRefreshToc: () -> Unit = {},
    isCheckingNewChapters: Boolean,
    isBatchMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    unreadCount: Int = 0,
    downloadedCount: Int = 0,
    bookStats: BookStats? = null,
    onUpdateCategory: (String) -> Unit = {},
    onMigrateSource: () -> Unit = {},
    onEditDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedMenu by remember { mutableStateOf(false) }
    var showQualityCheck by remember { mutableStateOf(false) }
    if (showQualityCheck) {
        com.example.ui.components.ChapterQualityDialog(book) { showQualityCheck = false }
    }

    // Title formatted up to 15 words, displaying across up to two lines
    val displayTitle = remember(book.title) {
        val words = book.title.trim().split(Regex("\\s+"))
        if (words.size > 15) {
            words.take(15).joinToString(" ") + "…"
        } else {
            book.title
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isBatchMode) { onRead() }
            .testTag("library_book_card_${book.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = if (isBatchMode) Modifier.clickable { onToggleSelect() } else Modifier
            ) {
                if (isBatchMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                // Cover image or Placeholder
                val hasLocalCover = !book.coverLocalPath.isNullOrEmpty() && File(book.coverLocalPath!!).exists()
                if (hasLocalCover) {
                    AsyncImage(
                        model = File(book.coverLocalPath!!),
                        contentDescription = "${book.title} Cover",
                        modifier = Modifier
                            .size(width = 64.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else if (!book.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "${book.title} Cover",
                        modifier = Modifier
                            .size(width = 64.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = book.title.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp
                            )
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                lineHeight = 19.sp
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Box {
                            IconButton(
                                onClick = { expandedMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(
                                expanded = expandedMenu,
                                onDismissRequest = { expandedMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Listen (TTS)") },
                                    onClick = {
                                        expandedMenu = false
                                        onListen()
                                    },
                                    leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Table of Contents") },
                                    onClick = {
                                        expandedMenu = false
                                        onRead()
                                    },
                                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) }
                                )
                                Divider()
                                // Shelf / Status category selection
                                Text(
                                    text = "SHELF / STATUS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                                val shelfOptions = listOf(
                                    "Reading" to Icons.Default.MenuBook,
                                    "Plan to Read" to Icons.Default.BookmarkBorder,
                                    "Waiting" to Icons.Default.HourglassEmpty,
                                    "Completed" to Icons.Default.CheckCircleOutline,
                                    "Favorites" to Icons.Default.FavoriteBorder
                                )
                                shelfOptions.forEach { (catName, icon) ->
                                    val isCurrent = book.category.equals(catName, ignoreCase = true)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = catName,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Current shelf",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            expandedMenu = false
                                            onUpdateCategory(catName)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    )
                                }
                                Divider()
                                if (!book.url.startsWith("local://")) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text("Check for New Chapters")
                                                if (isCheckingNewChapters) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            expandedMenu = false
                                            onCheckNewChapters()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        enabled = !isCheckingNewChapters
                                    )
                                }
                                if (!book.url.startsWith("local://")) {
                                    DropdownMenuItem(
                                        text = { Text("Refresh Table of Contents") },
                                        onClick = {
                                            expandedMenu = false
                                            onRefreshToc()
                                        },
                                        leadingIcon = { Icon(Icons.Default.ListAlt, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download Next 10 Chapters") },
                                        onClick = {
                                            expandedMenu = false
                                            onDownloadBatch(10)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download Next 50 Chapters") },
                                        onClick = {
                                            expandedMenu = false
                                            onDownloadBatch(50)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Download All Remaining") },
                                        onClick = {
                                            expandedMenu = false
                                            onDownloadAllRemaining()
                                        },
                                        leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) }
                                    )
                                    AutoDownloadMenuItem(book)
                                    QueueMenuItem(book) { expandedMenu = false }
                                    DropdownMenuItem(
                                        text = { Text("Check Chapter Quality") },
                                        onClick = {
                                            expandedMenu = false
                                            showQualityCheck = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.FactCheck, contentDescription = null) }
                                    )
                                    Divider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Manage Glossary") },
                                    onClick = {
                                        expandedMenu = false
                                        onGlossary()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Spellcheck, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export EPUB") },
                                    onClick = {
                                        expandedMenu = false
                                        onExportEpub()
                                    },
                                    leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export PDF") },
                                    onClick = {
                                        expandedMenu = false
                                        onExportPdf()
                                    },
                                    leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rescrape Corrupted Chapters") },
                                    onClick = {
                                        expandedMenu = false
                                        onRescrapeCorrupted()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                )
                                if (!book.url.startsWith("local://")) {
                                    DropdownMenuItem(
                                        text = { Text("Switch / Migrate Source") },
                                        onClick = {
                                            expandedMenu = false
                                            onMigrateSource()
                                        },
                                        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Edit Novel Details") },
                                    onClick = {
                                        expandedMenu = false
                                        onEditDetails()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                )
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("Delete Novel", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        expandedMenu = false
                                        onDelete()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Author: ${book.author}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (book.url.startsWith("local://")) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Imported",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = book.synopsis,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Info row (Chapters Downloaded and unread count)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (downloadedCount == book.totalChapters) "${book.totalChapters} Chapters Downloaded" else "$downloadedCount / ${book.totalChapters} Downloaded",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (unreadCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "$unreadCount unread",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isCheckingNewChapters) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Offline availability progress bar
            if (downloadedCount < book.totalChapters && book.totalChapters > 0) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Offline Available: ${((downloadedCount.toFloat() / book.totalChapters) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "${book.totalChapters - downloadedCount} pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinearProgressIndicator(
                        progress = downloadedCount.toFloat() / book.totalChapters,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Reading progress percentage & bar
            val totalChaps = maxOf(book.totalChapters, downloadedCount, bookStats?.maxChapterNumber ?: 0)
            val currentReadChNum = maxOf(
                book.lastReadChapterNumber,
                bookStats?.maxReadChapterNumber ?: 0,
                bookStats?.readCount ?: 0,
                if (totalChaps > 0) (totalChaps - unreadCount).coerceAtLeast(0) else 0
            )
            val progressPercent = if (totalChaps > 0) {
                ((currentReadChNum.toFloat() / totalChaps) * 100).toInt().coerceIn(0, 100)
            } else {
                0
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (totalChaps > 0) {
                            if (currentReadChNum >= totalChaps) "Reading Progress: 100% (Completed)"
                            else "Reading Progress: $progressPercent%"
                        } else {
                            "Reading Progress: Not started"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = if (totalChaps > 0) {
                            if (currentReadChNum > 0) "Ch. $currentReadChNum / $totalChaps Read"
                            else "0 / $totalChaps Read"
                        } else {
                            "0 Chapters"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    )
                }
                LinearProgressIndicator(
                    progress = if (totalChaps > 0) (currentReadChNum.toFloat() / totalChaps).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/**
 * Per-novel switch for background auto-download. Tapping flips the effective value; choosing the
 * same value as the site default clears the override, so a later change to the site default
 * applies again.
 */
@Composable
private fun AutoDownloadMenuItem(book: BookEntity) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val quota = remember { com.example.data.scraper.DownloadQuota.get(context) }
    val version by quota.version.collectAsState()
    val sourceKey = remember(book.url) { com.example.data.scraper.SourceKeys.keyFor(book.url) }
    val siteDefault = remember(version, sourceKey) { quota.limitsFor(sourceKey).autoDownload }
    val override = remember(version, book.id) { quota.bookOverride(book.id) }
    val effective = override ?: siteDefault

    DropdownMenuItem(
        text = {
            Column {
                Text("Auto-download new chapters")
                Text(
                    if (override == null) "Site default (${if (siteDefault) "on" else "off"})" else "Set for this novel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = {
            val newValue = !effective
            quota.setBookOverride(book.id, if (newValue == siteDefault) null else newValue)
        },
        leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) },
        trailingIcon = { Checkbox(checked = effective, onCheckedChange = null) }
    )
}

/** Adds the novel to the background download queue (after refreshing its chapter list), or removes it. */
@Composable
private fun QueueMenuItem(book: BookEntity, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val queue = remember { com.example.background.DownloadQueue.get(context) }
    val queued by queue.books.collectAsState()
    val inQueue = book.id in queued
    DropdownMenuItem(
        text = { Text(if (inQueue) "Remove from download queue" else "Add to download queue") },
        leadingIcon = { Icon(if (inQueue) Icons.Default.RemoveCircleOutline else Icons.Default.PlaylistAdd, contentDescription = null) },
        onClick = {
            onDone()
            if (inQueue) {
                queue.remove(book.id)
                com.example.background.DownloadQueue.schedule(context)
            } else {
                val scraping = com.example.NovelHoarderApp.getContainer(context.applicationContext as android.app.Application).scraping
                android.widget.Toast.makeText(context, "Getting the chapter list…", android.widget.Toast.LENGTH_SHORT).show()
                scraping.refreshTableOfContents(book.id) { _, msg ->
                    queue.add(book.id)
                    com.example.background.DownloadQueue.schedule(context)
                    com.example.background.DownloadQueue.runNow(context)
                    android.widget.Toast.makeText(context, "Queued ${book.title}. $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}
