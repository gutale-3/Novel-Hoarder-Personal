package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.DuplicateChapters
import com.example.util.ChapterGroup
import com.example.util.ChapterSubdivisionHelper
import com.example.util.TocSubdivisionMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReaderDrawerContent(
    bookId: String,
    currentChapterId: String,
    chapters: List<ChapterEntity>,
    archivedChapters: List<ChapterEntity>,
    bookmarks: List<BookmarkEntity>,
    viewModel: MainViewModel,
    context: Context,
    onSelectChapter: (String) -> Unit,
    onOpenAddChapterDialog: () -> Unit,
    onOpenResequenceDialog: () -> Unit,
    onOpenAutoArchiveDialog: () -> Unit,
    onConfirmDeleteChapters: (List<String>) -> Unit,
    onOpenRevisionHistory: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var drawerTabSelected by remember { mutableIntStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var chapterSubTab by remember { mutableIntStateOf(0) } // 0 = Active, 1 = Archived
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedChapters by remember { mutableStateOf(setOf<String>()) }
    var expandedHiddenRuns by remember { mutableStateOf(setOf<String>()) }
    var chapterForTagging by remember { mutableStateOf<ChapterEntity?>(null) }
    var duplicateGroups by remember { mutableStateOf<List<List<ChapterEntity>>>(emptyList()) }
    var showDuplicates by remember { mutableStateOf(false) }
    var showStructure by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    if (showQuality) {
        val qualityBook by remember(bookId) { viewModel.repository.getBookFlow(bookId) }.collectAsState(null)
        qualityBook?.let { ChapterQualityDialog(it) { showQuality = false } }
    }
    if (showStructure) ChapterStructureDialog(bookId, viewModel) { showStructure = false }
    if (showDuplicates) {
        val extras = duplicateGroups.sumOf { it.size - 1 }
        AlertDialog(
            onDismissRequest = { showDuplicates = false },
            title = { Text("Remove $extras duplicate chapter${if (extras == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "For each chapter saved twice, the copy you've read (or the older one) is kept and the " +
                            "others go to the Trash, where you can restore them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    duplicateGroups.take(8).forEach { g ->
                        Text("• ${g.first().title} ×${g.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (duplicateGroups.size > 8) Text("…and ${duplicateGroups.size - 8} more", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDuplicates = false
                    val groups = duplicateGroups
                    scope.launch {
                        for (g in groups) {
                            val keep = g.first()
                            if (!keep.isRead && g.any { it.isRead }) viewModel.repository.updateChapterReadStatus(keep.id, true)
                        }
                        viewModel.repository.softDeleteChapters(groups.flatMap { it.drop(1) }.map { it.id })
                        android.widget.Toast.makeText(context, "Moved $extras duplicates to the Trash", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDuplicates = false }) { Text("Cancel") } }
        )
    }

    var subdivisionMode by remember(bookId) {
        mutableStateOf(TocSubdivisionMode.fromCode(viewModel.settings.getBookTocSubdivisionMode(bookId)))
    }
    var batchSize by remember(bookId) {
        mutableIntStateOf(viewModel.settings.getBookTocBatchSize(bookId))
    }
    var customVolumeSplits by remember(bookId) {
        mutableStateOf(viewModel.settings.getBookCustomVolumeSplits(bookId))
    }
    var showSubdivisionDialog by remember { mutableStateOf(false) }
    var showVolumeSplitsDialog by remember { mutableStateOf(false) }

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
                                text = "No saved bookmarks or highlights in this book yet.\nLong-press any paragraph to bookmark or add notes!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(bookmarks) { b ->
                        val chName = chapters.find { it.id == b.chapterId }?.title ?: "Unknown Chapter"
                        val formattedTime = remember(b.timestamp) {
                            if (b.timestamp > 0) {
                                val sdf = SimpleDateFormat("h:mm a · MMM d", Locale.getDefault())
                                sdf.format(Date(b.timestamp))
                            } else {
                                ""
                            }
                        }
                        Card(
                            onClick = {
                                onSelectChapter(b.chapterId)
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = chName,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.tertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (formattedTime.isNotEmpty()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.padding(top = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AccessTime,
                                                    contentDescription = "Created Time",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                                )
                                                Text(
                                                    text = formattedTime,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium
                                                    ),
                                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    }
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
                        // Select stays visible; the less used tools live in a menu so the row
                        // never pushes buttons off the edge of the drawer.
                        FilledTonalButton(
                            onClick = {
                                isMultiSelectMode = true
                                selectedChapters = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Select", maxLines = 1, softWrap = false)
                        }
                        Box {
                            var toolsMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { toolsMenu = true }) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Chapter tools")
                            }
                            DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add a chapter") },
                                    leadingIcon = { Icon(Icons.Default.PostAdd, null) },
                                    onClick = { toolsMenu = false; onOpenAddChapterDialog() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Re-number chapters") },
                                    leadingIcon = { Icon(Icons.Default.List, null) },
                                    onClick = { toolsMenu = false; onOpenResequenceDialog() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Auto-hide settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = { toolsMenu = false; onOpenAutoArchiveDialog() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Undo & history") },
                                    leadingIcon = { Icon(Icons.Default.History, null) },
                                    onClick = { toolsMenu = false; onOpenRevisionHistory() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fix chapter order & numbering") },
                                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, null) },
                                    onClick = { toolsMenu = false; showStructure = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Check chapter problems") },
                                    leadingIcon = { Icon(Icons.Default.FactCheck, null) },
                                    onClick = { toolsMenu = false; showQuality = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove duplicate chapters") },
                                    leadingIcon = { Icon(Icons.Default.CopyAll, null) },
                                    onClick = {
                                        toolsMenu = false
                                        scope.launch {
                                            duplicateGroups = DuplicateChapters.find(
                                                viewModel.repository.getAllChaptersIncludingHidden(bookId)
                                            )
                                            if (duplicateGroups.isEmpty()) {
                                                android.widget.Toast.makeText(context, "No duplicate chapters found", android.widget.Toast.LENGTH_SHORT).show()
                                            } else showDuplicates = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Group chapters") },
                                    leadingIcon = { Icon(Icons.Default.FolderSpecial, null) },
                                    onClick = { toolsMenu = false; showSubdivisionDialog = true }
                                )
                            }
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
                    text = { Text("Hidden (${archivedChapters.size})", fontSize = 14.sp) }
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))

            // --- TOC Subdivision Bar ---
            val activeListForTOC = if (chapterSubTab == 0) {
                val activeFilter = viewModel.chapterTags.activeTagFilter
                if (activeFilter != null) {
                    chapters.filter { viewModel.chapterTags.hasTag(it.id, activeFilter) }
                } else chapters
            } else archivedChapters

            val currentGroups = remember(activeListForTOC, subdivisionMode, batchSize, customVolumeSplits) {
                ChapterSubdivisionHelper.subdivideChapters(
                    chapters = activeListForTOC,
                    mode = subdivisionMode,
                    batchSize = batchSize,
                    customVolumeSplits = customVolumeSplits
                )
            }

            var expandedGroupIds by remember(bookId, subdivisionMode, chapterSubTab) {
                val initial = mutableSetOf<String>()
                val activeGroupId = ChapterSubdivisionHelper.findGroupIdForChapter(currentGroups, currentChapterId)
                if (activeGroupId != null) {
                    initial.add(activeGroupId)
                } else if (currentGroups.isNotEmpty()) {
                    initial.add(currentGroups.first().id)
                }
                mutableStateOf(initial.toSet())
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showSubdivisionDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = when (subdivisionMode) {
                        TocSubdivisionMode.AS_IS -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        TocSubdivisionMode.BATCH_CHAPTERS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        TocSubdivisionMode.VOLUMES -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (subdivisionMode) {
                            TocSubdivisionMode.AS_IS -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            TocSubdivisionMode.BATCH_CHAPTERS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            TocSubdivisionMode.VOLUMES -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = when (subdivisionMode) {
                                TocSubdivisionMode.AS_IS -> Icons.Default.FormatListNumbered
                                TocSubdivisionMode.BATCH_CHAPTERS -> Icons.Default.Layers
                                TocSubdivisionMode.VOLUMES -> Icons.Default.AutoStories
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = when (subdivisionMode) {
                                TocSubdivisionMode.AS_IS -> MaterialTheme.colorScheme.onSurfaceVariant
                                TocSubdivisionMode.BATCH_CHAPTERS -> MaterialTheme.colorScheme.primary
                                TocSubdivisionMode.VOLUMES -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Text(
                            text = when (subdivisionMode) {
                                TocSubdivisionMode.AS_IS -> "Leave As Is"
                                TocSubdivisionMode.BATCH_CHAPTERS -> "Chapters (1–$batchSize)"
                                TocSubdivisionMode.VOLUMES -> "Volumes (${currentGroups.size})"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (subdivisionMode) {
                                TocSubdivisionMode.AS_IS -> MaterialTheme.colorScheme.onSurface
                                TocSubdivisionMode.BATCH_CHAPTERS -> MaterialTheme.colorScheme.primary
                                TocSubdivisionMode.VOLUMES -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Change division mode",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (subdivisionMode != TocSubdivisionMode.AS_IS) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (subdivisionMode == TocSubdivisionMode.VOLUMES) {
                            IconButton(
                                onClick = { showVolumeSplitsDialog = true },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Configure Volume Ranges",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        val allGroupsExpanded = currentGroups.isNotEmpty() && currentGroups.all { expandedGroupIds.contains(it.id) }
                        TextButton(
                            onClick = {
                                expandedGroupIds = if (allGroupsExpanded) {
                                    emptySet()
                                } else {
                                    currentGroups.map { it.id }.toSet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = if (allGroupsExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = if (allGroupsExpanded) "Collapse All" else "Expand All",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            if (isMultiSelectMode) {
                // One-tap selections for the usual clean-ups: chapters already read, and empty
                // ones (listed but never downloaded, or blanked).
                val pool = if (chapterSubTab == 0) chapters else archivedChapters
                Text(
                    "Tip: hold a chapter, then slide up or down to select everything in between.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        AssistChip(
                            onClick = { selectedChapters = pool.filter { it.isRead }.map { it.id }.toSet() },
                            label = { Text("Read (${pool.count { it.isRead }})", fontSize = 12.sp) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { selectedChapters = pool.filter { it.content.isBlank() }.map { it.id }.toSet() },
                            label = { Text("Empty (${pool.count { it.content.isBlank() }})", fontSize = 12.sp) }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { selectedChapters = pool.map { it.id }.toSet() },
                            label = { Text("All", fontSize = 12.sp) }
                        )
                    }
                    item {
                        AssistChip(onClick = { selectedChapters = emptySet() }, label = { Text("None", fontSize = 12.sp) })
                    }
                }
            }

            if (isMultiSelectMode && selectedChapters.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (chapterSubTab == 0) {
                            // Tick (Mark Read) - no words
                            IconButton(
                                onClick = {
                                    viewModel.progress.updateChaptersReadStatus(selectedChapters.toList(), true)
                                    isMultiSelectMode = false
                                    selectedChapters = emptySet()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Mark Read",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Double tick with slash (Mark Unread) - no words
                            IconButton(
                                onClick = {
                                    viewModel.progress.updateChaptersReadStatus(selectedChapters.toList(), false)
                                    isMultiSelectMode = false
                                    selectedChapters = emptySet()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RemoveDone,
                                    contentDescription = "Mark Unread",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Move Up
                            IconButton(
                                onClick = {
                                    viewModel.library.moveChaptersUp(bookId, selectedChapters.toList())
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Move Selected Up",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Move Down
                            IconButton(
                                onClick = {
                                    viewModel.library.moveChaptersDown(bookId, selectedChapters.toList())
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move Selected Down",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Rescrape / Rescribe
                            if (viewModel.rescrapingChapterId != null) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .padding(2.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        val selList = chapters.filter { selectedChapters.contains(it.id) }
                                        viewModel.scraping.rescrapeBatchChapters(selList) { _, msg ->
                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            isMultiSelectMode = false
                                            selectedChapters = emptySet()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Rescrape Chapters",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Archive - has words
                            TextButton(
                                onClick = {
                                    viewModel.progress.updateChaptersArchiveStatus(selectedChapters.toList(), true)
                                    isMultiSelectMode = false
                                    selectedChapters = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Hide", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        } else {
                            // Restore in archived tab - has words
                            TextButton(
                                onClick = {
                                    viewModel.progress.updateChaptersArchiveStatus(selectedChapters.toList(), false)
                                    isMultiSelectMode = false
                                    selectedChapters = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Restore", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        // Delete (Bin) - no words
                        IconButton(
                            onClick = {
                                val toDel = selectedChapters.toList()
                                isMultiSelectMode = false
                                selectedChapters = emptySet()
                                onConfirmDeleteChapters(toDel)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider()

            if (chapterSubTab == 0) {
                val allPresetTags = viewModel.chapterTags.getAllAvailableTags()
                val tagsInThisBook = allPresetTags.filter { tag ->
                    chapters.any { viewModel.chapterTags.hasTag(it.id, tag.id) }
                }
                if (tagsInThisBook.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = viewModel.chapterTags.activeTagFilter == null,
                                onClick = { viewModel.chapterTags.activeTagFilter = null },
                                label = { Text("All (${chapters.size})", fontSize = 11.sp) }
                            )
                        }
                        items(tagsInThisBook) { tag ->
                            val isSel = viewModel.chapterTags.activeTagFilter == tag.id
                            val count = chapters.count { viewModel.chapterTags.hasTag(it.id, tag.id) }
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    viewModel.chapterTags.activeTagFilter = if (isSel) null else tag.id
                                },
                                leadingIcon = { Text(tag.icon, fontSize = 12.sp) },
                                label = { Text("${tag.name} ($count)", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            val currentList = activeListForTOC

            if (currentList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (chapterSubTab == 0) {
                            if (viewModel.chapterTags.activeTagFilter != null) "No chapters with this milestone tag."
                            else "No active chapters."
                        } else "No archived chapters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val chapterListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    state = chapterListState,
                    modifier = Modifier
                        .fillMaxSize()
                        // In Select mode: hold a chapter, then slide to select everything you pass.
                        .dragToSelect(
                            enabled = isMultiSelectMode,
                            listState = chapterListState,
                            orderedKeys = currentList.map { it.id },
                            selected = selectedChapters,
                            onSelectionChange = { selectedChapters = it }
                        ),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    val hiddenInPlace = subdivisionMode == TocSubdivisionMode.AS_IS && chapterSubTab == 0 &&
                        viewModel.chapterTags.activeTagFilter == null && archivedChapters.isNotEmpty()
                    if (hiddenInPlace) {
                        // Hidden chapters stay where they are in the list, folded into one row per
                        // run ("3 hidden · Ch. 12–14"); tap to look inside and unhide any of them.
                        hiddenRunSegments(chapters, archivedChapters).forEach { seg ->
                            if (seg.size == 1 && !seg[0].isArchived) {
                                val ch = seg[0]
                                item(key = ch.id) {
                                    ChapterDrawerRowItem(
                                        ch = ch,
                                        isCurrent = ch.id == currentChapterId,
                                        isSelected = selectedChapters.contains(ch.id),
                                        isMultiSelectMode = isMultiSelectMode,
                                        chapterSubTab = chapterSubTab,
                                        viewModel = viewModel,
                                        scope = scope,
                                        onSelectChapter = onSelectChapter,
                                        onTagChapter = { chapterForTagging = it },
                                        onToggleSelect = { isChecked ->
                                            selectedChapters = if (isChecked) selectedChapters + ch.id else selectedChapters - ch.id
                                        }
                                    )
                                }
                            } else {
                                val runKey = "hidden_" + seg.first().id
                                item(key = runKey) {
                                    HiddenRunRow(
                                        run = seg,
                                        expanded = expandedHiddenRuns.contains(runKey),
                                        onToggle = {
                                            expandedHiddenRuns = if (expandedHiddenRuns.contains(runKey))
                                                expandedHiddenRuns - runKey else expandedHiddenRuns + runKey
                                        },
                                        onUnhide = { ids -> viewModel.progress.updateChaptersArchiveStatus(ids, false) },
                                        // The reader skips hidden chapters, so opening one unhides it first.
                                        onOpen = { id ->
                                            viewModel.progress.updateChaptersArchiveStatus(listOf(id), false)
                                            onSelectChapter(id)
                                        }
                                    )
                                }
                            }
                        }
                    } else if (subdivisionMode == TocSubdivisionMode.AS_IS) {
                        items(currentList, key = { it.id }) { ch ->
                            ChapterDrawerRowItem(
                                ch = ch,
                                isCurrent = ch.id == currentChapterId,
                                isSelected = selectedChapters.contains(ch.id),
                                isMultiSelectMode = isMultiSelectMode,
                                chapterSubTab = chapterSubTab,
                                viewModel = viewModel,
                                scope = scope,
                                onSelectChapter = onSelectChapter,
                                onTagChapter = { chapterForTagging = it },
                                onToggleSelect = { isChecked ->
                                    selectedChapters = if (isChecked) selectedChapters + ch.id else selectedChapters - ch.id
                                }
                            )
                        }
                    } else {
                        currentGroups.forEach { group ->
                            val isExpanded = expandedGroupIds.contains(group.id)
                            item(key = "group_hdr_${group.id}") {
                                ChapterGroupHeader(
                                    group = group,
                                    isExpanded = isExpanded,
                                    containsActiveChapter = group.chapters.any { it.id == currentChapterId },
                                    isMultiSelectMode = isMultiSelectMode,
                                    selectedChapters = selectedChapters,
                                    onToggleExpand = {
                                        expandedGroupIds = if (isExpanded) {
                                            expandedGroupIds - group.id
                                        } else {
                                            expandedGroupIds + group.id
                                        }
                                    },
                                    onToggleSelectGroup = { selectAll ->
                                        val gIds = group.chapters.map { it.id }.toSet()
                                        selectedChapters = if (selectAll) {
                                            selectedChapters + gIds
                                        } else {
                                            selectedChapters - gIds
                                        }
                                    }
                                )
                            }

                            if (isExpanded) {
                                items(group.chapters, key = { it.id }) { ch ->
                                    ChapterDrawerRowItem(
                                        ch = ch,
                                        isCurrent = ch.id == currentChapterId,
                                        isSelected = selectedChapters.contains(ch.id),
                                        isMultiSelectMode = isMultiSelectMode,
                                        chapterSubTab = chapterSubTab,
                                        viewModel = viewModel,
                                        scope = scope,
                                        onSelectChapter = onSelectChapter,
                                        onTagChapter = { chapterForTagging = it },
                                        onToggleSelect = { isChecked ->
                                            selectedChapters = if (isChecked) selectedChapters + ch.id else selectedChapters - ch.id
                                        },
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (chapterForTagging != null) {
        ChapterTagDialog(
            chapter = chapterForTagging,
            tagManager = viewModel.chapterTags,
            onDismiss = { chapterForTagging = null }
        )
    }

    if (showSubdivisionDialog) {
        SubdivisionModeDialog(
            currentMode = subdivisionMode,
            currentBatchSize = batchSize,
            onSelectMode = { newMode, newBatchSize ->
                subdivisionMode = newMode
                batchSize = newBatchSize
                viewModel.settings.setBookTocSubdivisionMode(bookId, newMode.code)
                viewModel.settings.setBookTocBatchSize(bookId, newBatchSize)
            },
            onOpenVolumeSplits = {
                showSubdivisionDialog = false
                showVolumeSplitsDialog = true
            },
            onDismiss = { showSubdivisionDialog = false }
        )
    }

    if (showVolumeSplitsDialog) {
        VolumeSplitsDialog(
            bookId = bookId,
            bookTitle = chapters.firstOrNull()?.title?.let { "Volume Setup" } ?: "Novel Volumes",
            chapters = chapters,
            initialSplits = customVolumeSplits,
            onSaveSplits = { newSplits ->
                customVolumeSplits = newSplits
                viewModel.settings.setBookCustomVolumeSplits(bookId, newSplits)
            },
            onDismiss = { showVolumeSplitsDialog = false }
        )
    }
}

@Composable
private fun ChapterGroupHeader(
    group: ChapterGroup,
    isExpanded: Boolean,
    containsActiveChapter: Boolean,
    isMultiSelectMode: Boolean,
    selectedChapters: Set<String>,
    onToggleExpand: () -> Unit,
    onToggleSelectGroup: (Boolean) -> Unit
) {
    val allGroupChaptersSelected = group.chapters.isNotEmpty() && group.chapters.all { selectedChapters.contains(it.id) }

    Surface(
        onClick = onToggleExpand,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (containsActiveChapter) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            1.dp,
            if (containsActiveChapter) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Drop-down chevron icon
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse ${group.title}" else "Expand ${group.title}",
                    tint = if (containsActiveChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )

                // Volume / Batch Icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (containsActiveChapter) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (group.volumeIndex != null) Icons.Default.AutoStories else Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (containsActiveChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (containsActiveChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (containsActiveChapter) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "READING",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "${group.totalCount} ch",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (group.isAllRead) "✓ All read" else "${group.readCount}/${group.totalCount} read",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (group.isAllRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontWeight = if (group.isAllRead) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Right side: multi-select checkbox or chevron toggle
            if (isMultiSelectMode) {
                Checkbox(
                    checked = allGroupChaptersSelected,
                    onCheckedChange = { checked ->
                        onToggleSelectGroup(checked)
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
            } else {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterDrawerRowItem(
    ch: ChapterEntity,
    isCurrent: Boolean,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    chapterSubTab: Int,
    viewModel: MainViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelectChapter: (String) -> Unit,
    onTagChapter: (ChapterEntity) -> Unit,
    onToggleSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
                        onCheckedChange = { onToggleSelect(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                } else {
                    if (chapterSubTab == 0) {
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
                val chTags = viewModel.chapterTags.getTagsForChapter(ch.id)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(isMultiSelectMode, isSelected, ch.id) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!isMultiSelectMode) {
                                        onToggleSelect(true)
                                    }
                                },
                                onTap = {
                                    if (isMultiSelectMode) {
                                        onToggleSelect(!isSelected)
                                    } else {
                                        onSelectChapter(ch.id)
                                    }
                                }
                            )
                        }
                ) {
                    Text(
                        text = com.example.viewmodel.ChapterNumbering.label(androidx.compose.ui.platform.LocalContext.current, ch),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary 
                                else if (isStub) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                else if (ch.isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.onSurface
                    )
                    if (chTags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            chTags.forEach { tag ->
                                val tagColor = Color(tag.colorHex)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = tagColor.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "${tag.icon} ${tag.name}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tagColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }

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
                    IconButton(
                        onClick = { onTagChapter(ch) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Label,
                            contentDescription = "Tag Chapter",
                            modifier = Modifier.size(18.dp),
                            tint = if (chTags.isNotEmpty()) Color(chTags.first().colorHex) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        },
        selected = !isMultiSelectMode && isCurrent,
        onClick = {
            if (isMultiSelectMode) {
                onToggleSelect(!isSelected)
            } else {
                onSelectChapter(ch.id)
            }
        },
        modifier = modifier.then(Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    )
}

/**
 * Splits the book into display segments in chapter order: a visible chapter on its own, or a run
 * of consecutive hidden chapters together.
 */
private fun hiddenRunSegments(visible: List<ChapterEntity>, hidden: List<ChapterEntity>): List<List<ChapterEntity>> {
    val all = (visible + hidden).sortedWith(compareBy({ it.chapterNumber }, { it.id }))
    val out = mutableListOf<MutableList<ChapterEntity>>()
    for (ch in all) {
        val last = out.lastOrNull()
        if (ch.isArchived && last != null && last.first().isArchived) last += ch
        else out += mutableListOf(ch)
    }
    return out
}

@Composable
private fun HiddenRunRow(
    run: List<ChapterEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUnhide: (List<String>) -> Unit,
    onOpen: (String) -> Unit
) {
    val range = if (run.size == 1) "Ch. ${run.first().chapterNumber}"
    else "Ch. ${run.first().chapterNumber}–${run.last().chapterNumber}"
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${run.size} hidden · $range",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onUnhide(run.map { it.id }) }) {
                    Text(if (run.size == 1) "Unhide" else "Unhide all", maxLines = 1, softWrap = false)
                }
            }
            if (expanded) {
                run.forEach { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onOpen(ch.id) }, modifier = Modifier.weight(1f)) {
                            Text(
                                ch.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        TextButton(onClick = { onUnhide(listOf(ch.id)) }) { Text("Unhide", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}
