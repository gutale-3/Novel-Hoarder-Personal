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
fun LibraryScreen(
    viewModel: MainViewModel,
    onOpenBook: (String) -> Unit,
    onNavigateToScrape: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val books by viewModel.repository.allBooks.collectAsState(emptyList())
    val deletedBooks by viewModel.deletedBooks.collectAsState(emptyList())
    val deletedChapters by viewModel.deletedChapters.collectAsState(emptyList())
    val context = LocalContext.current

    var currentMainTab by remember { mutableStateOf(0) } // 0 = Library, 1 = Deleted Items (Trash)
    var trashSubTab by remember { mutableStateOf(0) } // 0 = Novels, 1 = Chapters
    var showEmptyTrashDialog by remember { mutableStateOf(false) }

    // Search, Filter, Sort, Batch state
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("Title A-Z") } // "Title A-Z", "Title Z-A", "Total Chapters", "Unread Chapters", "Last Updated"
    var isBatchMode by remember { mutableStateOf(false) }
    var selectedBookIds by remember { mutableStateOf(emptySet<String>()) }

    var showImportProgressDialog by remember { mutableStateOf(false) }
    var importProgressMessage by remember { mutableStateOf("") }
    var showImportStatusDialog by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf("") }

    // Batch book stats map from Room (No N+1 queries)
    val bookStatsMap by viewModel.allBookStatsMap.collectAsState()

    // Category / Shelf state
    val categories = listOf("All", "Reading", "Plan to Read", "Waiting", "Completed", "Favorites")
    var selectedCategory by remember { mutableStateOf(viewModel.settings.activeLibraryCategory) }

    // Export with missing chapters state
    var showMissingExportDialog by remember { mutableStateOf(false) }
    var pendingExportBook by remember { mutableStateOf<BookEntity?>(null) }
    var pendingExportFormat by remember { mutableStateOf<String?>(null) }
    var missingChaptersCount by remember { mutableStateOf(0) }

    // Download All Remaining confirmation state
    var showDownloadAllConfirmDialog by remember { mutableStateOf(false) }
    var pendingDownloadAllBook by remember { mutableStateOf<BookEntity?>(null) }

    // Configure Import state
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportIsEpub by remember { mutableStateOf(false) }
    var showConfigureImportDialog by remember { mutableStateOf(false) }
    var importCustomUrl by remember { mutableStateOf("") }

    // Edit Book state
    var showEditDetailsDialog by remember { mutableStateOf(false) }
    var activeEditBook by remember { mutableStateOf<BookEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    var editCoverUrl by remember { mutableStateOf("") }
    var editCoverLocalPath by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("Reading") }

    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val firstUri = uris.first()
            val isEpub = firstUri.toString().endsWith(".epub", ignoreCase = true) || 
                         (context.contentResolver.getType(firstUri)?.contains("epub") == true)
            pendingImportUri = firstUri
            pendingImportIsEpub = isEpub
            importCustomUrl = ""
            showConfigureImportDialog = true
        }
    }

    val coverImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val coversDir = File(context.filesDir, "covers")
                        if (!coversDir.exists()) coversDir.mkdirs()
                        val localFile = File(coversDir, "custom_${System.currentTimeMillis()}.jpg")
                        localFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        inputStream.close()
                        editCoverLocalPath = localFile.absolutePath
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importProgressMessage = "Restoring library backup... Please wait..."
            showImportProgressDialog = true
            viewModel.library.restoreLibrary(context, uri) { success, msg ->
                showImportProgressDialog = false
                importStatusMessage = msg
                showImportStatusDialog = true
            }
        }
    }

    val filteredAndSortedBooks = remember(books, searchQuery, sortBy, selectedCategory, bookStatsMap) {
        var list = books.filter {
            (selectedCategory == "All" || it.category.equals(selectedCategory, ignoreCase = true)) &&
            (it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true))
        }
        
        list = when (sortBy) {
            "Title A-Z" -> list.sortedBy { it.title.lowercase() }
            "Title Z-A" -> list.sortedByDescending { it.title.lowercase() }
            "Total Chapters" -> list.sortedByDescending { it.totalChapters }
            "Unread Chapters" -> list.sortedByDescending { bookStatsMap[it.id]?.unreadCount ?: 0 }
            "Last Updated" -> list.sortedByDescending { it.updatedAt }
            else -> list.sortedBy { it.title.lowercase() }
        }
        list
    }

    // Glossary state
    var activeGlossaryBook by remember { mutableStateOf<BookEntity?>(null) }
    var showGlossaryDialog by remember { mutableStateOf(false) }

    // Deletion confirmation
    var activeDeleteBook by remember { mutableStateOf<BookEntity?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Export progress feedback
    var exportStatusMessage by remember { mutableStateOf("") }
    var showExportResultDialog by remember { mutableStateOf(false) }

    // Rescrape feedback
    var rescrapeResultMessage by remember { mutableStateOf("") }
    var showRescrapeResultDialog by remember { mutableStateOf(false) }

    // Reading Stats & Source Migration dialog state
    var showGlobalStatsDialog by remember { mutableStateOf(false) }
    var activeSourceMigrationBook by remember { mutableStateOf<BookEntity?>(null) }
    var showSourceMigrationDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    val headerAnimatable = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun animateHeaderTo(target: Float) {
        if (headerHeightPx <= 0f) return
        val clampedTarget = target.coerceIn(-headerHeightPx, 0f)
        headerAnimatable.snapTo(headerOffsetPx)
        headerAnimatable.animateTo(
            targetValue = clampedTarget,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 220,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        ) {
            headerOffsetPx = value
        }
    }

    fun updateHeaderDelta(delta: Float): Float {
        if (headerHeightPx <= 0f) return 0f
        val oldOffset = headerOffsetPx
        val newOffset = (headerOffsetPx + delta).coerceIn(-headerHeightPx, 0f)
        headerOffsetPx = newOffset
        return newOffset - oldOffset
    }

    fun settleHeader(velocity: Float = 0f) {
        if (headerHeightPx <= 0f) return
        coroutineScope.launch {
            val target = when {
                velocity < -250f -> -headerHeightPx
                velocity > 250f && lazyListState.firstVisibleItemIndex == 0 -> 0f
                headerOffsetPx > -headerHeightPx * 0.35f && lazyListState.firstVisibleItemIndex == 0 -> 0f
                else -> -headerHeightPx
            }
            animateHeaderTo(target)
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (headerHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                // Unidirectional: only collapse when scrolling down through the novel list
                if (delta < 0f && headerOffsetPx > -headerHeightPx) {
                    val consumed = updateHeaderDelta(delta)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (headerHeightPx <= 0f) return Offset.Zero
                // Only reveal header when at the very top of the list and pulling downward
                if (available.y > 0f && lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0) {
                    val consumedDelta = updateHeaderDelta(available.y)
                    return Offset(0f, consumedDelta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (headerHeightPx > 0f && (headerOffsetPx < 0f && headerOffsetPx > -headerHeightPx)) {
                    settleHeader(available.y)
                }
                return Velocity.Zero
            }
        }
    }

    // Automatically reveal header when user scrolls back to the very top
    LaunchedEffect(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset) {
        if (lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0) {
            if (headerOffsetPx < 0f) {
                animateHeaderTo(0f)
            }
        }
    }

    LaunchedEffect(currentMainTab) {
        if (headerOffsetPx != 0f) {
            animateHeaderTo(0f)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
    ) {
        // Collapsible Top Header Section with smooth 1:1 direct finger tracking and fling physics
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val fullHeight = placeable.height.toFloat()
                    if (fullHeight > 0f && headerHeightPx != fullHeight) {
                        headerHeightPx = fullHeight
                    }
                    val currentOffset = headerOffsetPx.coerceIn(-fullHeight, 0f)
                    val visibleHeight = (fullHeight + currentOffset).roundToInt().coerceAtLeast(0)
                    layout(placeable.width, visibleHeight) {
                        placeable.placeRelative(0, currentOffset.roundToInt())
                    }
                }
                .clipToBounds()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                val consumed = updateHeaderDelta(dragAmount)
                                if (consumed != 0f) {
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                settleHeader()
                            }
                        )
                    }
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "My Library",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.library.seedSampleNovels(force = true) },
                            enabled = !viewModel.library.isSeedingSamples
                        ) {
                            if (viewModel.library.isSeedingSamples) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoStories,
                                    contentDescription = "Load 500+ Ch Sample Novels",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        var showThemeMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showThemeMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Toggle Themes",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = showThemeMenu,
                                onDismissRequest = { showThemeMenu = false }
                            ) {
                                AppTheme.values().forEach { theme ->
                                    DropdownMenuItem(
                                        text = { Text(theme.displayName) },
                                        onClick = {
                                            viewModel.settings.updateTheme(theme)
                                            showThemeMenu = false
                                        },
                                        leadingIcon = {
                                            if (viewModel.currentTheme == theme) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )

                // Main Navigation Bar (Library vs Deleted Items)
                TabRow(
                    selectedTabIndex = currentMainTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = currentMainTab == 0,
                        onClick = { 
                            currentMainTab = 0 
                            coroutineScope.launch { animateHeaderTo(0f) }
                        },
                        text = { Text("Library (${books.size})", fontWeight = FontWeight.Bold) }
                    )
                    val totalDeleted = deletedBooks.size + deletedChapters.size
                    Tab(
                        selected = currentMainTab == 1,
                        onClick = { 
                            currentMainTab = 1 
                            coroutineScope.launch { animateHeaderTo(0f) }
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Deleted Items", fontWeight = FontWeight.Bold)
                                if (totalDeleted > 0) {
                                    Badge { Text("$totalDeleted") }
                                }
                            }
                        }
                    )
                }

                if (currentMainTab == 0) {
                    // Recommendation Card for Automatic Chapter Grabbing
                    var showAutoGrabRecommendation by remember { mutableStateOf(true) }
                    if (showAutoGrabRecommendation && !viewModel.autoGrabNewChapters) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Recommended: Auto-Grab Chapters",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Auto-grab newly released chapters strictly starting after your latest saved chapter. You can toggle this on and off anytime in Settings.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                                Button(
                                    onClick = {
                                        viewModel.autoGrabNewChapters = true
                                        showAutoGrabRecommendation = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Turn On", fontSize = 11.sp)
                                }
                                IconButton(
                                    onClick = { showAutoGrabRecommendation = false },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Control Card (Search, Sort, Import, Batch Panel)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search title, author...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("library_search_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var showSortMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { showSortMenu = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sort: $sortBy", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            listOf("Title A-Z", "Title Z-A", "Total Chapters", "Unread Chapters", "Last Updated").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        sortBy = option
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (viewModel.settings.enableReadingStats) {
                            IconButton(onClick = { showGlobalStatsDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.BarChart,
                                    contentDescription = "Reading Stats & Insights",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        IconButton(
                            onClick = { 
                                isBatchMode = !isBatchMode 
                                selectedBookIds = emptySet()
                            }
                        ) {
                            Icon(
                                imageVector = if (isBatchMode) Icons.Default.Close else Icons.Default.Checklist,
                                contentDescription = "Batch Actions",
                                tint = if (isBatchMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }

                        var showImportBackupMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showImportBackupMenu = true }) {
                                Icon(Icons.Default.SystemUpdateAlt, contentDescription = "Import / Sync", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = showImportBackupMenu,
                                onDismissRequest = { showImportBackupMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Import Local EPUB/TXT") },
                                    onClick = {
                                        showImportBackupMenu = false
                                        importLauncher.launch(
                                            arrayOf("*/*")
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Backup Library") },
                                    onClick = {
                                        showImportBackupMenu = false
                                        viewModel.library.backupLibrary(context) { success, pathOrError ->
                                            if (success) {
                                                shareFile(context, File(pathOrError), "application/json")
                                            } else {
                                                android.widget.Toast.makeText(context, "Backup failed: $pathOrError", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore Library Backup") },
                                    onClick = {
                                        showImportBackupMenu = false
                                        restoreLauncher.launch("application/json")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) }
                                )
                            }
                        }
                    }
                }

                if (isBatchMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected: ${selectedBookIds.size}",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 8.dp)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            var showBatchCategoryMenu by remember { mutableStateOf(false) }
                            Box {
                                Button(
                                    onClick = { showBatchCategoryMenu = true },
                                    enabled = selectedBookIds.isNotEmpty(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Shelf", fontSize = 12.sp)
                                }
                                DropdownMenu(
                                    expanded = showBatchCategoryMenu,
                                    onDismissRequest = { showBatchCategoryMenu = false }
                                ) {
                                    listOf("Reading", "Plan to Read", "Waiting", "Completed", "Favorites").forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text("Move to $cat") },
                                            onClick = {
                                                showBatchCategoryMenu = false
                                                viewModel.library.bulkUpdateCategory(selectedBookIds, cat) { msg ->
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    isBatchMode = false
                                                    selectedBookIds = emptySet()
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    if (selectedBookIds.isNotEmpty()) {
                                        viewModel.library.bulkReScrapeBooks(selectedBookIds) { msg ->
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                            isBatchMode = false
                                            selectedBookIds = emptySet()
                                        }
                                    }
                                },
                                enabled = selectedBookIds.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Re-scrape", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    if (selectedBookIds.isNotEmpty()) {
                                        viewModel.library.bulkDeleteBooks(selectedBookIds)
                                        isBatchMode = false
                                        selectedBookIds = emptySet()
                                    }
                                },
                                enabled = selectedBookIds.isNotEmpty(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Category Shelves / Tabs
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {},
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            categories.forEach { cat ->
                val count = if (cat == "All") books.size else books.count { it.category.equals(cat, ignoreCase = true) }
                Tab(
                    selected = selectedCategory == cat,
                    onClick = { 
                        selectedCategory = cat
                        viewModel.settings.updateActiveLibraryCategory(cat)
                    },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(cat, fontWeight = if (selectedCategory == cat) FontWeight.Bold else FontWeight.Normal)
                            Badge(
                                containerColor = if (selectedCategory == cat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selectedCategory == cat) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Text("$count", fontSize = 11.sp)
                            }
                        }
                    }
                )
            }
        }
                } // ends if (currentMainTab == 0) header controls
            } // ends Column inside Box
        } // ends collapsible header Box

        if (currentMainTab == 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
            if (filteredAndSortedBooks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight(1.1f)
                            .padding(top = 40.dp, bottom = 40.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        val consumed = updateHeaderDelta(dragAmount)
                                        if (consumed != 0f) {
                                            change.consume()
                                        }
                                    },
                                    onDragEnd = {
                                        settleHeader()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = "Empty Library",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No matching novels.",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            )
                            Text(
                                text = "Try modifying your search or download new books from 'Scrape' tab.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                ),
                                modifier = Modifier.padding(top = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.library.seedSampleNovels(force = true) },
                                enabled = !viewModel.library.isSeedingSamples,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("load_sample_novels_btn")
                            ) {
                                if (viewModel.library.isSeedingSamples) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Generating 5 Sample Novels...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AutoStories,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Load 5 Sample Novels (500+ Ch Each)")
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredAndSortedBooks) { book ->
                    LibraryBookItem(
                        book = book,
                        onRead = { onOpenBook(book.id) },
                        onListen = { viewModel.progress.startTtsForBook(book) },
                        onGlossary = {
                            activeGlossaryBook = book
                            showGlossaryDialog = true
                        },
                        onExportEpub = {
                            scope.launch {
                                val downloaded = viewModel.repository.getDownloadedChapterCount(book.id)
                                val missing = book.totalChapters - downloaded
                                if (missing > 0) {
                                    pendingExportBook = book
                                    pendingExportFormat = "EPUB"
                                    missingChaptersCount = missing
                                    showMissingExportDialog = true
                                } else {
                                    viewModel.library.compileFormat(book, "EPUB") { ok, path ->
                                        exportStatusMessage = if (ok) {
                                            "EPUB compiled successfully!\nFile: $path"
                                        } else {
                                            "Failed to compile EPUB!"
                                        }
                                        showExportResultDialog = true
                                        if (ok) shareFile(context, File(path), "application/epub+zip")
                                    }
                                }
                            }
                        },
                        onExportPdf = {
                            scope.launch {
                                val downloaded = viewModel.repository.getDownloadedChapterCount(book.id)
                                val missing = book.totalChapters - downloaded
                                if (missing > 0) {
                                    pendingExportBook = book
                                    pendingExportFormat = "PDF"
                                    missingChaptersCount = missing
                                    showMissingExportDialog = true
                                } else {
                                    viewModel.library.compileFormat(book, "PDF") { ok, path ->
                                        exportStatusMessage = if (ok) {
                                            "PDF compiled successfully!\nFile: $path"
                                        } else {
                                            "Failed to compile PDF!"
                                        }
                                        showExportResultDialog = true
                                        if (ok) shareFile(context, File(path), "application/pdf")
                                    }
                                }
                            }
                        },
                        onDelete = {
                            activeDeleteBook = book
                            showDeleteConfirmDialog = true
                        },
                        onRescrapeCorrupted = {
                            viewModel.scraping.rescrapeCorruptedChapters(book.id) { success, message ->
                                rescrapeResultMessage = message
                                showRescrapeResultDialog = true
                            }
                        },
                        onCheckNewChapters = { viewModel.scraping.checkForNewChapters(book) },
                        onDownloadBatch = { count ->
                            viewModel.scraping.downloadNextChapters(book.id, count)
                        },
                        onDownloadAllRemaining = {
                            pendingDownloadAllBook = book
                            showDownloadAllConfirmDialog = true
                        },
                        onRefreshToc = {
                            viewModel.scraping.refreshTableOfContents(book.id) { success, msg ->
                                rescrapeResultMessage = msg
                                showRescrapeResultDialog = true
                            }
                        },
                        isCheckingNewChapters = viewModel.isCheckingNewChapters && viewModel.checkingNewChaptersBookId == book.id,
                        isBatchMode = isBatchMode,
                        isSelected = selectedBookIds.contains(book.id),
                        onToggleSelect = {
                            selectedBookIds = if (selectedBookIds.contains(book.id)) {
                                selectedBookIds - book.id
                            } else {
                                selectedBookIds + book.id
                            }
                        },
                        unreadCount = bookStatsMap[book.id]?.unreadCount ?: 0,
                        downloadedCount = bookStatsMap[book.id]?.downloadedCount ?: 0,
                        bookStats = bookStatsMap[book.id],
                        onUpdateCategory = { newCategory ->
                            scope.launch {
                                viewModel.repository.updateBookCategory(book.id, newCategory)
                                android.widget.Toast.makeText(context, "Moved to '$newCategory'", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onMigrateSource = {
                            activeSourceMigrationBook = book
                            showSourceMigrationDialog = true
                        },
                        onEditDetails = {
                            activeEditBook = book
                            editTitle = book.title
                            editAuthor = book.author
                            editUrl = if (book.url.startsWith("local://")) "" else book.url
                            editCoverUrl = book.coverUrl ?: ""
                            editCoverLocalPath = book.coverLocalPath ?: ""
                            editCategory = book.category
                            showEditDetailsDialog = true
                        }
                    )
                }
            }
        }

        // Quick back-to-top button when header is hidden
            androidx.compose.animation.AnimatedVisibility(
                visible = (headerOffsetPx < -10f) || lazyListState.firstVisibleItemIndex > 0,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            animateHeaderTo(0f)
                            lazyListState.animateScrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Back to Top")
                }
            }
        }
    } else {
        // --- DELETED ITEMS (TRASH) SECTION ---
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabRow(
                        selectedTabIndex = trashSubTab,
                        modifier = Modifier.weight(1f),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Tab(
                            selected = trashSubTab == 0,
                            onClick = { trashSubTab = 0 },
                            text = { Text("Novels (${deletedBooks.size})", fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = trashSubTab == 1,
                            onClick = { trashSubTab = 1 },
                            text = { Text("Chapters (${deletedChapters.size})", fontWeight = FontWeight.Bold) }
                        )
                    }
                    if (deletedBooks.isNotEmpty() || deletedChapters.isNotEmpty()) {
                        IconButton(
                            onClick = { showEmptyTrashDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Empty Trash",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (trashSubTab == 0) {
                if (deletedBooks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No deleted novels in trash.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(deletedBooks) { book ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Author: ${book.author}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${book.totalChapters} chapters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Button(
                                        onClick = { viewModel.library.restoreBook(book.id) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Restore", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { viewModel.library.permanentlyDeleteBook(book.id) }
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Permanently", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (deletedChapters.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No deleted chapters in trash.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(deletedChapters) { ch ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(ch.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Chapter #${ch.chapterNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Button(
                                        onClick = { viewModel.library.restoreChapter(ch.id) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Restore", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { viewModel.library.permanentlyDeleteChapter(ch.id) }
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Permanently", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text("Empty Trash Permanently?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete all novels and chapters currently in trash? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.library.emptyTrash()
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Trash Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    } // End of main layout Column

    // --- Import / Restore Progress Dialog ---
    if (showImportProgressDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Processing File...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(importProgressMessage, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {}
        )
    }

    // --- Import Status Dialog ---
    if (showImportStatusDialog) {
        AlertDialog(
            onDismissRequest = { showImportStatusDialog = false },
            title = { Text("Import Status") },
            text = { Text(importStatusMessage) },
            confirmButton = {
                Button(onClick = { showImportStatusDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // --- Confirmation Delete Dialog ---
    if (showDeleteConfirmDialog && activeDeleteBook != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Novel?") },
            text = { Text("Are you sure you want to delete '${activeDeleteBook!!.title}'? This will delete all downloaded chapters, compiled ebooks, and customized glossaries permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.library.deleteBook(activeDeleteBook!!.id)
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Export Result Dialog ---
    if (showExportResultDialog) {
        AlertDialog(
            onDismissRequest = { showExportResultDialog = false },
            title = { Text("Export Completed") },
            text = { Text(exportStatusMessage) },
            confirmButton = {
                Button(onClick = { showExportResultDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // --- Rescrape Progress Dialog ---
    if (viewModel.isRescrapingBookId != null) {
        val progressPercent = (viewModel.rescrapeBookProgress * 100).toInt()
        AlertDialog(
            onDismissRequest = { /* non-dismissable */ },
            title = { Text("Rescraping Novel...") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Re-scraping corrupted/invalid chapters for this novel. This identifies chapters capturing login pages or error messages and restores them. Please hold on...")
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = viewModel.rescrapeBookProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Progress: $progressPercent%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {}
        )
    }

    // --- Rescrape Result Dialog ---
    if (showRescrapeResultDialog) {
        AlertDialog(
            onDismissRequest = { showRescrapeResultDialog = false },
            title = { Text("Rescrape Completed") },
            text = { Text(rescrapeResultMessage) },
            confirmButton = {
                Button(onClick = { showRescrapeResultDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // --- Configure Import Dialog ---
    if (showConfigureImportDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { showConfigureImportDialog = false },
            title = { Text("Configure Local Import") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "You are importing a local book file. To enable future chapter updates, re-scraping, or automatic chapter syncing in the future, you can optionally provide its online source URL below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = importCustomUrl,
                        onValueChange = { importCustomUrl = it },
                        label = { Text("Novel Source URL (Optional)") },
                        placeholder = { Text("https://tomatomtl.com/books/...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    Text(
                        text = "If left empty, the book will be imported purely as a local offline file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri!!
                        val isEpub = pendingImportIsEpub
                        val customUrl = importCustomUrl.trim().ifEmpty { null }
                        showConfigureImportDialog = false
                        importProgressMessage = "Importing local file... Please wait..."
                        showImportProgressDialog = true
                        viewModel.library.importLocalFile(context, uri, isEpub = isEpub, customUrl = customUrl) { success, msg ->
                            showImportProgressDialog = false
                            importStatusMessage = msg
                            showImportStatusDialog = true
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigureImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Edit Novel Details Dialog ---
    if (showEditDetailsDialog && activeEditBook != null) {
        AlertDialog(
            onDismissRequest = { showEditDetailsDialog = false },
            title = { Text("Edit Novel Details") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        label = { Text("Author") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("Source Scraping URL") },
                        placeholder = { Text("https://tomatomtl.com/books/...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Divider()

                    Text(
                        text = "Novel Cover Image",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    // Display current image or temporary edited image
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val coverModel = if (editCoverLocalPath.isNotEmpty()) {
                            File(editCoverLocalPath)
                        } else if (editCoverUrl.isNotEmpty()) {
                            editCoverUrl
                        } else {
                            null
                        }

                        if (coverModel != null) {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = "Cover preview",
                                modifier = Modifier
                                    .size(width = 60.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(width = 60.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { coverImageLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pick Gallery Image", fontSize = 12.sp)
                            }

                            if (editCoverLocalPath.isNotEmpty()) {
                                TextButton(
                                    onClick = { editCoverLocalPath = "" },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reset Local Photo", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editCoverUrl,
                        onValueChange = { editCoverUrl = it },
                        label = { Text("Cover Image URL (Fallback)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Divider()

                    Text(
                        text = "Shelf / Category",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Reading", "Plan to Read", "Waiting", "Completed", "Favorites").forEach { cat ->
                            FilterChip(
                                selected = editCategory == cat,
                                onClick = { editCategory = cat },
                                label = { Text(cat, fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bookId = activeEditBook!!.id
                        viewModel.library.updateBookDetails(
                            bookId = bookId,
                            newTitle = editTitle,
                            newAuthor = editAuthor,
                            newUrl = editUrl,
                            newCoverUrl = editCoverUrl.ifBlank { null },
                            newCoverLocalPath = editCoverLocalPath.ifBlank { null },
                            newCategory = editCategory
                        ) { success, msg ->
                            showEditDetailsDialog = false
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDetailsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Custom Glossary Dialog ---
    if (showGlossaryDialog && activeGlossaryBook != null) {
        GlossaryManagerDialog(
            book = activeGlossaryBook!!,
            viewModel = viewModel,
            onDismiss = { showGlossaryDialog = false }
        )
    }

    // --- New Chapters Found Dialog ---
    if (viewModel.showNewChaptersDialog) {
        val count = viewModel.newChaptersFoundCount
        val bookName = viewModel.checkedBookEntity?.title ?: "Novel"
        val startChapter = viewModel.scraping.newChaptersList.firstOrNull()?.chapterNumber
        val endChapter = viewModel.scraping.newChaptersList.lastOrNull()?.chapterNumber
        val rangeStr = if (startChapter != null && endChapter != null) {
            if (startChapter == endChapter) "Chapter $startChapter" else "Chapters $startChapter to $endChapter"
        } else ""

        AlertDialog(
            onDismissRequest = { viewModel.showNewChaptersDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("New Chapters Found")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (count > 0) {
                        Text(
                            text = "Found $count new chapter(s) for \"$bookName\"" +
                                (if (rangeStr.isNotEmpty()) " ($rangeStr)" else "") +
                                ".\n\nGrabbing will start strictly from your latest chapter."
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.autoGrabNewChapters = !viewModel.autoGrabNewChapters
                                }
                        ) {
                            Checkbox(
                                checked = viewModel.autoGrabNewChapters,
                                onCheckedChange = { viewModel.autoGrabNewChapters = it }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Always auto-grab new chapters without asking",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Text("No new chapters found for \"$bookName\". Everything is up to date!")
                    }
                }
            },
            confirmButton = {
                if (count > 0) {
                    Button(
                        onClick = {
                            viewModel.scraping.startScrapingNewChapters()
                            onNavigateToScrape()
                        }
                    ) {
                        Text("Download Now")
                    }
                } else {
                    Button(onClick = { viewModel.showNewChaptersDialog = false }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (count > 0) {
                    TextButton(onClick = { viewModel.showNewChaptersDialog = false }) {
                        Text("Later")
                    }
                }
            }
        )
    }

    // --- Missing Chapters Export Prompt Dialog ---
    if (showMissingExportDialog && pendingExportBook != null) {
        val book = pendingExportBook!!
        val fmt = pendingExportFormat ?: "EPUB"
        AlertDialog(
            onDismissRequest = { showMissingExportDialog = false },
            title = { Text("Undownloaded Chapters Detected") },
            text = {
                Text("'$missingChaptersCount' out of ${book.totalChapters} chapters are not downloaded yet.\n\nWould you like to download missing chapters first before compiling the $fmt ebook?")
            },
            confirmButton = {
                Button(onClick = {
                    showMissingExportDialog = false
                    viewModel.scraping.downloadAllRemainingChapters(book.id) { s, f ->
                        android.widget.Toast.makeText(context, "Downloaded $s chapters ($f failed). Ready for export!", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) {
                    Text("Download Missing First")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMissingExportDialog = false
                    viewModel.library.compileFormat(book, fmt) { ok, path ->
                        exportStatusMessage = if (ok) "$fmt compiled successfully!\nFile: $path" else "Failed to compile $fmt!"
                        showExportResultDialog = true
                        if (ok) shareFile(context, File(path), if (fmt == "EPUB") "application/epub+zip" else "application/pdf")
                    }
                }) {
                    Text("Export Downloaded Only")
                }
            }
        )
    }

    // --- Download All Remaining Confirmation Dialog ---
    if (showDownloadAllConfirmDialog && pendingDownloadAllBook != null) {
        val book = pendingDownloadAllBook!!
        val stats = bookStatsMap[book.id]
        val downloaded = stats?.downloadedCount ?: 0
        val total = stats?.totalChapters ?: book.totalChapters
        val pending = (total - downloaded).coerceAtLeast(0)
        val delaySec = viewModel.settings.requestDelayMs / 1000f
        val estMinutes = ((pending * delaySec) / 60).toInt().coerceAtLeast(1)

        AlertDialog(
            onDismissRequest = { showDownloadAllConfirmDialog = false },
            title = { Text("Download All Remaining Chapters?") },
            text = {
                Text("This will batch download $pending remaining chapters.\n\nEstimated time: ~${estMinutes} minute(s) (at ${delaySec}s delay per request).")
            },
            confirmButton = {
                Button(onClick = {
                    showDownloadAllConfirmDialog = false
                    viewModel.scraping.downloadAllRemainingChapters(book.id) { s, f ->
                        android.widget.Toast.makeText(context, "Completed! $s downloaded, $f failed.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Start Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Reading Stats Dialog ---
    if (showGlobalStatsDialog && viewModel.settings.enableReadingStats) {
        ReadingStatsDialog(
            viewModel = viewModel,
            onDismiss = { showGlobalStatsDialog = false }
        )
    }

    // --- Source Migration Dialog ---
    if (showSourceMigrationDialog && activeSourceMigrationBook != null) {
        SourceMigrationDialog(
            book = activeSourceMigrationBook!!,
            viewModel = viewModel,
            onDismiss = { showSourceMigrationDialog = false }
        )
    }
}

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

@Composable
fun GlossaryManagerDialog(
    book: BookEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val glossaries by viewModel.repository.getGlossaryFlow(book.id).collectAsState(emptyList())
    var origText by remember { mutableStateOf("") }
    var replText by remember { mutableStateOf("") }
    var editingGlossary by remember { mutableStateOf<GlossaryEntity?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Glossary: ${book.title}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add translation rules to replace machine-translated terms dynamically as you read offline.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                // AI Glossary Button Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "AI Glossary Assistant",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    )
                    Button(
                        onClick = { viewModel.aiFeatures.generateGlossaryWithAi(book) },
                        enabled = !viewModel.isGeneratingGlossary,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("ai_glossary_generate_btn")
                    ) {
                        if (viewModel.isGeneratingGlossary) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "AI", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Generate with AI", fontSize = 11.sp)
                        }
                    }
                }
                
                if (viewModel.glossaryStatusMessage.isNotEmpty()) {
                    Text(
                        text = viewModel.glossaryStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (viewModel.glossaryStatusMessage.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                Divider()

                // Input fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = origText,
                        onValueChange = { origText = it },
                        label = { Text(if (editingGlossary != null) "Edit Original" else "Original") },
                        placeholder = { Text("e.g. Master Lin") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp)
                    )

                    OutlinedTextField(
                        value = replText,
                        onValueChange = { replText = it },
                        label = { Text(if (editingGlossary != null) "Edit Replace" else "Replace") },
                        placeholder = { Text("e.g. Lin Feng") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(6.dp)
                    )

                    if (editingGlossary != null) {
                        IconButton(
                            onClick = {
                                editingGlossary = null
                                origText = ""
                                replText = ""
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                                .size(48.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Edit", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }

                    IconButton(
                        onClick = {
                            if (origText.trim().isNotEmpty()) {
                                scope.launch {
                                    val toSave = editingGlossary?.copy(
                                        originalText = origText.trim(),
                                        replacementText = replText.trim()
                                    ) ?: GlossaryEntity(
                                        bookId = book.id,
                                        originalText = origText.trim(),
                                        replacementText = replText.trim()
                                    )
                                    viewModel.repository.insertGlossary(toSave)
                                    editingGlossary = null
                                    origText = ""
                                    replText = ""
                                }
                            }
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                            .size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (editingGlossary != null) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = if (editingGlossary != null) "Save Edit" else "Add",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Divider()

                // List of glossary terms
                Text(
                    text = "Active Replacements (${glossaries.size})",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                )

                if (glossaries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No glossary terms defined.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(glossaries) { glossary ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = if (editingGlossary?.id == glossary.id) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = glossary.originalText,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = glossary.replacementText.ifEmpty { "(deleted)" },
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            editingGlossary = glossary
                                            origText = glossary.originalText
                                            replText = glossary.replacementText
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                if (editingGlossary?.id == glossary.id) {
                                                    editingGlossary = null
                                                    origText = ""
                                                    replText = ""
                                                }
                                                viewModel.repository.deleteGlossary(glossary)
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// Share Intent helper for compiled EPUB/PDF
private fun shareFile(context: Context, file: File, mimeType: String) {
    try {
        val authority = "${context.packageName}.provider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Ebook"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
