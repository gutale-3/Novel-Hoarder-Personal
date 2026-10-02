package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ChapterEntity
import com.example.util.ChapterSubdivisionHelper
import com.example.util.TocSubdivisionMode

@Composable
fun VolumeSplitsDialog(
    bookId: String,
    bookTitle: String,
    chapters: List<ChapterEntity>,
    initialSplits: List<Int>,
    onSaveSplits: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    var splits by remember { mutableStateOf(initialSplits.ifEmpty { listOf(1) }) }
    var newChapterInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Configure Volume Splits",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Specify which chapter starts each volume. You can automatically detect volumes or manually add boundaries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Add split input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newChapterInput,
                        onValueChange = {
                            newChapterInput = it.filter { char -> char.isDigit() }
                            inputError = null
                        },
                        label = { Text("Start Chapter #") },
                        placeholder = { Text("e.g. 41") },
                        singleLine = true,
                        isError = inputError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            val num = newChapterInput.toIntOrNull()
                            if (num == null || num <= 1) {
                                inputError = "Enter a chapter > 1"
                            } else if (splits.contains(num)) {
                                inputError = "Already exists"
                            } else {
                                splits = (splits + num).sorted()
                                newChapterInput = ""
                                inputError = null
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                if (inputError != null) {
                    Text(
                        text = inputError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Auto-detect button
                OutlinedButton(
                    onClick = {
                        val autoGroups = ChapterSubdivisionHelper.subdivideChapters(
                            chapters = chapters,
                            mode = TocSubdivisionMode.VOLUMES,
                            batchSize = 50,
                            customVolumeSplits = emptyList()
                        )
                        val detected = autoGroups.mapNotNull { g ->
                            g.chapters.firstOrNull()?.chapterNumber?.let { if (it > 0) it else null }
                        }.distinct().sorted()
                        splits = if (detected.isNotEmpty()) detected else listOf(1)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-Detect from Titles & Restarts", fontSize = 12.sp)
                }

                HorizontalDivider()

                // List of current volume boundaries
                Text(
                    text = "Current Volumes (${splits.size})",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sorted = splits.sorted()
                    itemsIndexed(sorted) { index, startCh ->
                        val volNum = index + 1
                        val nextStart = if (index + 1 < sorted.size) sorted[index + 1] - 1 else null
                        val rangeText = if (nextStart != null) "Ch. $startCh – $nextStart" else "Ch. $startCh – End"

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$volNum",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Volume $volNum",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = rangeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (startCh != 1) {
                                    IconButton(
                                        onClick = {
                                            splits = splits.filter { it != startCh }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove split",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSplits(splits.filter { it > 0 }.distinct().sorted())
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Volumes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
