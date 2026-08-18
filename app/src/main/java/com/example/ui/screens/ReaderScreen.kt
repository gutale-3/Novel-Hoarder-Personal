package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.Font
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GlossaryEntity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Number of LazyColumn header items placed before paragraph items (1: Chapter Header, 2: Audio Player Bar / Banner, 3: Chapter Summary / Recap card)
private const val READER_HEADER_ITEMS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    viewModel: MainViewModel,
    initialChapterId: String? = null,
    initialParaIndex: Int = -1,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bookState by viewModel.repository.getBookFlow(bookId).collectAsState(null)
    val chapters by viewModel.repository.getChaptersFlow(bookId).collectAsState(emptyList())
    val glossaries by viewModel.repository.getGlossaryFlow(bookId).collectAsState(emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentChapterId by remember { mutableStateOf(initialChapterId ?: "") }
    val lazyListState = rememberLazyListState()

    var showControls by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val readerColors = when (viewModel.readerTheme) {
        "light" -> Pair(Color(0xFFFCFBF9), Color(0xFF1E1E1E))
        "sepia" -> Pair(Color(0xFFF4ECD8), Color(0xFF5B4636))
        "charcoal" -> Pair(Color(0xFF2C2C2C), Color(0xFFB0B0B0))
        "oled" -> Pair(Color(0xFF000000), Color(0xFFE0E0E0))
        "eink" -> Pair(Color(0xFFFFFFFF), Color(0xFF000000))
        else -> if (isDark) Pair(Color(0xFF0E1113), Color(0xFFE2E2E6)) else Pair(Color(0xFFFCFBF9), Color(0xFF1E1E1E))
    }
    val readerBgColor = readerColors.first
    val readerTextColor = readerColors.second

    val barBgColor = readerBgColor
    val barContentColor = readerTextColor

    val selectedFontFamily = when (viewModel.readerFontFamily) {
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        "custom" -> {
            val file = java.io.File(viewModel.readerCustomFontPath)
            if (file.exists()) {
                try {
                    FontFamily(Font(file))
                } catch (e: Exception) {
                    FontFamily.Serif
                }
            } else {
                FontFamily.Serif
            }
        }
        else -> FontFamily.Serif
    }

    // Automatic Ambient Syncing via Light Sensor
    DisposableEffect(viewModel.readerAmbientSyncEnabled) {
        if (viewModel.readerAmbientSyncEnabled) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
            
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    event?.let {
                        val lux = it.values[0]
                        if (lux < 15f) {
                            if (viewModel.readerTheme != "oled" && viewModel.readerTheme != "charcoal") {
                                viewModel.settings.updateReaderTheme("oled")
                            }
                        } else if (lux < 250f) {
                            if (viewModel.readerTheme != "sepia") {
                                viewModel.settings.updateReaderTheme("sepia")
                            }
                        } else {
                            if (viewModel.readerTheme != "light") {
                                viewModel.settings.updateReaderTheme("light")
                            }
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            
            lightSensor?.let {
                sensorManager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }
            
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    // Settings panel toggles
    var showSettingsDialog by remember { mutableStateOf(false) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedChapters by remember { mutableStateOf(setOf<String>()) }

    // UI Dialog state
    var showFindReplaceDialog by remember { mutableStateOf(false) }

    // Reset multi-select when drawer closes
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) {
            isMultiSelectMode = false
            selectedChapters = emptySet()
        }
    }

    // Load book's last read chapter
    LaunchedEffect(bookState, chapters) {
        val lastRead = bookState?.lastReadChapterId
        if (currentChapterId.isEmpty() && chapters.isNotEmpty()) {
            if (!lastRead.isNullOrEmpty() && chapters.any { it.id == lastRead }) {
                currentChapterId = lastRead
            } else {
                currentChapterId = chapters.first().id
            }
        }
    }

    // Active Chapter object
    val activeChapter = remember(chapters, currentChapterId) {
        if (currentChapterId.isEmpty()) {
            chapters.firstOrNull()
        } else {
            chapters.find { it.id == currentChapterId }
        }
    }

    // Whenever active chapter changes, save reading progress in database and prefetch next chapters
    LaunchedEffect(activeChapter) {
        if (activeChapter != null && bookState != null) {
            viewModel.repository.updateBook(bookState!!.copy(lastReadChapterId = activeChapter.id))
            viewModel.aiFeatures.triggerAutoDownloadNextChapters(bookState!!, activeChapter)
            viewModel.tts.primeChapterOpening(activeChapter.content)
        }
    }

    // Keep UI chapter in sync with TTS playing chapter
    LaunchedEffect(viewModel.ttsPlayingChapter) {
        if (viewModel.ttsPlayingBook?.id == bookId) {
            val ttsChId = viewModel.ttsPlayingChapter?.id
            if (!ttsChId.isNullOrEmpty() && ttsChId != currentChapterId) {
                currentChapterId = ttsChId
            }
        }
    }

    var drawerTabSelected by remember { mutableStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var chapterSubTab by remember { mutableStateOf(0) } // 0 = Active, 1 = Archived
    var showAutoArchiveSettingDialog by remember { mutableStateOf(false) }
    val archivedChapters by viewModel.progress.getArchivedChaptersFlow(bookId).collectAsState(emptyList())

    var showAddSingleChapterDialog by remember { mutableStateOf(false) }
    var addChTitle by remember { mutableStateOf("") }
    var addChContent by remember { mutableStateOf("") }
    var addChNumberStr by remember { mutableStateOf("") }
    var addChInsertPosition by remember { mutableStateOf(-1) } // -1 = end, 0 = beginning, >0 = after that index

    var showResequenceDialog by remember { mutableStateOf(false) }
    var resequencePrefix by remember { mutableStateOf("Chapter") }
    var resequenceStartNumberStr by remember { mutableStateOf("1") }

    var showBookmarkDialog by remember { mutableStateOf(false) }
    var selectedParaIndexForBookmark by remember { mutableStateOf<Int?>(null) }
    var selectedParaTextForBookmark by remember { mutableStateOf("") }
    var bookmarkNoteText by remember { mutableStateOf("") }
    val bookmarks by viewModel.repository.getBookmarksFlow(bookId).collectAsState(emptyList())

    var showDeleteChaptersConfirmDialog by remember { mutableStateOf(false) }
    var chaptersToDelete by remember { mutableStateOf(emptyList<String>()) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                
                TabRow(
                    selectedTabIndex = drawerTabSelected,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = drawerTabSelected == 0,
                        onClick = { drawerTabSelected = 0 },
                        text = { Text("Chapters", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = drawerTabSelected == 1,
                        onClick = { drawerTabSelected = 1 },
                        text = { Text("Bookmarks", fontWeight = FontWeight.Bold) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (drawerTabSelected == 1) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (bookmarks.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No saved bookmarks or highlights in this book yet.\nTap any paragraph to save highlights with thoughts!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(bookmarks) { b ->
                                val chName = chapters.find { it.id == b.chapterId }?.title ?: "Unknown Chapter"
                                Card(
                                    onClick = {
                                        scope.launch {
                                            currentChapterId = b.chapterId
                                            drawerState.close()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = chName,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.tertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        viewModel.repository.deleteBookmark(b.id)
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Bookmark",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        Text(
                                            text = b.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (b.note.isNotEmpty()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                                    .padding(6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Note,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                                                    tint = MaterialTheme.colorScheme.secondary
                                                )
                                                Text(
                                                    text = b.note,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMultiSelectMode) "Selected (${selectedChapters.size})" else "Chapters",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isMultiSelectMode) {
                                IconButton(onClick = {
                                    val currentList = if (chapterSubTab == 0) chapters else archivedChapters
                                    if (selectedChapters.size == currentList.size) {
                                        selectedChapters = emptySet()
                                    } else {
                                        selectedChapters = currentList.map { it.id }.toSet()
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (selectedChapters.size == (if (chapterSubTab == 0) chapters.size else archivedChapters.size)) Icons.Default.SelectAll else Icons.Default.Checklist,
                                        contentDescription = "Select All"
                                    )
                                }
                                IconButton(onClick = {
                                    isMultiSelectMode = false
                                    selectedChapters = emptySet()
                                }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel Multi-select")
                                }
                            } else {
                                IconButton(onClick = {
                                    showAddSingleChapterDialog = true
                                }) {
                                    Icon(imageVector = Icons.Default.PostAdd, contentDescription = "Add Single Chapter", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    showResequenceDialog = true
                                }) {
                                    Icon(imageVector = Icons.Default.List, contentDescription = "Auto-Resequence Chapters", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    showAutoArchiveSettingDialog = true
                                }) {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Auto-Archive Settings", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    isMultiSelectMode = true
                                    selectedChapters = emptySet()
                                }) {
                                    Icon(imageVector = Icons.Default.EditCalendar, contentDescription = "Enable Multi-select")
                                }
                            }
                        }
                    }

                    TabRow(
                        selectedTabIndex = chapterSubTab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Tab(
                            selected = chapterSubTab == 0,
                            onClick = { 
                                chapterSubTab = 0 
                                isMultiSelectMode = false
                                selectedChapters = emptySet()
                            },
                            text = { Text("Active (${chapters.size})", fontSize = 14.sp) }
                        )
                        Tab(
                            selected = chapterSubTab == 1,
                            onClick = { 
                                chapterSubTab = 1 
                                isMultiSelectMode = false
                                selectedChapters = emptySet()
                            },
                            text = { Text("Archived (${archivedChapters.size})", fontSize = 14.sp) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (isMultiSelectMode && selectedChapters.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (chapterSubTab == 0) {
                                TextButton(
                                    onClick = {
                                        viewModel.progress.updateChaptersReadStatus(selectedChapters.toList(), true)
                                        isMultiSelectMode = false
                                        selectedChapters = emptySet()
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Read", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                
                                HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))
                                
                                TextButton(
                                    onClick = {
                                        viewModel.progress.updateChaptersReadStatus(selectedChapters.toList(), false)
                                        isMultiSelectMode = false
                                        selectedChapters = emptySet()
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.RemoveDone, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Unread", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }

                                HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))

                                TextButton(
                                    onClick = {
                                        viewModel.progress.updateChaptersArchiveStatus(selectedChapters.toList(), true)
                                        isMultiSelectMode = false
                                        selectedChapters = emptySet()
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Archive", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else {
                                TextButton(
                                    onClick = {
                                        viewModel.progress.updateChaptersArchiveStatus(selectedChapters.toList(), false)
                                        isMultiSelectMode = false
                                        selectedChapters = emptySet()
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restore", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            HorizontalDivider(modifier = Modifier.height(20.dp).width(1.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))

                            TextButton(
                                onClick = {
                                    chaptersToDelete = selectedChapters.toList()
                                    showDeleteChaptersConfirmDialog = true
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(4.dp))
                                Text("Delete", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    
                    HorizontalDivider()

                    val currentList = if (chapterSubTab == 0) chapters else archivedChapters

                    if (currentList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (chapterSubTab == 0) "No active chapters." else "No archived chapters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(currentList) { ch ->
                                val isCurrent = ch.id == currentChapterId
                                val isSelected = selectedChapters.contains(ch.id)
                                NavigationDrawerItem(
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isMultiSelectMode) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        selectedChapters = if (checked) {
                                                            selectedChapters + ch.id
                                                        } else {
                                                            selectedChapters - ch.id
                                                        }
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                            } else {
                                                if (chapterSubTab == 0) {
                                                    // Visual indicators for Read / Unread in standard view
                                                    Icon(
                                                        imageVector = if (ch.isRead) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                        contentDescription = if (ch.isRead) "Read" else "Unread",
                                                        tint = if (ch.isRead) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) 
                                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        modifier = Modifier.size(20.dp).clickable {
                                                            viewModel.progress.updateChaptersReadStatus(listOf(ch.id), !ch.isRead)
                                                        }
                                                    )
                                                } else {
                                                    // Archived icon
                                                    Icon(
                                                        imageVector = Icons.Default.Archive,
                                                        contentDescription = "Archived",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(20.dp).clickable {
                                                            viewModel.progress.updateChaptersArchiveStatus(listOf(ch.id), false)
                                                        }
                                                    )
                                                }
                                            }
                                            
                                            val isStub = ch.content.isBlank()
                                            Text(
                                                text = ch.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary 
                                                        else if (isStub) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                                        else if (ch.isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                        else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )

                                            if (isStub && !isMultiSelectMode) {
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            viewModel.scraping.downloadSingleChapter(ch)
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CloudDownload,
                                                        contentDescription = "Download Chapter",
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                                    )
                                                }
                                            }

                                            if (!isMultiSelectMode) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = { viewModel.library.moveChapterUp(bookId, ch.id) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowUp,
                                                            contentDescription = "Move Up",
                                                            modifier = Modifier.size(18.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { viewModel.library.moveChapterDown(bookId, ch.id) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Move Down",
                                                            modifier = Modifier.size(18.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    if (chapterSubTab == 0) {
                                                        if (viewModel.rescrapingChapterId == ch.id) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(20.dp),
                                                                strokeWidth = 2.dp,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                        } else {
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.scraping.rescrapeSingleChapter(ch) { success, msg ->
                                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                },
                                                                modifier = Modifier.size(24.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Refresh,
                                                                    contentDescription = "Rescrape Chapter",
                                                                    modifier = Modifier.size(16.dp),
                                                                    tint = MaterialTheme.colorScheme.primary
                                                                )
                                                            }
                                                        }
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            chaptersToDelete = listOf(ch.id)
                                                            showDeleteChaptersConfirmDialog = true
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "Delete Chapter",
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    selected = !isMultiSelectMode && isCurrent,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            selectedChapters = if (isSelected) {
                                                selectedChapters - ch.id
                                            } else {
                                                selectedChapters + ch.id
                                            }
                                        } else {
                                            scope.launch {
                                                currentChapterId = ch.id
                                                drawerState.close()
                                            }
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    val isCompact = configuration.screenWidthDp < 600
                    var showMenu by remember { mutableStateOf(false) }

                    TopAppBar(
                        title = {
                            Text(
                                text = bookState?.title ?: "Offline Reader",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            // Audio / TTS Narration (Always visible)
                            IconButton(onClick = {
                                val activeCh = activeChapter
                                val bk = bookState
                                if (activeCh != null && bk != null) {
                                    if (viewModel.ttsIsPlaying && viewModel.ttsPlayingChapter?.id == activeCh.id) {
                                        viewModel.tts.pauseTts()
                                    } else if (viewModel.ttsIsPaused && viewModel.ttsPlayingChapter?.id == activeCh.id) {
                                        viewModel.tts.resumeTts()
                                    } else {
                                        viewModel.tts.speak(activeCh.content, bk, activeCh)
                                    }
                                }
                            }) {
                                val icon = if (viewModel.ttsIsPlaying && viewModel.ttsPlayingChapter?.id == activeChapter?.id) {
                                    Icons.Default.VolumeOff
                                } else {
                                    Icons.Default.RecordVoiceOver
                                }
                                Icon(imageVector = icon, contentDescription = "Listen to Chapter")
                            }

                            // Open TOC drawer (Always visible)
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(imageVector = Icons.Default.FormatListNumbered, contentDescription = "Chapter Index")
                            }

                            if (!isCompact) {
                                // Text style settings (Visible on larger screens)
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(imageVector = Icons.Default.TextFormat, contentDescription = "Font settings")
                                }

                                // Rescrape Current Chapter (Visible on larger screens)
                                IconButton(onClick = {
                                    val activeCh = activeChapter
                                    if (activeCh != null) {
                                        viewModel.scraping.rescrapeSingleChapter(activeCh) { success, msg ->
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    if (viewModel.rescrapingChapterId == activeChapter?.id) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescrape Chapter")
                                    }
                                }
                            } else {
                                // Dropdown/Overflow Menu (Visible on compact mobile screens)
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More options")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Font Settings") },
                                            leadingIcon = { Icon(Icons.Default.TextFormat, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                            onClick = {
                                                showMenu = false
                                                showSettingsDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                if (viewModel.rescrapingChapterId == activeChapter?.id) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("Rescraping...")
                                                    }
                                                } else {
                                                    Text("Rescrape Chapter")
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                            enabled = viewModel.rescrapingChapterId != activeChapter?.id,
                                            onClick = {
                                                showMenu = false
                                                val activeCh = activeChapter
                                                if (activeCh != null) {
                                                    viewModel.scraping.rescrapeSingleChapter(activeCh) { success, msg ->
                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = barBgColor,
                            titleContentColor = barContentColor,
                            navigationIconContentColor = barContentColor,
                            actionIconContentColor = barContentColor
                        )
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    // Reader Navigation controls (Previous / Next Chapters)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = if (viewModel.readerTheme == "eink") 0.dp else 8.dp,
                        color = barBgColor,
                        contentColor = barContentColor,
                        border = if (viewModel.readerTheme == "eink") BorderStroke(1.dp, Color.Black) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
                            val hasPrev = currentIdx > 0
                            val hasNext = currentIdx < chapters.size - 1

                            TextButton(
                                onClick = {
                                    if (hasPrev) currentChapterId = chapters[currentIdx - 1].id
                                },
                                enabled = hasPrev,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = barContentColor,
                                    disabledContentColor = barContentColor.copy(alpha = 0.38f)
                                ),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Prev")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Previous", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            Text(
                                text = if (chapters.isNotEmpty() && currentIdx != -1) {
                                    "${currentIdx + 1} / ${chapters.size}"
                                } else "",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = barContentColor
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp),
                                maxLines = 1
                            )

                            TextButton(
                                onClick = {
                                    if (hasNext) currentChapterId = chapters[currentIdx + 1].id
                                },
                                enabled = hasNext,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = barContentColor,
                                    disabledContentColor = barContentColor.copy(alpha = 0.38f)
                                ),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Text("Next", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next")
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(readerBgColor)
                    .padding(innerPadding)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls }
                        )
                    }
                    .testTag("reader_canvas")
            ) {
                if (activeChapter == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Loading chapter contents offline...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground)
                            )
                        }
                    }
                } else {
                    // Actual reading canvas (scrollable with generous line spacing and custom font size!)

                    // Restore scroll state or reset to 0 on chapter change
                    LaunchedEffect(currentChapterId) {
                        if (!currentChapterId.isNullOrEmpty()) {
                            if (currentChapterId == initialChapterId && initialParaIndex >= 0) {
                                lazyListState.scrollToItem(initialParaIndex + READER_HEADER_ITEMS)
                            } else {
                                val savedPara = viewModel.progress.getSavedParagraphIndex(bookId, currentChapterId)
                                if (savedPara > 0) {
                                    lazyListState.scrollToItem(savedPara + READER_HEADER_ITEMS)
                                } else {
                                    lazyListState.scrollToItem(0)
                                }
                            }
                        } else {
                            lazyListState.scrollToItem(0)
                        }
                    }

                    // Auto-follow scroll: Keep the active TTS paragraph centered and in full view near top
                    val activePara = viewModel.ttsActiveParagraphIndex ?: -1
                    val isTtsPlaying = viewModel.ttsIsPlaying
                    val playingChapterId = viewModel.ttsPlayingChapter?.id
                    val autoScrollEnabled = viewModel.ttsAutoScrollEnabled

                    // Detect user-initiated drag to temporarily suspend auto-following
                    val isUserDragging by lazyListState.interactionSource.collectIsDraggedAsState()
                    var isAutoScrollSuspended by remember { mutableStateOf(false) }

                    LaunchedEffect(currentChapterId) {
                        isAutoScrollSuspended = false
                    }

                    LaunchedEffect(isUserDragging, isTtsPlaying) {
                        if (isUserDragging && isTtsPlaying) {
                            isAutoScrollSuspended = true
                        }
                    }

                    // Auto-re-engage auto-follow after 5 seconds of no manual scrolling
                    LaunchedEffect(isAutoScrollSuspended, isUserDragging, activePara) {
                        if (isAutoScrollSuspended && !isUserDragging) {
                            delay(5000L)
                            isAutoScrollSuspended = false
                        }
                    }

                    LaunchedEffect(activePara, isTtsPlaying, playingChapterId, autoScrollEnabled, activeChapter?.id, isAutoScrollSuspended) {
                        if (autoScrollEnabled && !isAutoScrollSuspended && isTtsPlaying && playingChapterId == activeChapter?.id) {
                            val targetItemIndex = if (activePara < 0) 0 else activePara + READER_HEADER_ITEMS
                            val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
                            val scrollOffset = if (viewportHeight > 0) -viewportHeight / 4 else -120
                            lazyListState.animateScrollToItem(targetItemIndex, scrollOffset)
                        }
                    }

                    val chapterTextToRender = activeChapter.content
                    val paragraphs = remember(chapterTextToRender) {
                        chapterTextToRender.split("\n").filter { it.trim().isNotEmpty() }
                    }

                    // Real-time memory tracking of current paragraph being read or listened to
                    val currentParagraphIndex by remember {
                        derivedStateOf {
                            if (viewModel.ttsIsPlaying && viewModel.ttsPlayingChapter?.id == activeChapter.id) {
                                (viewModel.ttsActiveParagraphIndex ?: -1).coerceIn(0, paragraphs.size - 1)
                            } else {
                                if (paragraphs.isNotEmpty()) {
                                    (lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS).coerceIn(0, paragraphs.size - 1)
                                } else {
                                    0
                                }
                            }
                        }
                    }

                    val currentParagraphText by remember {
                        derivedStateOf {
                            if (currentParagraphIndex in paragraphs.indices) {
                                paragraphs[currentParagraphIndex]
                            } else {
                                ""
                            }
                        }
                    }

                    // Auto-save reading progress and create bookmark when user leaves the novel or switches off phone
                    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, activeChapter.id, currentChapterId) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                                if (currentParagraphIndex >= 0) {
                                    viewModel.progress.autoSaveProgressAndBookmark(
                                        bookId = bookId,
                                        chapterId = activeChapter.id,
                                        paragraphIndex = currentParagraphIndex,
                                        paragraphText = currentParagraphText
                                    )
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            if (currentParagraphIndex >= 0) {
                                viewModel.progress.autoSaveProgressAndBookmark(
                                    bookId = bookId,
                                    chapterId = activeChapter.id,
                                    paragraphIndex = currentParagraphIndex,
                                    paragraphText = currentParagraphText
                                )
                            }
                        }
                    }

                    // Expose total paragraphs to viewmodel for TTS player bar seek slider
                    LaunchedEffect(paragraphs.size) {
                        viewModel.ttsTotalParagraphs = paragraphs.size
                    }

                    // Background prefetching of next chapter when auto-fetch is enabled
                    LaunchedEffect(activeChapter.id, activeChapter.content) {
                        if (activeChapter.content.isNotBlank() && viewModel.settings.autoFetchWhileReading) {
                            val currentIndex = chapters.indexOfFirst { it.id == activeChapter.id }
                            if (currentIndex >= 0) {
                                val nextChap = chapters.getOrNull(currentIndex + 1)
                                if (nextChap != null && nextChap.content.isBlank()) {
                                    delay(1500L)
                                    viewModel.scraping.downloadSingleChapter(nextChap)
                                }
                            }
                        }
                    }

                    if (activeChapter.content.isBlank()) {
                        var isDownloadingChapter by remember(activeChapter.id) { mutableStateOf(false) }
                        var downloadError by remember(activeChapter.id) { mutableStateOf<String?>(null) }

                        LaunchedEffect(activeChapter.id) {
                            if (viewModel.settings.autoFetchWhileReading) {
                                isDownloadingChapter = true
                                downloadError = null
                                val res = viewModel.scraping.downloadSingleChapter(activeChapter)
                                isDownloadingChapter = false
                                if (res.isFailure) {
                                    downloadError = res.exceptionOrNull()?.message ?: "Failed to download chapter"
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier
                                    .widthIn(max = 400.dp)
                                    .padding(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = activeChapter.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "Not downloaded yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (isDownloadingChapter) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(32.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 3.dp
                                        )
                                        Text(
                                            text = "Downloading chapter...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        if (downloadError != null) {
                                            Text(
                                                text = downloadError!!,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isDownloadingChapter = true
                                                    downloadError = null
                                                    val res = viewModel.scraping.downloadSingleChapter(activeChapter)
                                                    isDownloadingChapter = false
                                                    if (res.isFailure) {
                                                        downloadError = res.exceptionOrNull()?.message ?: "Failed to download chapter"
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(if (downloadError != null) "Retry Download" else "Download Chapter")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 800.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = viewModel.readerMargin.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 80.dp)
                    ) {
                        // Title
                        item {
                            Text(
                                text = activeChapter.title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    color = if (viewModel.readerTheme == "eink") Color.Black else MaterialTheme.colorScheme.primary,
                                    fontFamily = selectedFontFamily
                                ),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // Find & Replace bar
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showFindReplaceDialog = true },
                                    modifier = Modifier.testTag("bulk_replace_icon_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = "Find & Replace",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Find & Replace", fontSize = 12.sp)
                                }
                            }
                        }

                        // Divider
                        item {
                            Divider()
                        }

                        // Paragraphs
                        items(paragraphs.size) { idx ->
                            val para = paragraphs[idx]
                            val isReadingThisPara = (viewModel.ttsIsPlaying || viewModel.ttsIsPaused) &&
                                    viewModel.ttsPlayingBook?.id == bookState?.id &&
                                    viewModel.ttsPlayingChapter?.id == activeChapter.id &&
                                    idx == viewModel.ttsActiveParagraphIndex

                            val hasExistingBookmark = bookmarks.find { it.chapterId == currentChapterId && it.paragraphIndex == idx }

                            val textAndStyleModifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = when {
                                        isReadingThisPara -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        hasExistingBookmark != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                                        else -> Color.Transparent
                                    },
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .pointerInput(idx, para, hasExistingBookmark) {
                                    detectTapGestures(
                                        onTap = {
                                            showControls = !showControls
                                        },
                                        onLongPress = {
                                            selectedParaIndexForBookmark = idx
                                            selectedParaTextForBookmark = para
                                            bookmarkNoteText = hasExistingBookmark?.note ?: ""
                                            showBookmarkDialog = true
                                        }
                                    )
                                }
                                .padding(vertical = 4.dp, horizontal = 8.dp)

                            val paragraphTextColor = if (isReadingThisPara) {
                                if (viewModel.readerTheme == "eink") Color.Black else MaterialTheme.colorScheme.primary
                            } else {
                                readerTextColor
                            }

                            val paragraphAlpha = if (viewModel.focusModeEnabled && (viewModel.ttsIsPlaying || viewModel.ttsIsPaused) &&
                                    viewModel.ttsPlayingBook?.id == bookState?.id &&
                                    viewModel.ttsPlayingChapter?.id == activeChapter.id) {
                                if (isReadingThisPara) 1.0f else 0.35f
                            } else {
                                1.0f
                            }

                            val hyphenatedText = softHyphenateText(para.trim(), viewModel.readerHyphenationEnabled)

                            Text(
                                text = highlightGlossaryTerms(
                                    text = "      " + hyphenatedText,
                                    glossaries = glossaries,
                                    highlightColor = if (viewModel.readerTheme == "eink") Color.Black else MaterialTheme.colorScheme.primary
                                ),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = viewModel.readerFontSize.sp,
                                    lineHeight = (viewModel.readerFontSize * viewModel.readerLineHeight).sp,
                                    fontFamily = selectedFontFamily,
                                    letterSpacing = viewModel.readerLetterSpacing.em,
                                    color = paragraphTextColor.copy(alpha = paragraphAlpha),
                                    fontWeight = if (isReadingThisPara) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = if (viewModel.readerJustificationEnabled) TextAlign.Justify else TextAlign.Start
                                ),
                                modifier = textAndStyleModifier
                            )
                        }
                    }

                    // Floating "Resume auto-scroll" chip when auto-follow is temporarily suspended by user manual drag
                    AnimatedVisibility(
                        visible = isAutoScrollSuspended && autoScrollEnabled && isTtsPlaying && playingChapterId == activeChapter.id,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    ) {
                        ElevatedButton(
                            onClick = {
                                isAutoScrollSuspended = false
                                val targetItemIndex = if (activePara < 0) 0 else activePara + READER_HEADER_ITEMS
                                scope.launch {
                                    val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
                                    val scrollOffset = if (viewportHeight > 0) -viewportHeight / 4 else -120
                                    lazyListState.animateScrollToItem(targetItemIndex, scrollOffset)
                                }
                            },
                            colors = ButtonDefaults.elevatedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 6.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Resume auto-scroll",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
            }
        }
    }

    // --- Dynamic Text Style Controls Dialog ---
    if (showSettingsDialog) {
        val fontPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let {
                viewModel.settings.importCustomFont(context, it)
            }
        }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Reading Style Options", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Background Theme Selector
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Background Theme",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("Auto", "auto", MaterialTheme.colorScheme.background),
                                    Triple("Pristine", "light", Color(0xFFFCFBF9)),
                                    Triple("Sepia", "sepia", Color(0xFFF4ECD8))
                                ).forEach { (name, themeKey, color) ->
                                    val selected = viewModel.readerTheme == themeKey
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(color)
                                            .clickable { viewModel.settings.updateReaderTheme(themeKey) }
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (themeKey == "light" || themeKey == "sepia" || (themeKey == "auto" && !isDark)) Color.Black else Color.White
                                            )
                                        )
                                    }
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("Charcoal", "charcoal", Color(0xFF2C2C2C)),
                                    Triple("OLED", "oled", Color(0xFF000000)),
                                    Triple("E-Ink", "eink", Color(0xFFFFFFFF))
                                ).forEach { (name, themeKey, color) ->
                                    val selected = viewModel.readerTheme == themeKey
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(color)
                                            .clickable { viewModel.settings.updateReaderTheme(themeKey) }
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (themeKey == "eink") Color.Black else Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Font Size Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Font Size: ${viewModel.readerFontSize} sp",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Slider(
                            value = viewModel.readerFontSize.toFloat(),
                            onValueChange = { viewModel.settings.updateFontSize(it.toInt()) },
                            valueRange = 12f..40f,
                            steps = 28
                        )
                    }

                    // 3. Line Spacing Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Line Spacing: ${"%.1f".format(viewModel.readerLineHeight)}x",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Slider(
                            value = viewModel.readerLineHeight,
                            onValueChange = { viewModel.settings.updateReaderLineHeight(it) },
                            valueRange = 1.0f..2.5f
                        )
                    }

                    // 4. Paragraph Margin Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Paragraph Padding: ${viewModel.readerMargin} dp",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Slider(
                            value = viewModel.readerMargin.toFloat(),
                            onValueChange = { viewModel.settings.updateReaderMargin(it.toInt()) },
                            valueRange = 8f..48f,
                            steps = 5
                        )
                    }

                    // 5. Letter Spacing Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Letter Spacing: ${"%.2f".format(viewModel.readerLetterSpacing)} em",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Slider(
                            value = viewModel.readerLetterSpacing,
                            onValueChange = { viewModel.settings.updateReaderLetterSpacing(it) },
                            valueRange = -0.05f..0.25f
                        )
                    }

                    // 6. Font Family Selector
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Font Style",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Pair("Serif", "serif"),
                                Pair("Sans", "sans"),
                                Pair("Mono", "mono")
                            ).forEach { (name, fontKey) ->
                                val selected = viewModel.readerFontFamily == fontKey
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.settings.updateFontFamily(fontKey) },
                                    label = { Text(name) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                        
                        if (viewModel.readerCustomFontPath.isNotEmpty()) {
                            val selected = viewModel.readerFontFamily == "custom"
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.settings.updateFontFamily("custom") },
                                label = { Text("Custom: ${viewModel.readerCustomFontName}") },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedButton(
                            onClick = { fontPickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = "Upload Font")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Upload Custom Font (.ttf/.otf)", fontWeight = FontWeight.Bold)
                        }
                    }

                    Divider()

                    // 7. Ambient Light Sensor Sync
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ambient Light Sync",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Automatically adapt contrast based on room lighting",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.readerAmbientSyncEnabled,
                            onCheckedChange = { viewModel.settings.updateAmbientSyncEnabled(it) }
                        )
                    }

                    // 8. Text Justification
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Text Justification",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Align text with both left and right margins",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.readerJustificationEnabled,
                            onCheckedChange = { viewModel.settings.updateJustificationEnabled(it) }
                        )
                    }

                    // 9. Soft Hyphenation Engine
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Soft Hyphenation Engine",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Break long words with soft-hyphens for better spacing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.readerHyphenationEnabled,
                            onCheckedChange = { viewModel.settings.updateHyphenationEnabled(it) }
                        )
                    }

                    // 10. Focus Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Focus Mode",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Dim non-active paragraphs during TTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.focusModeEnabled,
                            onCheckedChange = { viewModel.tts.toggleFocusMode() }
                        )
                    }

                    // 11. Auto-Scroll Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Follow Spoken Text",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Automatically scroll page to active sentence",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.ttsAutoScrollEnabled,
                            onCheckedChange = { viewModel.tts.toggleTtsAutoScroll() }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showDeleteChaptersConfirmDialog && chaptersToDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showDeleteChaptersConfirmDialog = false
                chaptersToDelete = emptyList()
            },
            title = { Text("Delete Chapters?", fontWeight = FontWeight.Bold) },
            text = {
                val count = chaptersToDelete.size
                Text("Are you sure you want to delete ${if (count == 1) "this chapter" else "$count selected chapters"}? This action is permanent and cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentActiveChapterIsDeleted = chaptersToDelete.contains(currentChapterId)
                        viewModel.progress.deleteChapters(bookId, chaptersToDelete) { msg ->
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            if (currentActiveChapterIsDeleted) {
                                scope.launch {
                                    val remaining = chapters.filterNot { chaptersToDelete.contains(it.id) }
                                    if (remaining.isNotEmpty()) {
                                        currentChapterId = remaining.first().id
                                    } else {
                                        onBack()
                                    }
                                }
                            }
                        }
                        showDeleteChaptersConfirmDialog = false
                        chaptersToDelete = emptyList()
                        isMultiSelectMode = false
                        selectedChapters = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteChaptersConfirmDialog = false
                    chaptersToDelete = emptyList()
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAutoArchiveSettingDialog && bookState != null) {
        val currentHours = bookState?.autoArchiveHours ?: 0
        AlertDialog(
            onDismissRequest = { showAutoArchiveSettingDialog = false },
            title = { Text("Auto-Archive Settings", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "After chapters stay in \"Read\" status for the selected duration, they will automatically be moved to the Archive. This helps keep your Chapter List clean!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val options = listOf(
                        0 to "Never (Disable)",
                        1 to "1 Hour",
                        2 to "2 Hours",
                        3 to "3 Hours",
                        5 to "5 Hours",
                        10 to "10 Hours",
                        24 to "1 Day"
                    )
                    
                    options.forEach { (hours, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        viewModel.repository.updateBook(bookState!!.copy(autoArchiveHours = hours))
                                        showAutoArchiveSettingDialog = false
                                        android.widget.Toast.makeText(context, "Auto-archive set to $label", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = currentHours == hours,
                                onClick = null
                            )
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoArchiveSettingDialog = false }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showFindReplaceDialog) {
        FindReplaceDialog(
            bookId = bookId,
            viewModel = viewModel,
            onDismiss = { showFindReplaceDialog = false }
        )
    }

    if (showBookmarkDialog && selectedParaIndexForBookmark != null) {
        val existingBookmark = bookmarks.find { it.chapterId == currentChapterId && it.paragraphIndex == selectedParaIndexForBookmark }
        AlertDialog(
            onDismissRequest = { showBookmarkDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (existingBookmark != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(if (existingBookmark != null) "Paragraph Actions" else "Paragraph Actions", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = selectedParaTextForBookmark,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                    
                    // Quick Action Buttons
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Actions",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        // Action 1: Set Reading Position
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch {
                                        lazyListState.scrollToItem(selectedParaIndexForBookmark!! + READER_HEADER_ITEMS)
                                        viewModel.progress.saveReadingProgress(
                                            bookId = bookId,
                                            chapterId = currentChapterId,
                                            paragraphIndex = selectedParaIndexForBookmark!!
                                        )
                                        showBookmarkDialog = false
                                    }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Start Reading from Here",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Scroll and resume reading visually from here",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // Action 2: Start Speech/TTS from Here
                        val activeCh = activeChapter
                        val bk = bookState
                        if (activeCh != null && bk != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewModel.tts.speak(
                                            text = activeCh.content,
                                            book = bk,
                                            chapter = activeCh,
                                            startFromParagraphIndex = selectedParaIndexForBookmark!!
                                        )
                                        showBookmarkDialog = false
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Read Aloud from Here (TTS)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        text = "Start voice reader narrative from this paragraph",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Content Editing / Cleaning Section
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Ad & Content Cleaning",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )

                        // Action 3: Truncate this chapter from here
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch {
                                        val activeCh = activeChapter
                                        if (activeCh != null && selectedParaTextForBookmark != null) {
                                            val lines = activeCh.content.split("\n")
                                            val indexInLines = lines.indexOfFirst { it.trim() == selectedParaTextForBookmark.trim() }
                                            if (indexInLines != -1) {
                                                val newContent = lines.subList(0, indexInLines).joinToString("\n").trim()
                                                viewModel.repository.updateChapterContent(activeCh.id, newContent)
                                            }
                                        }
                                        showBookmarkDialog = false
                                    }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Truncate Chapter From Here",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Delete this line and everything after it in this chapter",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // Action 4: Truncate matching lines and after in ALL chapters
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch {
                                        val query = selectedParaTextForBookmark?.trim().orEmpty()
                                        if (query.isNotEmpty()) {
                                            val allChaps = viewModel.repository.getChapters(bookId)
                                            var countUpdated = 0
                                            for (chap in allChaps) {
                                                val lines = chap.content.split("\n")
                                                val matchIdx = lines.indexOfFirst { 
                                                    it.contains(query, ignoreCase = true) 
                                                }
                                                if (matchIdx != -1) {
                                                    val newContent = lines.subList(0, matchIdx).joinToString("\n").trim()
                                                    viewModel.repository.updateChapterContent(chap.id, newContent)
                                                    countUpdated++
                                                }
                                            }
                                            if (countUpdated > 0) {
                                                android.widget.Toast.makeText(
                                                    context, 
                                                    "Successfully cleaned and truncated $countUpdated chapters!", 
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                        showBookmarkDialog = false
                                    }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Truncate All Chapters Matching This",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Scan all chapters, deleting from any line containing this text to the end (e.g. for recurring end ads)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Bookmark / Highlight Note",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = bookmarkNoteText,
                            onValueChange = { bookmarkNoteText = it },
                            label = { Text("Personal Note / Thought") },
                            placeholder = { Text("e.g. Important clue, beautiful quote...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val bId = "${bookId}_${currentChapterId}_${selectedParaIndexForBookmark!!}"
                            val newBookmark = com.example.data.local.BookmarkEntity(
                                id = bId,
                                bookId = bookId,
                                chapterId = currentChapterId,
                                paragraphIndex = selectedParaIndexForBookmark!!,
                                text = selectedParaTextForBookmark,
                                note = bookmarkNoteText
                            )
                            viewModel.repository.insertBookmark(newBookmark)
                            showBookmarkDialog = false
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (existingBookmark != null) "Update Bookmark" else "Save Bookmark")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (existingBookmark != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    viewModel.repository.deleteBookmark(existingBookmark.id)
                                    showBookmarkDialog = false
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete")
                        }
                    }
                    TextButton(onClick = { showBookmarkDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showAddSingleChapterDialog) {
        val sortedActiveChs = remember(chapters) { chapters.sortedBy { it.chapterNumber } }
        var isDropdownExpanded by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showAddSingleChapterDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PostAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Add Single Chapter", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = addChTitle,
                        onValueChange = { addChTitle = it },
                        label = { Text("Chapter Title") },
                        placeholder = { Text("e.g. Chapter 4.5: Extra Story") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Position selection
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = when (addChInsertPosition) {
                                -1 -> "At the very end"
                                0 -> "At the very beginning"
                                else -> {
                                    val targetCh = sortedActiveChs.getOrNull(addChInsertPosition - 1)
                                    if (targetCh != null) "After: ${targetCh.title}" else "At the very end"
                                }
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Insert Position") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = { isDropdownExpanded = true }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Position")
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = isDropdownExpanded,
                            onDismissRequest = { isDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 250.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("At the very beginning (Chapter 1)") },
                                onClick = {
                                    addChInsertPosition = 0
                                    addChNumberStr = "1"
                                    isDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("At the very end") },
                                onClick = {
                                    addChInsertPosition = -1
                                    val nextNum = (sortedActiveChs.lastOrNull()?.chapterNumber ?: 0) + 1
                                    addChNumberStr = nextNum.toString()
                                    isDropdownExpanded = false
                                }
                            )
                            sortedActiveChs.forEachIndexed { idx, ch ->
                                DropdownMenuItem(
                                    text = { Text("After: ${ch.title} (No. ${ch.chapterNumber})") },
                                    onClick = {
                                        addChInsertPosition = idx + 1 // 1-based offset
                                        addChNumberStr = (ch.chapterNumber + 1).toString()
                                        isDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = addChNumberStr,
                        onValueChange = { addChNumberStr = it },
                        label = { Text("Assign Chapter Number") },
                        placeholder = { Text("e.g. 5") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )

                    OutlinedTextField(
                        value = addChContent,
                        onValueChange = { addChContent = it },
                        label = { Text("Chapter Content") },
                        placeholder = { Text("Paste chapter text here...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 5,
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = addChNumberStr.toIntOrNull() ?: ((sortedActiveChs.lastOrNull()?.chapterNumber ?: 0) + 1)
                        viewModel.library.addSingleChapter(
                            bookId = bookId,
                            title = addChTitle.ifBlank { "Chapter $num" },
                            chapterNumber = num,
                            content = addChContent,
                            onComplete = {
                                showAddSingleChapterDialog = false
                                addChTitle = ""
                                addChContent = ""
                                addChNumberStr = ""
                                addChInsertPosition = -1
                                android.widget.Toast.makeText(context, "Chapter added successfully!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                ) {
                    Text("Add Chapter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSingleChapterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResequenceDialog) {
        val startNum = resequenceStartNumberStr.toIntOrNull() ?: 1
        val previewTitle1 = if (resequencePrefix.trim().isEmpty()) "$startNum" else "${resequencePrefix.trim()} $startNum"
        val previewTitle2 = if (resequencePrefix.trim().isEmpty()) "${startNum + 1}" else "${resequencePrefix.trim()} ${startNum + 1}"
        
        AlertDialog(
            onDismissRequest = { showResequenceDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Auto-Resequence Chapters", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "This will automatically re-number and rename all chapters sequentially in their current reading order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = resequencePrefix,
                        onValueChange = { resequencePrefix = it },
                        label = { Text("Word / Prefix (Optional)") },
                        placeholder = { Text("e.g. Chapter, Novel, Volume, Section") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = resequenceStartNumberStr,
                        onValueChange = { resequenceStartNumberStr = it },
                        label = { Text("Starting Number") },
                        placeholder = { Text("e.g. 1, 1000") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Sequence Preview:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("1. $previewTitle1", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text("2. $previewTitle2", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text("3. ...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val num = resequenceStartNumberStr.toIntOrNull() ?: 1
                        viewModel.library.resequenceChapters(
                            bookId = bookId,
                            prefix = resequencePrefix,
                            startNumber = num,
                            onComplete = { count ->
                                showResequenceDialog = false
                                android.widget.Toast.makeText(context, "Successfully re-sequenced $count chapters!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                ) {
                    Text("Resequence All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResequenceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ChapterRecapDialog(
    chapter: ChapterEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isGenerating by remember { mutableStateOf(false) }
    var summaryText by remember { mutableStateOf("") }

    LaunchedEffect(chapter.id) {
        isGenerating = true
        val cached = viewModel.repository.getChapterRecap(chapter.id)
        if (cached != null) {
            summaryText = cached.summary
        }
        isGenerating = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Summarize, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chapter Recap")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "A concise AI summary of the events in: ${chapter.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isGenerating) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("AI is writing recap...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (summaryText.isNotEmpty()) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                isGenerating = true
                                coroutineScope.launch {
                                    val result = viewModel.getChapterRecap(chapter)
                                    if (result != null) {
                                        summaryText = result
                                    }
                                    isGenerating = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate Recap with AI")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
        dismissButton = {
            if (summaryText.isNotEmpty() && !isGenerating) {
                TextButton(onClick = {
                    isGenerating = true
                    coroutineScope.launch {
                        val result = viewModel.getChapterRecap(chapter, force = true)
                        if (result != null) {
                            summaryText = result
                        }
                        isGenerating = false
                    }
                }) {
                    Text("Regenerate")
                }
            }
        }
    )
}

@Composable
fun AskAiDialog(
    chapter: ChapterEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ask AI Helper")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Ask any question about characters, translation nuances, or plot context in: ${chapter.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("e.g. Who is Master Lin Feng?") },
                    label = { Text("Your Question") },
                    modifier = Modifier.fillMaxWidth().testTag("ask_ai_input_field"),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                if (isThinking) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("AI is answering...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (answer.isNotEmpty()) {
                    Text(
                        text = "Answer:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (question.isNotBlank()) {
                        isThinking = true
                        coroutineScope.launch {
                            val resp = viewModel.askAboutSelection(
                                selection = chapter.content.take(1000),
                                question = question
                            )
                            answer = resp
                            isThinking = false
                        }
                    }
                },
                enabled = question.isNotBlank() && !isThinking,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Ask")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun FindReplaceDialog(
    bookId: String,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var scopeAllBooks by remember { mutableStateOf(false) }
    var isReplacing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bulk Find & Replace")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Perform bulk string substitutions across downloaded chapters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = findText,
                    onValueChange = { findText = it },
                    label = { Text("Find Text") },
                    placeholder = { Text("e.g. Master Lin") },
                    modifier = Modifier.fillMaxWidth().testTag("bulk_replace_find_input"),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    label = { Text("Replacement Text") },
                    placeholder = { Text("e.g. Lin Feng") },
                    modifier = Modifier.fillMaxWidth().testTag("bulk_replace_replace_input"),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Apply across ALL books",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = scopeAllBooks,
                        onCheckedChange = { scopeAllBooks = it }
                    )
                }

                if (isReplacing) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (resultMessage.isNotEmpty()) {
                    Text(
                        text = resultMessage,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (findText.isNotEmpty()) {
                        isReplacing = true
                        resultMessage = ""
                        coroutineScope.launch {
                            val results = viewModel.bulkFindAndReplace(
                                bookId = bookId,
                                findText = findText,
                                replaceText = replaceText,
                                scopeAllBooks = scopeAllBooks
                            )
                            resultMessage = "Replaced in ${results.first} chapters (${results.second} matches)"
                            isReplacing = false
                        }
                    }
                },
                enabled = findText.isNotEmpty() && !isReplacing,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Execute")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun softHyphenateText(text: String, enabled: Boolean): String {
    if (!enabled) return text
    return text.split(" ").joinToString(" ") { word ->
        if (word.length <= 6) {
            word
        } else {
            val syllables = mutableListOf<String>()
            var currentSyllable = StringBuilder()
            val vowels = "aeiouyAEIOUY"
            
            var i = 0
            while (i < word.length) {
                val char = word[i]
                currentSyllable.append(char)
                
                if (vowels.contains(char)) {
                    if (i + 2 < word.length && !vowels.contains(word[i + 1]) && vowels.contains(word[i + 2])) {
                        syllables.add(currentSyllable.toString())
                        currentSyllable = StringBuilder()
                    } else if (i + 3 < word.length && !vowels.contains(word[i + 1]) && !vowels.contains(word[i + 2]) && vowels.contains(word[i + 3])) {
                        currentSyllable.append(word[i + 1])
                        syllables.add(currentSyllable.toString())
                        currentSyllable = StringBuilder()
                        i++
                    }
                }
                i++
            }
            if (currentSyllable.isNotEmpty()) {
                syllables.add(currentSyllable.toString())
            }
            if (syllables.size > 1) {
                syllables.joinToString("\u00AD")
            } else {
                word
            }
        }
    }
}

private fun highlightGlossaryTerms(
    text: String,
    glossaries: List<GlossaryEntity>,
    highlightColor: Color
): AnnotatedString {
    if (glossaries.isEmpty()) return AnnotatedString(text)
    
    // Sort glossaries descending by length to match longer terms first and avoid partial sub-word matching issues
    val sortedGlossaries = glossaries.sortedByDescending { it.originalText.length }
    val annotatedBuilder = AnnotatedString.Builder()
    
    var currentIndex = 0
    while (currentIndex < text.length) {
        var matchFound = false
        for (glossary in sortedGlossaries) {
            val original = glossary.originalText
            if (original.isNotEmpty() && text.startsWith(original, currentIndex, ignoreCase = true)) {
                val matchedText = text.substring(currentIndex, currentIndex + original.length)
                annotatedBuilder.pushStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold,
                        background = highlightColor.copy(alpha = 0.15f)
                    )
                )
                annotatedBuilder.append(matchedText)
                annotatedBuilder.pop()
                currentIndex += original.length
                matchFound = true
                break
            }
        }
        if (!matchFound) {
            annotatedBuilder.append(text[currentIndex])
            currentIndex++
        }
    }
    return annotatedBuilder.toAnnotatedString()
}
