package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.TocSubdivisionMode

@Composable
fun SubdivisionModeDialog(
    currentMode: TocSubdivisionMode,
    currentBatchSize: Int,
    onSelectMode: (TocSubdivisionMode, Int) -> Unit,
    onOpenVolumeSplits: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var selectedBatchSize by remember { mutableIntStateOf(currentBatchSize) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.FolderSpecial,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Subdivide Table of Contents",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose how chapters are structured in this novel's table of contents:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Option 1: Leave it as it is
                SubdivisionOptionCard(
                    title = "1. Leave it as it is",
                    description = "Single continuous list from chapter 1 to the end without grouping.",
                    icon = Icons.Default.FormatListNumbered,
                    isSelected = selectedMode == TocSubdivisionMode.AS_IS,
                    onClick = { selectedMode = TocSubdivisionMode.AS_IS }
                )

                // Option 2: Subdivide into chapters
                SubdivisionOptionCard(
                    title = "2. Subdivide into chapters",
                    description = "Divide into batches (e.g. 1 to 50, 51 to 100, ..., 951 to 970) with collapsible drop-downs.",
                    icon = Icons.Default.Layers,
                    isSelected = selectedMode == TocSubdivisionMode.BATCH_CHAPTERS,
                    onClick = { selectedMode = TocSubdivisionMode.BATCH_CHAPTERS }
                )

                if (selectedMode == TocSubdivisionMode.BATCH_CHAPTERS) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Batch Size (Chapters per group):",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(25, 50, 100).forEach { size ->
                                FilterChip(
                                    selected = selectedBatchSize == size,
                                    onClick = { selectedBatchSize = size },
                                    label = { Text("$size chapters", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                // Option 3: Subdivide by chapter volumes
                SubdivisionOptionCard(
                    title = "3. Subdivide by chapter volumes",
                    description = "Divide into story volumes (Volume 1, Volume 2, etc.) with collapsible drop-downs. Handles restarts & volume titles.",
                    icon = Icons.Default.AutoStories,
                    isSelected = selectedMode == TocSubdivisionMode.VOLUMES,
                    onClick = { selectedMode = TocSubdivisionMode.VOLUMES }
                )

                if (selectedMode == TocSubdivisionMode.VOLUMES) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                onSelectMode(selectedMode, selectedBatchSize)
                                onOpenVolumeSplits()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Configure Volume Ranges", fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSelectMode(selectedMode, selectedBatchSize)
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Apply")
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
private fun SubdivisionOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            1.5.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(top = 2.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
