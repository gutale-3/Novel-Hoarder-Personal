package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.example.util.WatermarkCleaner
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
    // Flows are remembered: a new Flow on each recomposition restarts every query (all chapter
    // text) while scrolling.
    val bookState by remember(bookId) { viewModel.repository.getBookFlow(bookId) }.collectAsState(null)
    val chapters by remember(bookId) { viewModel.repository.getChaptersFlow(bookId) }.collectAsState(emptyList())
    val archivedChapters by remember(bookId) { viewModel.progress.getArchivedChaptersFlow(bookId) }.collectAsState(emptyList())
    val bookmarks by remember(bookId) { viewModel.repository.getBookmarksFlow(bookId) }.collectAsState(emptyList())
    val glossaries by remember(bookId) { viewModel.repository.getGlossaryFlow(bookId) }.collectAsState(emptyList())
    val textReplacementRules by remember(bookId) { viewModel.textRules.getRulesForBookFlow(bookId) }.collectAsState(emptyList())

    DisposableEffect(Unit) {
        com.example.ui.components.ReaderPaging.readerShown()
        onDispose { com.example.ui.components.ReaderPaging.readerHidden() }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentChapterId by remember { mutableStateOf(initialChapterId ?: "") }
    val lazyListState = rememberLazyListState()

    BackHandler {
        viewModel.progress.clearActiveReaderSession()
        onBack()
    }

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
    var showTagDialogForChapter by remember { mutableStateOf<ChapterEntity?>(null) }
    var showRevisionHistoryDialog by remember { mutableStateOf(false) }

    var sessionStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastLoggedParagraphIndex by remember { mutableIntStateOf(0) }

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

    val selectedFontFamily = com.example.ui.theme.ReaderFonts.resolve(viewModel.readerFontFamily, viewModel.readerCustomFontPath)

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
    val activeChapter = remember(chapters, archivedChapters, currentChapterId) {
        if (currentChapterId.isEmpty()) {
            chapters.firstOrNull()
        } else {
            // A hidden chapter opened from the drawer or a bookmark still opens.
            chapters.find { it.id == currentChapterId } ?: archivedChapters.find { it.id == currentChapterId }
        }
    }

    // In page-turn mode there is no scrolling list; PagedReader saves the paragraph on each page
    // turn, so that is the position to keep (the list's index is stale there).
    fun currentReadingParagraph(): Int {
        val ch = activeChapter
        return if (viewModel.settings.readerPageTurnMode && ch != null) {
            viewModel.progress.getSavedParagraphIndex(bookId, ch.id).coerceAtLeast(0)
        } else {
            (lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS).coerceAtLeast(0)
        }
    }

    // Whenever active chapter changes, save reading progress in database and prefetch next chapters
    LaunchedEffect(activeChapter) {
        if (activeChapter != null && bookState != null) {
            viewModel.repository.updateBook(
                bookState!!.copy(
                    lastReadChapterId = activeChapter.id,
                    lastReadChapterNumber = maxOf(bookState!!.lastReadChapterNumber, activeChapter.chapterNumber)
                )
            )
            viewModel.aiFeatures.triggerAutoDownloadNextChapters(bookState!!, activeChapter)
            viewModel.tts.primeChapterOpening(activeChapter.content)
            viewModel.progress.setActiveReaderSession(bookId, activeChapter.id)
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
    var initialTargetUsed by remember { mutableStateOf(false) }
    LaunchedEffect(currentChapterId) {
        if (currentChapterId.isNotEmpty()) {
            // The launch target applies once; coming back to that chapter later uses saved progress.
            if (!initialTargetUsed && currentChapterId == initialChapterId && initialParaIndex >= 0) {
                initialTargetUsed = true
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
                val currentParagraphIndex = currentReadingParagraph()
                if (activeChapter != null) {
                    val lines = activeChapter.content.split("\n\n", "\n").filter { it.isNotBlank() }
                    val currentParaText = lines.getOrNull(currentParagraphIndex) ?: ""
                    viewModel.progress.autoSaveProgressAndBookmark(
                        bookId = bookId,
                        chapterId = activeChapter.id,
                        paragraphIndex = currentParagraphIndex,
                        paragraphText = currentParaText
                    )
                    viewModel.progress.setActiveReaderSession(bookId, activeChapter.id, currentParagraphIndex)
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
            val currentParagraphIndex = currentReadingParagraph()
            if (activeChapter != null) {
                val lines = activeChapter.content.split("\n\n", "\n").filter { it.isNotBlank() }
                val currentParaText = lines.getOrNull(currentParagraphIndex) ?: ""
                viewModel.progress.autoSaveProgressAndBookmark(
                    bookId = bookId,
                    chapterId = activeChapter.id,
                    paragraphIndex = currentParagraphIndex,
                    paragraphText = currentParaText
                )
                val durationSec = ((System.currentTimeMillis() - sessionStartTime) / 1000).coerceIn(5L, 1800L)
                if (viewModel.settings.enableReadingStats && durationSec >= 5) {
                    val newlyReadWords = if (currentParagraphIndex > lastLoggedParagraphIndex) {
                        val end = minOf(currentParagraphIndex + 1, lines.size)
                        lines.subList(lastLoggedParagraphIndex, end).sumOf { it.split("\\s+".toRegex()).size }
                    } else {
                        lines.getOrNull(currentParagraphIndex)?.split("\\s+".toRegex())?.size ?: 30
                    }.coerceIn(10, 8000)
                    lastLoggedParagraphIndex = currentParagraphIndex
                    viewModel.stats.recordReadingSession(bookId, activeChapter.id, durationSec, newlyReadWords)
                }
            }
        }
    }

    val activeParagraphs = remember(
        activeChapter?.content,
        viewModel.settings.watermarkRemovalEnabled,
        viewModel.settings.customWatermarkPhrases
    ) {
        val raw = activeChapter?.content?.split("\n\n", "\n")?.filter { it.isNotBlank() } ?: emptyList()
        WatermarkCleaner.cleanParagraphs(
            paragraphs = raw,
            enabled = viewModel.settings.watermarkRemovalEnabled,
            customPhrases = viewModel.settings.getCustomWatermarkPhrasesList()
        )
    }
    val currentParagraphIndex by remember {
        derivedStateOf {
            val visibleIdx = lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS
            if (visibleIdx >= 0) visibleIdx else 0
        }
    }
    val userWpm = remember(viewModel.stats.totalReadingSeconds, viewModel.stats.totalWordsRead) {
        if (viewModel.stats.totalReadingSeconds >= 120 && viewModel.stats.totalWordsRead > 200) {
            ((viewModel.stats.totalWordsRead * 60) / viewModel.stats.totalReadingSeconds).toInt().coerceIn(120, 600)
        } else {
            240
        }
    }
    val remainingWords = remember(activeParagraphs, currentParagraphIndex) {
        if (currentParagraphIndex >= activeParagraphs.size) 0
        else activeParagraphs.drop(currentParagraphIndex).sumOf { it.trim().split("\\s+".toRegex()).size }
    }
    val estimatedMinutesLeft = remember(remainingWords, userWpm) {
        if (remainingWords <= 0) 0
        else kotlin.math.ceil(remainingWords.toDouble() / userWpm.toDouble()).toInt().coerceAtLeast(1)
    }

    val readerDrawer: @Composable () -> Unit = {
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
                },
                onOpenRevisionHistory = { showRevisionHistoryDialog = true }
            )
        }
    val readerBody: @Composable () -> Unit = {
        Scaffold(
            containerColor = readerBgColor,
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
                    onOpenSourceMigration = { showSourceMigrationDialog = true },
                    onOpenTags = { showTagDialogForChapter = activeChapter },
                    onOpenRevisionHistory = { showRevisionHistoryDialog = true }
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
                    onSelectChapter = { chId -> currentChapterId = chId },
                    estimatedMinutesLeft = estimatedMinutesLeft
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val pageTurnChapter = activeChapter?.takeIf {
                    viewModel.settings.readerPageTurnMode && it.content.isNotBlank()
                }
                if (pageTurnChapter != null) {
                    val pageParagraphs = remember(pageTurnChapter.content, viewModel.settings.watermarkRemovalEnabled) {
                        WatermarkCleaner.cleanParagraphs(
                            paragraphs = pageTurnChapter.content.split("\n\n", "\n").filter { it.isNotBlank() },
                            enabled = viewModel.settings.watermarkRemovalEnabled,
                            customPhrases = viewModel.settings.getCustomWatermarkPhrasesList()
                        )
                    }
                    Box(Modifier.fillMaxSize().background(readerBgColor)) {
                        PagedReader(
                            activeChapter = pageTurnChapter,
                            chapters = chapters,
                            paragraphs = pageParagraphs,
                            viewModel = viewModel,
                            bookId = bookId,
                            fontFamily = selectedFontFamily,
                            textColor = readerTextColor,
                            onToggleControls = { showControls = !showControls },
                            onSelectChapter = { chId -> currentChapterId = chId }
                        )
                    }
                } else {
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
                        },
                        onOpenTags = { showTagDialogForChapter = activeChapter }
                    )
                }

                PreviouslyOnCard(
                    bookId = bookId,
                    activeChapter = activeChapter,
                    chapters = chapters,
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Extra-Dim Sub-Zero Night Scrim
                if (viewModel.settings.readerExtraDimEnabled && viewModel.settings.readerExtraDimPercent > 0) {
                    val dimAlpha = (viewModel.settings.readerExtraDimPercent / 100f).coerceIn(0.05f, 0.85f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = dimAlpha))
                    )
                }
            }
        }
    }

    // Tablets / unfolded foldables: keep the chapter list open beside the text.
    if (com.example.ui.theme.rememberScreenSize() == com.example.ui.theme.ScreenSize.Expanded) {
        PermanentNavigationDrawer(drawerContent = readerDrawer) { readerBody() }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = readerDrawer
        ) { readerBody() }
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
                        val toDelete = chaptersToDelete.toSet() // the list is cleared before the callback
                        val currentActiveChapterIsDeleted = currentChapterId in toDelete
                        val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
                        // Land on the nearest surviving chapter: the next one, else the previous one.
                        val neighbour = (chapters.drop(currentIdx + 1) + chapters.take(maxOf(currentIdx, 0)).reversed())
                            .firstOrNull { it.id !in toDelete }
                        viewModel.progress.deleteChapters(bookId, toDelete.toList()) { msg ->
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            if (currentActiveChapterIsDeleted) {
                                if (neighbour != null) currentChapterId = neighbour.id else onBack()
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

    if (showTagDialogForChapter != null) {
        ChapterTagDialog(
            chapter = showTagDialogForChapter,
            tagManager = viewModel.chapterTags,
            onDismiss = { showTagDialogForChapter = null }
        )
    }

    if (showRevisionHistoryDialog) {
        ChapterRevisionHistoryDialog(
            bookId = bookId,
            activeChapter = activeChapter,
            viewModel = viewModel,
            context = context,
            onDismiss = { showRevisionHistoryDialog = false }
        )
    }
}

/**
 * "Previously on…": opening a novel after two days or more away shows a short AI recap of the
 * chapter before the one being resumed. Needs an AI (Gemini key or on-device model); silent
 * otherwise. Recaps are cached, so this costs one request per chapter at most.
 */
@Composable
private fun PreviouslyOnCard(
    bookId: String,
    activeChapter: com.example.data.local.ChapterEntity?,
    chapters: List<com.example.data.local.ChapterEntity>,
    viewModel: com.example.viewmodel.MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // When this novel was last read, captured before this visit updates it.
    val lastReadAt = remember(bookId) {
        context.getSharedPreferences("novel_hoarder_prefs", android.content.Context.MODE_PRIVATE)
            .getLong("progress_at_$bookId", 0L)
    }
    val away = lastReadAt > 0 && System.currentTimeMillis() - lastReadAt > 2 * 24 * 60 * 60 * 1000L
    var dismissed by remember(bookId) { mutableStateOf(false) }
    var recap by remember(bookId) { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId, activeChapter?.id, away) {
        if (!away || recap != null || activeChapter == null) return@LaunchedEffect
        if (!viewModel.aiRegistry.best().isAvailable()) return@LaunchedEffect
        val idx = chapters.indexOfFirst { it.id == activeChapter.id }
        val previous = chapters.take(maxOf(idx, 0)).lastOrNull { it.content.isNotBlank() } ?: return@LaunchedEffect
        val text = viewModel.getChapterRecap(previous)
        if (!text.isNullOrBlank() && !text.startsWith("Error") && !text.startsWith("No AI") &&
            !text.startsWith("No on-device") && !text.startsWith("That model")
        ) recap = text
    }

    val shown = recap
    if (!away || dismissed || shown == null) return
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Previously on…", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { dismissed = true }) {
                    Icon(Icons.Default.Close, contentDescription = "Close recap")
                }
            }
            Text(shown, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
