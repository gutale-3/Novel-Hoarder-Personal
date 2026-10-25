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

@OptIn(ExperimentalMaterial3Api::class)
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
    shelves: List<String> = LibraryPrefs.DEFAULT_SHELVES,
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

    val progress = remember(book, bookStats) { com.example.util.ReadingProgress.of(book, bookStats) }

    // The full menu as a bottom sheet: it scrolls and clears the gesture bar, so every action
    // (Delete included) can be reached.
    if (expandedMenu) {
        ModalBottomSheet(onDismissRequest = { expandedMenu = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    progress.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                )
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
                                val shelfIcons = mapOf(
                                    "Reading" to Icons.Default.MenuBook,
                                    "Plan to Read" to Icons.Default.BookmarkBorder,
                                    "Waiting" to Icons.Default.HourglassEmpty,
                                    "Completed" to Icons.Default.CheckCircleOutline,
                                    "Favorites" to Icons.Default.FavoriteBorder
                                )
                                val shelfOptions = shelves.map { it to (shelfIcons[it] ?: Icons.Default.Folder) }
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
                                    RawTranslationMenuItems(book) { expandedMenu = false }
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

    // Swipe right to continue reading, left to listen.
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onRead()
                SwipeToDismissBoxValue.EndToStart -> onListen()
                else -> {}
            }
            false // snap back; the card stays in the list
        },
        positionalThreshold = { it * 0.35f }
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !isBatchMode,
        enableDismissFromEndToStart = !isBatchMode,
        modifier = modifier,
        backgroundContent = {
            val toRead = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (toRead) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        if (toRead) Icons.Default.MenuBook else Icons.Default.Headphones, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (toRead) "Continue reading" else "Listen",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) {
    // Compact card: cover, title, one progress line — several novels fit on screen.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isBatchMode) onToggleSelect() else onRead() }
            .testTag("library_book_card_${book.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isBatchMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            }
            val cover: Any? = book.coverLocalPath?.takeIf { it.isNotEmpty() && File(it).exists() }?.let { File(it) }
                ?: book.coverUrl?.takeIf { it.isNotEmpty() }
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    modifier = Modifier.size(width = 46.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        book.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        progress.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (progress.caughtUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (progress.caughtUp) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (progress.caughtUp) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (isCheckingNewChapters) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                val offline = progress.downloaded
                if (offline > 0 || book.url.startsWith("local://")) {
                    Text(
                        if (book.url.startsWith("local://")) "Imported · $offline offline" else "$offline offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
            IconButton(onClick = { expandedMenu = true }) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Menu for ${book.title}")
            }
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

/** For novels from raw Chinese sites: translate stored raw chapters, or keep the original text. */
@Composable
private fun RawTranslationMenuItems(book: BookEntity, onDone: () -> Unit) {
    if (!com.example.data.scraper.RawTranslation.isRawSource(book.url)) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var translateOn by remember(book.id) {
        mutableStateOf(com.example.data.scraper.RawTranslation.isEnabled(context, book.id))
    }
    DropdownMenuItem(
        text = { Text("Translate raw chapters") },
        leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
        onClick = {
            onDone()
            val scraping = com.example.NovelHoarderApp.getContainer(context.applicationContext as android.app.Application).scraping
            android.widget.Toast.makeText(context, "Translating…", android.widget.Toast.LENGTH_SHORT).show()
            scraping.translateRawChapters(book.id) { msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    )
    DropdownMenuItem(
        text = { Text("Translate new chapters") },
        leadingIcon = { Icon(Icons.Default.GTranslate, contentDescription = null) },
        trailingIcon = { Checkbox(checked = translateOn, onCheckedChange = null) },
        onClick = {
            translateOn = !translateOn
            com.example.data.scraper.RawTranslation.setEnabled(context, book.id, translateOn)
        }
    )
}
