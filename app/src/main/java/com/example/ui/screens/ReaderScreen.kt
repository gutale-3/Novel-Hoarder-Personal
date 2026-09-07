package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.ui.components.*
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

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
    val archivedChapters by viewModel.progress.getArchivedChaptersFlow(bookId).collectAsState(emptyList())
    val bookmarks by viewModel.repository.getBookmarksFlow(bookId).collectAsState(emptyList())
    val glossaries by viewModel.repository.getGlossaryFlow(bookId).collectAsState(emptyList())
    val textReplacementRules by viewModel.textRules.getRulesForBookFlow(bookId).collectAsState(emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentChapterId by remember { mutableStateOf(initialChapterId ?: "") }
    val lazyListState = rememberLazyListState()

    var showControls by remember { mutableStateOf(false) }

    // Dialog Visibility States
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showFindReplaceDialog by remember { mutableStateOf(false) }
    var showAutoArchiveSettingDialog by remember { mutableStateOf(false) }
    var showAddSingleChapterDialog by remember { mutableStateOf(false) }
    var showResequenceDialog by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var selectedParaIndexForBookmark by remember { mutableStateOf<Int?>(null) }
    var selectedParaTextForBookmark by remember { mutableStateOf("") }
    var showDeleteChaptersConfirmDialog by remember { mutableStateOf(false) }
    var chaptersToDelete by remember { mutableStateOf(emptyList<String>()) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var showRsvpModal by remember { mutableStateOf(false) }
    var showTextRulesDialog by remember { mutableStateOf(false) }
    var showSourceMigrationDialog by remember { mutableStateOf(false) }
    var showTapZonesDialog by remember { mutableStateOf(false) }

    var sessionStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

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
            val file = File(viewModel.readerCustomFontPath)
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

    // Restore scroll position or scroll to initial target
    LaunchedEffect(currentChapterId) {
        if (currentChapterId.isNotEmpty()) {
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

    // Auto-save reading progress, bookmark, and reading stats on pause / stop / dispose
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activeChapter?.id, currentChapterId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val currentParagraphIndex = (lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS).coerceAtLeast(0)
                if (activeChapter != null) {
                    val lines = activeChapter.content.split("\n\n", "\n").filter { it.isNotBlank() }
                    val currentParaText = lines.getOrNull(currentParagraphIndex) ?: ""
                    viewModel.progress.autoSaveProgressAndBookmark(
                        bookId = bookId,
                        chapterId = activeChapter.id,
                        paragraphIndex = currentParagraphIndex,
                        paragraphText = currentParaText
                    )
                    val durationSec = ((System.currentTimeMillis() - sessionStartTime) / 1000)
                    if (viewModel.settings.enableReadingStats && durationSec >= 5) {
                        val wordsInSession = lines.take(currentParagraphIndex + 1).sumOf { it.split("\\s+".toRegex()).size }.coerceAtLeast(10)
                        viewModel.stats.recordReadingSession(bookId, activeChapter.id, durationSec, wordsInSession)
                        sessionStartTime = System.currentTimeMillis()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val currentParagraphIndex = (lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS).coerceAtLeast(0)
            if (activeChapter != null) {
                val lines = activeChapter.content.split("\n\n", "\n").filter { it.isNotBlank() }
                val currentParaText = lines.getOrNull(currentParagraphIndex) ?: ""
                viewModel.progress.autoSaveProgressAndBookmark(
                    bookId = bookId,
                    chapterId = activeChapter.id,
                    paragraphIndex = currentParagraphIndex,
                    paragraphText = currentParaText
                )
                val durationSec = ((System.currentTimeMillis() - sessionStartTime) / 1000)
                if (viewModel.settings.enableReadingStats && durationSec >= 5) {
                    val wordsInSession = lines.take(currentParagraphIndex + 1).sumOf { it.split("\\s+".toRegex()).size }.coerceAtLeast(10)
                    viewModel.stats.recordReadingSession(bookId, activeChapter.id, durationSec, wordsInSession)
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ReaderDrawerContent(
                bookId = bookId,
                currentChapterId = currentChapterId,
                chapters = chapters,
                archivedChapters = archivedChapters,
                bookmarks = bookmarks,
                viewModel = viewModel,
                context = context,
                onSelectChapter = { chId ->
                    scope.launch {
                        currentChapterId = chId
                        drawerState.close()
                    }
                },
                onOpenAddChapterDialog = { showAddSingleChapterDialog = true },
                onOpenResequenceDialog = { showResequenceDialog = true },
                onOpenAutoArchiveDialog = { showAutoArchiveSettingDialog = true },
                onConfirmDeleteChapters = { toDel ->
                    chaptersToDelete = toDel
                    showDeleteChaptersConfirmDialog = true
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                ReaderTopBar(
                    visible = showControls,
                    bookState = bookState,
                    activeChapter = activeChapter,
                    viewModel = viewModel,
                    context = context,
                    barBgColor = barBgColor,
                    barContentColor = barContentColor,
                    onBack = onBack,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { showSettingsDialog = true },
                    onOpenRsvp = { showRsvpModal = true },
                    onOpenStats = { showStatsDialog = true },
                    onOpenTextRules = { showTextRulesDialog = true },
                    onOpenTapZones = { showTapZonesDialog = true },
                    onOpenSourceMigration = { showSourceMigrationDialog = true }
                )
            },
            bottomBar = {
                ReaderBottomBar(
                    visible = showControls,
                    currentChapterId = currentChapterId,
                    chapters = chapters,
                    viewModel = viewModel,
                    barBgColor = barBgColor,
                    barContentColor = barContentColor,
                    onSelectChapter = { chId -> currentChapterId = chId }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                ReaderContent(
                    bookState = bookState,
                    activeChapter = activeChapter,
                    chapters = chapters,
                    bookmarks = bookmarks,
                    glossaries = glossaries,
                    textReplacementRules = textReplacementRules,
                    viewModel = viewModel,
                    context = context,
                    lazyListState = lazyListState,
                    selectedFontFamily = selectedFontFamily,
                    readerBgColor = readerBgColor,
                    readerTextColor = readerTextColor,
                    showControls = showControls,
                    onToggleControls = { showControls = !showControls },
                    onOpenFindReplace = { showFindReplaceDialog = true },
                    onOpenRsvp = { showRsvpModal = true },
                    onSelectChapter = { chId -> currentChapterId = chId },
                    onParagraphLongClick = { idx, text ->
                        selectedParaIndexForBookmark = idx
                        selectedParaTextForBookmark = text
                        showBookmarkDialog = true
                    }
                )
            }
        }
    }

    // --- Dialogs ---
    if (showSettingsDialog) {
        ReaderSettingsDialog(
            viewModel = viewModel,
            context = context,
            onDismiss = { showSettingsDialog = false },
            onOpenTextRules = { showTextRulesDialog = true },
            onOpenTapZones = { showTapZonesDialog = true }
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
        val existingBookmark = bookmarks.find { 
            it.chapterId == currentChapterId && it.paragraphIndex == selectedParaIndexForBookmark 
        }
        ParagraphBookmarkActionDialog(
            bookId = bookId,
            currentChapterId = currentChapterId,
            activeChapter = activeChapter,
            bookState = bookState,
            paragraphIndex = selectedParaIndexForBookmark!!,
            paragraphText = selectedParaTextForBookmark,
            existingBookmark = existingBookmark,
            viewModel = viewModel,
            context = context,
            onScrollToParagraph = { pIdx ->
                scope.launch {
                    lazyListState.scrollToItem(pIdx + READER_HEADER_ITEMS)
                }
            },
            onDismiss = { showBookmarkDialog = false }
        )
    }

    if (showAddSingleChapterDialog) {
        AddSingleChapterDialog(
            bookId = bookId,
            chapters = chapters,
            viewModel = viewModel,
            context = context,
            onDismiss = { showAddSingleChapterDialog = false }
        )
    }

    if (showResequenceDialog) {
        ResequenceChaptersDialog(
            bookId = bookId,
            viewModel = viewModel,
            context = context,
            onDismiss = { showResequenceDialog = false }
        )
    }

    if (showAutoArchiveSettingDialog && bookState != null) {
        AutoArchiveSettingsDialog(
            book = bookState!!,
            viewModel = viewModel,
            context = context,
            onDismiss = { showAutoArchiveSettingDialog = false }
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

    if (showStatsDialog && viewModel.settings.enableReadingStats) {
        ReadingStatsDialog(
            viewModel = viewModel,
            onDismiss = { showStatsDialog = false }
        )
    }

    if (showRsvpModal && viewModel.settings.enableRsvpSpeedReading) {
        val chapterWords = remember(activeChapter?.content) {
            activeChapter?.content ?: ""
        }
        val approxWordStart = remember(lazyListState.firstVisibleItemIndex) {
            val pIdx = (lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS).coerceAtLeast(0)
            (pIdx * 40).coerceAtLeast(0)
        }
        RsvpSpeedReaderModal(
            chapterTitle = activeChapter?.title ?: "Chapter",
            rawText = chapterWords,
            initialWordIndex = approxWordStart,
            viewModel = viewModel,
            onDismiss = { lastWordIdx ->
                val lines = (activeChapter?.content ?: "").split("\n\n", "\n").filter { it.isNotBlank() }
                val approxPara = if (lines.isNotEmpty()) {
                    (lastWordIdx / 40).coerceIn(0, lines.size - 1)
                } else 0
                scope.launch {
                    lazyListState.scrollToItem(approxPara + READER_HEADER_ITEMS)
                }
                showRsvpModal = false
            }
        )
    }

    if (showTextRulesDialog && viewModel.settings.enableAutoApplyTextRules) {
        TextReplacementDialog(
            bookId = bookId,
            viewModel = viewModel,
            onDismiss = { showTextRulesDialog = false }
        )
    }

    if (showTapZonesDialog && viewModel.settings.enableTapZonesCustomization) {
        TapZonesConfigDialog(
            viewModel = viewModel,
            onDismiss = { showTapZonesDialog = false }
        )
    }

    if (showSourceMigrationDialog && viewModel.settings.enableSourceMigration && bookState != null) {
        SourceMigrationDialog(
            book = bookState!!,
            viewModel = viewModel,
            onDismiss = { showSourceMigrationDialog = false }
        )
    }
}
