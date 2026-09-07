package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.BookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun AutoArchiveSettingsDialog(
    book: BookEntity,
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentHours = book.autoArchiveHours

    AlertDialog(
        onDismissRequest = onDismiss,
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
                                    viewModel.repository.updateBook(book.copy(autoArchiveHours = hours))
                                    onDismiss()
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
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun AddSingleChapterDialog(
    bookId: String,
    chapters: List<ChapterEntity>,
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    val sortedActiveChs = remember(chapters) { chapters.sortedBy { it.chapterNumber } }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var addChTitle by remember { mutableStateOf("") }
    var addChContent by remember { mutableStateOf("") }
    var addChNumberStr by remember { mutableStateOf("") }
    var addChInsertPosition by remember { mutableIntStateOf(-1) } // -1 = end, 0 = beginning, >0 = after that index

    AlertDialog(
        onDismissRequest = onDismiss,
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
                            onDismiss()
                            android.widget.Toast.makeText(context, "Chapter added successfully!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            ) {
                Text("Add Chapter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ResequenceChaptersDialog(
    bookId: String,
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    var resequencePrefix by remember { mutableStateOf("Chapter") }
    var resequenceStartNumberStr by remember { mutableStateOf("1") }

    val startNum = resequenceStartNumberStr.toIntOrNull() ?: 1
    val previewTitle1 = if (resequencePrefix.trim().isEmpty()) "$startNum" else "${resequencePrefix.trim()} $startNum"
    val previewTitle2 = if (resequencePrefix.trim().isEmpty()) "${startNum + 1}" else "${resequencePrefix.trim()} ${startNum + 1}"
    
    AlertDialog(
        onDismissRequest = onDismiss,
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
                            onDismiss()
                            android.widget.Toast.makeText(context, "Successfully re-sequenced $count chapters!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            ) {
                Text("Resequence All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ParagraphBookmarkActionDialog(
    bookId: String,
    currentChapterId: String,
    activeChapter: ChapterEntity?,
    bookState: BookEntity?,
    paragraphIndex: Int,
    paragraphText: String,
    existingBookmark: BookmarkEntity?,
    viewModel: MainViewModel,
    context: Context,
    onScrollToParagraph: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var bookmarkNoteText by remember { mutableStateOf(existingBookmark?.note ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (existingBookmark != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "Bookmark",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Paragraph Actions", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = paragraphText,
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
                                    onScrollToParagraph(paragraphIndex)
                                    viewModel.progress.saveReadingProgress(
                                        bookId = bookId,
                                        chapterId = currentChapterId,
                                        paragraphIndex = paragraphIndex
                                    )
                                    onDismiss()
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
                    if (activeChapter != null && bookState != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.tts.speak(
                                        text = activeChapter.content,
                                        book = bookState,
                                        chapter = activeChapter,
                                        startFromParagraphIndex = paragraphIndex
                                    )
                                    onDismiss()
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

                    // Action 3A: Delete strictly after this line
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    if (activeChapter != null) {
                                        val lines = activeChapter.content.split("\n")
                                        val indexInLines = lines.indexOfFirst { it.trim() == paragraphText.trim() }
                                        if (indexInLines != -1) {
                                            val newContent = lines.subList(0, indexInLines + 1).joinToString("\n").trim()
                                            viewModel.repository.updateChapterContent(activeChapter.id, newContent)
                                            android.widget.Toast.makeText(context, "Deleted all content after this line.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    onDismiss()
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Delete After This Line",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Keep this line and delete all following lines in this chapter",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Action 3B: Delete from this line
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    if (activeChapter != null) {
                                        val lines = activeChapter.content.split("\n")
                                        val indexInLines = lines.indexOfFirst { it.trim() == paragraphText.trim() }
                                        if (indexInLines != -1) {
                                            val newContent = lines.subList(0, indexInLines).joinToString("\n").trim()
                                            viewModel.repository.updateChapterContent(activeChapter.id, newContent)
                                            android.widget.Toast.makeText(context, "Deleted this line and following content.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    onDismiss()
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
                                text = "Delete This Line and Everything After",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Delete this line and all following lines in this chapter",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Action 4: Truncate matching lines in ALL chapters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    val query = paragraphText.trim()
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
                                    onDismiss()
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
                        val bId = "${bookId}_${currentChapterId}_${paragraphIndex}"
                        val newBookmark = BookmarkEntity(
                            id = bId,
                            bookId = bookId,
                            chapterId = currentChapterId,
                            paragraphIndex = paragraphIndex,
                            text = paragraphText,
                            note = bookmarkNoteText
                        )
                        viewModel.repository.insertBookmark(newBookmark)
                        onDismiss()
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
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
