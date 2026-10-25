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
    showResume: Boolean = true,
    modifier: Modifier = Modifier
) {
    val playingBook = viewModel.ttsPlayingBook
    val playingChapter = viewModel.ttsPlayingChapter

    if (playingBook == null || playingChapter == null) {
        // "Continue listening" only where it helps (Home), as a slim pill.
        if (viewModel.hasResumableSession && showResume) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("tts_resume_bar"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.progress.resumeLastSession() }
                        .padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow, contentDescription = "Resume listening",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(8.dp).size(24.dp)
                    )
                    Text(
                        text = "Continue: ${viewModel.resumeBookName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.progress.clearTtsProgress(); viewModel.progress.loadResumableTtsSession() }) {
                        Icon(
                            Icons.Default.Close, contentDescription = "Dismiss",
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
    val activePara = viewModel.ttsActiveParagraphIndex ?: -1
    val totalParas = viewModel.ttsTotalParagraphs
    val fraction = if (totalParas <= 1 || activePara < 0) 0f else (activePara.toFloat() / (totalParas - 1)).coerceIn(0f, 1f)
    val sleepLabel: String? = run {
        val secs = viewModel.sleepTimerRemainingSeconds
        when {
            secs != null && secs > 0 && viewModel.ttsIsPlaying -> "%d:%02d".format(secs / 60, secs % 60)
            secs != null && secs > 0 -> "${secs / 60}m"
            viewModel.sleepTimerMinutes == -1 -> "End of ch."
            else -> null
        }
    }
    val openReader = { onNavigateToReader(playingBook.id, playingChapter.id, viewModel.ttsActiveParagraphIndex ?: 0) }
    val playPause = { if (viewModel.ttsIsPlaying) viewModel.tts.pauseTts() else viewModel.tts.resumeTts() }

    @Composable
    fun Cover(size: androidx.compose.ui.unit.Dp) {
        val coverBitmap = remember(playingBook.coverLocalPath) {
            playingBook.coverLocalPath?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
                ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        }
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2)) }
        }
    }

    // Mini player: one slim, slightly see-through row; the page keeps scrolling underneath.
    if (viewModel.isTtsPlayerBarMinimized) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("tts_player_bar_minimized"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp).copy(alpha = 0.92f),
            shadowElevation = 6.dp
        ) {
            Column {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = Color.Transparent
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.clickable(onClick = openReader)) { Cover(40.dp) }
                    Column(
                        Modifier.weight(1f).padding(horizontal = 10.dp).clickable { viewModel.isTtsPlayerBarMinimized = false }
                    ) {
                        Text(
                            playingChapter.title, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(playingBook.title, sleepLabel?.let { "⏾ $it" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (viewModel.isPreparingVoice) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = playPause) {
                            Icon(
                                if (viewModel.ttsIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (viewModel.ttsIsPlaying) "Pause" else "Play",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.tts.playNextChapterTts() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
                    }
                    IconButton(onClick = { viewModel.isTtsPlayerBarMinimized = false }) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Show all controls")
                    }
                }
            }
        }
        return
    }

    // Full controls
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("tts_player_bar"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp).copy(alpha = 0.97f),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(onClick = openReader)) { Cover(44.dp) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable(onClick = openReader)) {
                    Text(
                        playingChapter.title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        playingBook.title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { viewModel.isTtsPlayerBarMinimized = true }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Make smaller")
                }
                IconButton(onClick = { viewModel.tts.stopTts() }) {
                    Icon(Icons.Default.Close, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (viewModel.isPreparingVoice) {
                Text(
                    "Preparing voice…", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Position: drag to jump to a paragraph
            val sliderValue = if (activePara < 0) 0f else activePara.toFloat()
            val maxSliderValue = if (totalParas <= 1) 1f else (totalParas - 1).toFloat()
            var draggingValue by remember { mutableStateOf<Float?>(null) }
            val currentSliderValue = draggingValue ?: sliderValue
            Slider(
                value = currentSliderValue.coerceIn(0f, maxSliderValue),
                onValueChange = { draggingValue = it },
                onValueChangeFinished = {
                    draggingValue?.let { viewModel.progress.seekToParagraph(it.toInt()) }
                    draggingValue = null
                },
                valueRange = 0f..maxSliderValue,
                modifier = Modifier.fillMaxWidth().height(28.dp).testTag("tts_slider")
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (activePara < 0) "Title" else "¶ ${currentSliderValue.toInt() + 1} / $totalParas",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Transport
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.tts.playPreviousChapterTts() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
                }
                IconButton(onClick = { viewModel.progress.skipParagraph(-1) }) {
                    Icon(Icons.Default.NavigateBefore, contentDescription = "Back one paragraph", modifier = Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = playPause,
                    enabled = !viewModel.isPreparingVoice,
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        if (viewModel.ttsIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.ttsIsPlaying) "Pause" else "Play",
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = { viewModel.progress.skipParagraph(1) }) {
                    Icon(Icons.Default.NavigateNext, contentDescription = "Forward one paragraph", modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { viewModel.tts.playNextChapterTts() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
                }
            }

            // Quick settings: labelled chips instead of unexplained icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                AssistChip(
                    onClick = { viewModel.tts.initTts(); showSettingsDialog = true },
                    label = {
                        val sp = viewModel.ttsSpeed
                        Text(if (sp % 1f == 0f) "${sp.toInt()}x" else "%.2f".format(sp).trimEnd('0') + "x")
                    },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(16.dp)) }
                )
                AssistChip(
                    onClick = { viewModel.tts.initTts(); showSettingsDialog = true },
                    label = { Text(sleepLabel ?: "Sleep") },
                    leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = viewModel.ttsAutoScrollEnabled,
                    onClick = { viewModel.tts.toggleTtsAutoScroll() },
                    label = { Text("Follow") },
                    leadingIcon = { Icon(Icons.Default.MenuBook, null, Modifier.size(16.dp)) }
                )
                IconButton(onClick = { viewModel.tts.initTts(); showSettingsDialog = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Voice settings")
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Sleep Timer",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                if (viewModel.sleepTimerMinutes > 0) {
                                    val mins = viewModel.sleepTimerMinutes
                                    Text(
                                        text = "$mins min · restarts each play",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
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

                            // Smooth Audio Fade-Out Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Smooth 60s Audio Fade-Out",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Gently lowers playback volume over the final 60 seconds before pause so you fall asleep peacefully",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = viewModel.sleepTimerFadeOutEnabled,
                                    onCheckedChange = { viewModel.sleepTimerFadeOutEnabled = it }
                                )
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
