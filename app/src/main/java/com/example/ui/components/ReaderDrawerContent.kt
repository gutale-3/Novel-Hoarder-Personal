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
    onConfirmDeleteChapters: (List<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var drawerTabSelected by remember { mutableIntStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    var chapterSubTab by remember { mutableIntStateOf(0) } // 0 = Active, 1 = Archived
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedChapters by remember { mutableStateOf(setOf<String>()) }
    var chapterForTagging by remember { mutableStateOf<ChapterEntity?>(null) }

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
                        IconButton(onClick = onOpenAddChapterDialog) {
                            Icon(imageVector = Icons.Default.PostAdd, contentDescription = "Add Single Chapter", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onOpenResequenceDialog) {
                            Icon(imageVector = Icons.Default.List, contentDescription = "Auto-Resequence Chapters", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onOpenAutoArchiveDialog) {
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
                                Text("Archive", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

            val currentList = if (chapterSubTab == 0) {
                val activeFilter = viewModel.chapterTags.activeTagFilter
                if (activeFilter != null) {
                    chapters.filter { viewModel.chapterTags.hasTag(it.id, activeFilter) }
                } else {
                    chapters
                }
            } else archivedChapters

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
                                                            isMultiSelectMode = true
                                                            selectedChapters = setOf(ch.id)
                                                        }
                                                    },
                                                    onTap = {
                                                        if (isMultiSelectMode) {
                                                            selectedChapters = if (isSelected) {
                                                                selectedChapters - ch.id
                                                            } else {
                                                                selectedChapters + ch.id
                                                            }
                                                        } else {
                                                            onSelectChapter(ch.id)
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        Text(
                                            text = ch.title,
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
                                            onClick = { chapterForTagging = ch },
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
                                    selectedChapters = if (isSelected) {
                                        selectedChapters - ch.id
                                    } else {
                                        selectedChapters + ch.id
                                    }
                                } else {
                                    onSelectChapter(ch.id)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
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
}
