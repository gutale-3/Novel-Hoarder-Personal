package com.example.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.data.local.BookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GlossaryEntity
import com.example.data.local.TextReplacementRuleEntity
import com.example.util.BionicReadingHelper
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val READER_HEADER_ITEMS = 3 // Title (0), Find & Replace Row (1), Divider (2)

@Composable
fun ReaderContent(
    bookState: BookEntity?,
    activeChapter: ChapterEntity?,
    chapters: List<ChapterEntity>,
    bookmarks: List<BookmarkEntity>,
    glossaries: List<GlossaryEntity>,
    textReplacementRules: List<TextReplacementRuleEntity>,
    viewModel: MainViewModel,
    context: Context,
    lazyListState: LazyListState,
    selectedFontFamily: FontFamily,
    readerBgColor: Color,
    readerTextColor: Color,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onOpenFindReplace: () -> Unit,
    onOpenRsvp: () -> Unit,
    onSelectChapter: (String) -> Unit,
    onParagraphLongClick: (Int, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val currentParagraphIndex by remember {
        derivedStateOf {
            val visibleIdx = lazyListState.firstVisibleItemIndex - READER_HEADER_ITEMS
            if (visibleIdx >= 0) visibleIdx else 0
        }
    }

    val isContinuous = viewModel.settings.readerPagingMode == "continuous"

    val chapterParagraphs = remember(activeChapter?.content) {
        activeChapter?.content?.split("\n\n", "\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBgColor)
            .onSizeChanged { canvasSize = it }
            .pointerInput(viewModel.settings.enableTapZonesCustomization, currentParagraphIndex) {
                detectTapGestures(
                    onTap = { offset ->
                        if (viewModel.settings.enableTapZonesCustomization && canvasSize.width > 0 && canvasSize.height > 0) {
                            val xRatio = offset.x / canvasSize.width.toFloat()
                            val yRatio = offset.y / canvasSize.height.toFloat()

                            val row = when {
                                yRatio < 0.33f -> "t"
                                yRatio < 0.66f -> "m"
                                else -> "b"
                            }
                            val col = when {
                                xRatio < 0.33f -> "l"
                                xRatio < 0.66f -> "c"
                                else -> "r"
                            }
                            val zoneKey = "$row$col"
                            val action = when (zoneKey) {
                                "tl" -> viewModel.settings.tapZoneTopLeftAction
                                "tc" -> viewModel.settings.tapZoneTopCenterAction
                                "tr" -> viewModel.settings.tapZoneTopRightAction
                                "ml" -> viewModel.settings.tapZoneMidLeftAction
                                "mc" -> viewModel.settings.tapZoneMidCenterAction
                                "mr" -> viewModel.settings.tapZoneMidRightAction
                                "bl" -> viewModel.settings.tapZoneBottomLeftAction
                                "bc" -> viewModel.settings.tapZoneBottomCenterAction
                                "br" -> viewModel.settings.tapZoneBottomRightAction
                                else -> "TOGGLE_CONTROLS"
                            }

                            when (action) {
                                "PREV_PAGE" -> {
                                    scope.launch {
                                        val target = (lazyListState.firstVisibleItemIndex - 4).coerceAtLeast(0)
                                        lazyListState.animateScrollToItem(target)
                                    }
                                }
                                "NEXT_PAGE" -> {
                                    scope.launch {
                                        val target = lazyListState.firstVisibleItemIndex + 4
                                        lazyListState.animateScrollToItem(target)
                                    }
                                }
                                "TOGGLE_CONTROLS" -> onToggleControls()
                                "QUICK_BOOKMARK" -> {
                                    if (currentParagraphIndex >= 0 && activeChapter != null && bookState != null) {
                                        scope.launch {
                                            val paraText = chapterParagraphs.getOrNull(currentParagraphIndex) ?: ""
                                            viewModel.repository.insertBookmark(
                                                BookmarkEntity(
                                                    id = "${bookState.id}_${activeChapter.id}_$currentParagraphIndex",
                                                    bookId = bookState.id,
                                                    chapterId = activeChapter.id,
                                                    paragraphIndex = currentParagraphIndex,
                                                    text = paraText.take(120),
                                                    note = "Quick Bookmark",
                                                    timestamp = System.currentTimeMillis()
                                                )
                                            )
                                        }
                                        android.widget.Toast.makeText(context, "Quick Bookmark Added", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "TOGGLE_RSVP" -> onOpenRsvp()
                                "TOGGLE_AUTOSCROLL" -> viewModel.ttsAutoScrollEnabled = !viewModel.ttsAutoScrollEnabled
                                "TOGGLE_BIONIC" -> viewModel.settings.updateBionicReadingActiveInReader(!viewModel.settings.bionicReadingActiveInReader)
                                else -> {}
                            }
                        } else {
                            onToggleControls()
                        }
                    }
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
            // Auto-follow scroll: Keep the active TTS paragraph centered and in full view
            val activePara = viewModel.ttsActiveParagraphIndex ?: -1
            val isTtsPlaying = viewModel.ttsIsPlaying
            val playingChapterId = viewModel.ttsPlayingChapter?.id
            val autoScrollEnabled = viewModel.ttsAutoScrollEnabled

            val isUserDragging by lazyListState.interactionSource.collectIsDraggedAsState()
            var isAutoScrollSuspended by remember { mutableStateOf(false) }

            LaunchedEffect(activeChapter.id) {
                isAutoScrollSuspended = false
            }

            LaunchedEffect(isUserDragging, isTtsPlaying) {
                if (isUserDragging && isTtsPlaying) {
                    isAutoScrollSuspended = true
                }
            }

            LaunchedEffect(isAutoScrollSuspended, isUserDragging, activePara) {
                if (isAutoScrollSuspended && !isUserDragging) {
                    delay(5000L)
                    isAutoScrollSuspended = false
                }
            }

            LaunchedEffect(activePara, isTtsPlaying, playingChapterId, autoScrollEnabled, activeChapter.id, isAutoScrollSuspended) {
                if (autoScrollEnabled && !isAutoScrollSuspended && isTtsPlaying && playingChapterId == activeChapter.id) {
                    val targetItemIndex = if (activePara < 0) 0 else activePara + READER_HEADER_ITEMS
                    val viewportHeight = lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
                    val scrollOffset = if (viewportHeight > 0) -viewportHeight / 4 else -120
                    lazyListState.animateScrollToItem(targetItemIndex, scrollOffset)
                }
            }

            // Expose total paragraphs to viewmodel for TTS player bar seek slider
            LaunchedEffect(chapterParagraphs.size) {
                viewModel.ttsTotalParagraphs = chapterParagraphs.size
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
                val currentIdx = chapters.indexOfFirst { it.id == activeChapter.id }
                val hasPrev = currentIdx > 0
                val hasNext = currentIdx < chapters.size - 1
                val nextChapter = if (hasNext) chapters[currentIdx + 1] else null
                val prevChapter = if (hasPrev) chapters[currentIdx - 1] else null

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 800.dp)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = viewModel.readerMargin.dp),
                    verticalArrangement = Arrangement.spacedBy(viewModel.settings.readerParagraphSpacing.dp),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp)
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
                                onClick = onOpenFindReplace,
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
                        HorizontalDivider()
                    }

                    // Paragraphs
                    items(chapterParagraphs.size) { idx ->
                        val rawPara = chapterParagraphs[idx]
                        val para = if (viewModel.settings.enableAutoApplyTextRules && textReplacementRules.isNotEmpty()) {
                            viewModel.repository.applyReplacementRules(rawPara, textReplacementRules)
                        } else {
                            rawPara
                        }

                        val isReadingThisPara = (viewModel.ttsIsPlaying || viewModel.ttsIsPaused) &&
                                viewModel.ttsPlayingBook?.id == bookState?.id &&
                                viewModel.ttsPlayingChapter?.id == activeChapter.id &&
                                idx == viewModel.ttsActiveParagraphIndex

                        val hasExistingBookmark = bookmarks.find { it.chapterId == activeChapter.id && it.paragraphIndex == idx }

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
                                        onToggleControls()
                                    },
                                    onLongPress = {
                                        onParagraphLongClick(idx, para)
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
                        val indentPrefix = if (viewModel.settings.readerFirstLineIndentEnabled) "        " else "      "

                        val annotatedText = if (viewModel.settings.enableBionicReading && viewModel.settings.bionicReadingActiveInReader) {
                            BionicReadingHelper.formatBionicText(
                                text = indentPrefix + hyphenatedText,
                                baseColor = paragraphTextColor.copy(alpha = paragraphAlpha),
                                boldColor = if (viewModel.readerTheme == "eink") Color.Black else MaterialTheme.colorScheme.primary.copy(alpha = paragraphAlpha)
                            )
                        } else {
                            highlightGlossaryTerms(
                                text = indentPrefix + hyphenatedText,
                                glossaries = glossaries,
                                highlightColor = if (viewModel.readerTheme == "eink") Color.Black else MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = annotatedText,
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

                    // Chapter End Actions / Next Chapter transition card
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "End of Chapter",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (nextChapter != null) {
                                    Button(
                                        onClick = {
                                            onSelectChapter(nextChapter.id)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Next: ${nextChapter.title}",
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "You've reached the latest chapter!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (prevChapter != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onSelectChapter(prevChapter.id)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Previous: ${prevChapter.title}",
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Reading Focus Guide Overlay
                if (viewModel.settings.enableReadingGuide) {
                    val guideColor = when (viewModel.settings.readingGuideColor) {
                        "amber" -> Color(0xFFFFB300)
                        "cyan" -> Color(0xFF00E5FF)
                        "green" -> Color(0xFF00E676)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(viewModel.settings.readingGuideHeight.dp)
                            .align(Alignment.Center)
                            .background(guideColor.copy(alpha = viewModel.settings.readingGuideOpacity))
                            .border(1.dp, guideColor.copy(alpha = 0.4f))
                    )
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
