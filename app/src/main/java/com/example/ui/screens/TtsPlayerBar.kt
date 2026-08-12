package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.PiperVoice
import com.example.data.ai.PiperVoiceCatalog
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.VoiceOption
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsPlayerBar(
    viewModel: MainViewModel,
    onNavigateToReader: (String, String, Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val playingBook = viewModel.ttsPlayingBook
    val playingChapter = viewModel.ttsPlayingChapter

    if (playingBook == null || playingChapter == null) {
        if (viewModel.hasResumableSession) {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("tts_resume_bar"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.progress.resumeLastSession() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume Playback",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Continue listening",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "${viewModel.resumeBookName} • ${viewModel.resumeChapterTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { viewModel.progress.clearTtsProgress(); viewModel.progress.loadResumableTtsSession() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss Resume Bar",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        return
    }

    var showSettingsDialog by remember { mutableStateOf(false) }

    if (viewModel.isTtsPlayerBarMinimized) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .clickable {
                    onNavigateToReader(playingBook.id, playingChapter.id, viewModel.ttsActiveParagraphIndex ?: 0)
                }
                .testTag("tts_player_bar_minimized"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            ),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { viewModel.isTtsPlayerBarMinimized = false },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = "Expand Player Controls",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = "${playingBook.title} - ${playingChapter.title}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (viewModel.isPreparingVoice) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(
                        onClick = {
                            if (viewModel.ttsIsPlaying) {
                                viewModel.tts.pauseTts()
                            } else {
                                viewModel.tts.resumeTts()
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (viewModel.ttsIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (viewModel.ttsIsPlaying) "Pause Playback" else "Resume Playback",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.tts.stopTts() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop Playback",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("tts_player_bar"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // First Row: Cover, Book Title, Chapter Title, Voice In Use
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Book Cover
                val coverFile = remember(playingBook.coverLocalPath) {
                    if (!playingBook.coverLocalPath.isNullOrEmpty()) File(playingBook.coverLocalPath) else null
                }
                val coverBitmap = remember(coverFile) {
                    if (coverFile != null && coverFile.exists()) {
                        runCatching { BitmapFactory.decodeFile(coverFile.absolutePath) }.getOrNull()
                    } else null
                }

                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = "Book Cover",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Playing Audio",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Info text
                val selectedVoiceName = remember(viewModel.selectedVoiceId, viewModel.ttsVoices) {
                    viewModel.ttsVoices.find { it.id == viewModel.selectedVoiceId }?.name
                        ?.replace("Piper - ", "")?.replace("Kokoro - ", "") ?: "Default Voice"
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onNavigateToReader(playingBook.id, playingChapter.id, viewModel.ttsActiveParagraphIndex ?: 0)
                        },
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = playingBook.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playingChapter.title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Voice: $selectedVoiceName",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Minimize and stop buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.isTtsPlayerBarMinimized = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.UnfoldLess,
                            contentDescription = "Minimize Player Controls",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.tts.stopTts() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop Playback",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Preparing Voice Banner
            if (viewModel.isPreparingVoice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Preparing voice engine...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Second Row: Progress Slider
            val activePara = viewModel.ttsActiveParagraphIndex ?: -1
            val totalParas = viewModel.ttsTotalParagraphs
            val sliderValue = if (activePara < 0) 0f else activePara.toFloat()
            val maxSliderValue = if (totalParas <= 1) 1f else (totalParas - 1).toFloat()

            var draggingValue by remember { mutableStateOf<Float?>(null) }
            val currentSliderValue = draggingValue ?: sliderValue

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activePara < 0) "Reading Chapter Title" else "Paragraph ${currentSliderValue.toInt() + 1} of $totalParas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Sleep Timer Countdown Indicator
                    val sleepSecs = viewModel.sleepTimerRemainingSeconds
                    if (sleepSecs != null && sleepSecs > 0) {
                        val m = sleepSecs / 60
                        val s = sleepSecs % 60
                        Text(
                            text = "Sleep in ${m}m ${s}s",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (viewModel.sleepTimerMinutes == -1) {
                        Text(
                            text = "Sleep at end of Ch.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        Text(
                            text = "${((currentSliderValue / maxSliderValue) * 100).toInt().coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Slider(
                    value = currentSliderValue.coerceIn(0f, maxSliderValue),
                    onValueChange = { draggingValue = it },
                    onValueChangeFinished = {
                        draggingValue?.let {
                            viewModel.progress.seekToParagraph(it.toInt())
                        }
                        draggingValue = null
                    },
                    valueRange = 0f..maxSliderValue,
                    modifier = Modifier.fillMaxWidth().height(24.dp).testTag("tts_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                )
            }

            // Third Row: Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Auto-Scroll Toggle Button
                IconButton(
                    onClick = { viewModel.tts.toggleTtsAutoScroll() },
                    modifier = Modifier.size(48.dp).testTag("tts_quick_autoscroll_btn")
                ) {
                    Icon(
                        imageVector = if (viewModel.ttsAutoScrollEnabled) Icons.Default.MenuBook else Icons.Default.Book,
                        contentDescription = "Toggle Auto-Scroll Spoken Text",
                        tint = if (viewModel.ttsAutoScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Prev Chapter
                IconButton(
                    onClick = { viewModel.tts.playPreviousChapterTts() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Skip to Previous Chapter",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Skip back 1 paragraph
                IconButton(
                    onClick = { viewModel.progress.skipParagraph(-1) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NavigateBefore,
                        contentDescription = "Rewind One Paragraph",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play / Pause Toggle Button
                FilledIconButton(
                    onClick = {
                        if (viewModel.ttsIsPlaying) {
                            viewModel.tts.pauseTts()
                        } else {
                            viewModel.tts.resumeTts()
                        }
                    },
                    enabled = !viewModel.isPreparingVoice,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (viewModel.ttsIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.ttsIsPlaying) "Pause Playback" else "Start Playback",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Skip forward 1 paragraph
                IconButton(
                    onClick = { viewModel.progress.skipParagraph(1) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NavigateNext,
                        contentDescription = "Fast-Forward One Paragraph",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Next Chapter
                IconButton(
                    onClick = { viewModel.tts.playNextChapterTts() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip to Next Chapter",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Voice Settings Button
                IconButton(
                    onClick = {
                        viewModel.tts.initTts()
                        showSettingsDialog = true
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Voice & Speech Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // --- Voice Customization Bottom Sheet ---
    if (showSettingsDialog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        var voiceToImportFor by remember { mutableStateOf<PiperVoice?>(null) }

        val documentPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            val targetVoice = voiceToImportFor
            if (uri != null && targetVoice != null) {
                viewModel.tts.importPremiumVoice(uri, targetVoice)
            }
            voiceToImportFor = null
        }

        ModalBottomSheet(
            onDismissRequest = { showSettingsDialog = false },
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Narration Voice",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
                        )
                        Text(
                            text = "Choose an offline TTS voice",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = false }, modifier = Modifier.size(48.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close Voice Settings")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Selector
                var selectedTab by remember { mutableStateOf(0) }
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    divider = { Divider(color = MaterialTheme.colorScheme.surfaceVariant) }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Neural Voices", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = "Neural Offline Voices", modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("System Voices", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Hearing, contentDescription = "System Android Voices", modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Voice Settings", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Tune, contentDescription = "Speed and Pitch Settings", modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    var genderFilter by remember { mutableStateOf("All") }
                    var searchQuery by remember { mutableStateOf("") }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search by voice name or accent...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Voice Catalog") },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search query")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("All", "Female", "Male").forEach { filter ->
                                val isSelected = genderFilter == filter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { genderFilter = filter },
                                    label = { Text(filter, fontWeight = FontWeight.SemiBold) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        val piperVoices = viewModel.ttsVoices.filter {
                            it.id.startsWith("vits-piper-") || it.id.startsWith("kokoro-")
                        }.filter { voice ->
                            val piperVoice = PiperVoiceCatalog.getVoiceById(voice.id)
                            val matchesGender = when (genderFilter) {
                                "Female" -> piperVoice.gender.equals("Female", ignoreCase = true)
                                "Male" -> piperVoice.gender.equals("Male", ignoreCase = true)
                                else -> true
                            }
                            val matchesSearch = searchQuery.isBlank() ||
                                    piperVoice.name.contains(searchQuery, ignoreCase = true) ||
                                    piperVoice.accent.contains(searchQuery, ignoreCase = true)
                            matchesGender && matchesSearch
                        }

                        if (piperVoices.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No matching offline voices found.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(piperVoices) { voice ->
                                    val isSelected = voice.id == viewModel.selectedVoiceId
                                    val piperVoice = PiperVoiceCatalog.getVoiceById(voice.id)
                                    val isDownloaded = viewModel.tts.isVoiceDownloaded(piperVoice)
                                    val isThisVoiceDownloading = viewModel.premiumVoiceDownloading && viewModel.sherpaOnnxTtsEngine.selectedVoiceId == voice.id

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                if (isDownloaded) {
                                                    viewModel.tts.setTtsVoice(voice)
                                                    showSettingsDialog = false
                                                } else if (!isThisVoiceDownloading) {
                                                    viewModel.tts.downloadPremiumVoice(piperVoice)
                                                }
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                            }
                                        ),
                                        border = BorderStroke(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .background(
                                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                            else MaterialTheme.colorScheme.surfaceVariant,
                                                            shape = RoundedCornerShape(10.dp)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = if (isSelected) Icons.Default.VolumeUp else Icons.Default.RecordVoiceOver,
                                                        contentDescription = null,
                                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = piperVoice.name,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                            )
                                                        )

                                                        val badgeText = if (piperVoice.isKokoro) "KOKORO" else "PIPER"
                                                        val badgeBg = if (piperVoice.isKokoro) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                                        val badgeColor = if (piperVoice.isKokoro) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

                                                        Surface(
                                                            color = badgeBg,
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = badgeText,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Black,
                                                                    color = badgeColor
                                                                )
                                                            )
                                                        }

                                                        if (isDownloaded) {
                                                            Surface(
                                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                                shape = RoundedCornerShape(4.dp)
                                                            ) {
                                                                Text(
                                                                    text = "Downloaded",
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                                        fontSize = 9.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Text(
                                                        text = "${piperVoice.accent} • ${piperVoice.gender} • ${piperVoice.sizeMb}MB",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                // Sample Preview Button
                                                val isPlayingPreview = viewModel.previewingVoiceId == voice.id
                                                IconButton(
                                                    onClick = {
                                                        if (isPlayingPreview) {
                                                            viewModel.tts.stopVoicePreview()
                                                        } else {
                                                            viewModel.tts.playVoicePreview(voice)
                                                        }
                                                    },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (isPlayingPreview) Icons.Default.Stop else Icons.Default.PlayCircle,
                                                        contentDescription = "Preview Sample Audio",
                                                        tint = if (isPlayingPreview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                // Import from File button
                                                IconButton(
                                                    onClick = {
                                                        voiceToImportFor = piperVoice
                                                        documentPicker.launch(arrayOf("*/*"))
                                                    },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.FileOpen,
                                                        contentDescription = "Import Voice from File",
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            if (isThisVoiceDownloading) {
                                                LinearProgressIndicator(
                                                    progress = viewModel.premiumVoiceDownloadProgress / 100f,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTab == 1) {
                    // --- TAB 1: SYSTEM VOICES ---
                    val systemVoices = viewModel.ttsVoices.filter {
                        !it.id.startsWith("vits-piper-") && !it.id.startsWith("kokoro-")
                    }

                    if (systemVoices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No system TTS voices available.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(systemVoices) { voice ->
                                val isSelected = voice.id == viewModel.selectedVoiceId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            viewModel.tts.setTtsVoice(voice)
                                            showSettingsDialog = false
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        }
                                    ),
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Hearing,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = voice.name.replace("System: ", ""),
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                            Text(
                                                text = "System Native TTS Engine",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        val isPlayingPreview = viewModel.previewingVoiceId == voice.id
                                        IconButton(
                                            onClick = {
                                                if (isPlayingPreview) {
                                                    viewModel.tts.stopVoicePreview()
                                                } else {
                                                    viewModel.tts.playVoicePreview(voice)
                                                }
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isPlayingPreview) Icons.Default.Stop else Icons.Default.PlayCircle,
                                                contentDescription = "Preview Sample Audio",
                                                tint = if (isPlayingPreview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // --- TAB 2: VOICE SETTINGS ---
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Pitch Slider (0.5x to 2.0x)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Pitch",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${String.format("%.1f", viewModel.ttsPitch)}x",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                            Slider(
                                value = viewModel.ttsPitch,
                                onValueChange = { pitch ->
                                    viewModel.tts.updateTtsSettings(pitch, viewModel.ttsSpeed)
                                },
                                valueRange = 0.5f..2.0f
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Speed Slider (0.5x to 3.0x)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Speed",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${String.format("%.1f", viewModel.ttsSpeed)}x",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                            Slider(
                                value = viewModel.ttsSpeed,
                                onValueChange = { speed ->
                                    viewModel.tts.updateTtsSettings(viewModel.ttsPitch, speed)
                                },
                                valueRange = 0.5f..3.0f
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.tts.updateTtsSettings(1.0f, 1.0f) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Settings", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset Speed & Pitch to 1.0x", fontWeight = FontWeight.Bold)
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Sleep Timer
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Sleep Timer",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(0 to "Off", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "60m", -1 to "End Ch.").forEach { (mins, label) ->
                                    val isSelected = viewModel.sleepTimerMinutes == mins
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.tts.startSleepTimer(mins) },
                                        label = { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Auto-scroll Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Follow Spoken Text",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Scroll reader display automatically as text is spoken",
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
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showSettingsDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
