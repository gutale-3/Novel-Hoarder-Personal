package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun RsvpSpeedReaderModal(
    chapterTitle: String,
    rawText: String,
    initialWordIndex: Int = 0,
    viewModel: MainViewModel,
    onDismiss: (lastWordIndex: Int) -> Unit
) {
    val words = remember(rawText) {
        rawText.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    if (words.isEmpty()) {
        AlertDialog(
            onDismissRequest = { onDismiss(0) },
            title = { Text("No Content") },
            text = { Text("This chapter does not contain any readable text for RSVP speed reading.") },
            confirmButton = { TextButton(onClick = { onDismiss(0) }) { Text("OK") } }
        )
        return
    }

    var currentIndex by remember { mutableIntStateOf(initialWordIndex.coerceIn(0, words.size - 1)) }
    var isPlaying by remember { mutableStateOf(false) }
    var wpm by remember { mutableIntStateOf(viewModel.settings.rsvpWpm) }

    // RSVP Playback Loop
    LaunchedEffect(isPlaying, currentIndex, wpm) {
        if (isPlaying && currentIndex < words.size - 1) {
            val currentWord = words[currentIndex]
            var delayMs = (60_000L / wpm.coerceAtLeast(100))

            // Add subtle pause for sentence endings
            if (currentWord.endsWith(".") || currentWord.endsWith("!") || currentWord.endsWith("?")) {
                delayMs = (delayMs * 1.5).toLong()
            } else if (currentWord.endsWith(",") || currentWord.endsWith(";") || currentWord.endsWith(":")) {
                delayMs = (delayMs * 1.25).toLong()
            }

            delay(delayMs)
            currentIndex++
        } else if (isPlaying && currentIndex >= words.size - 1) {
            isPlaying = false
        }
    }

    Dialog(
        onDismissRequest = {
            viewModel.settings.updateRsvpWpm(wpm)
            onDismiss(currentIndex)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "RSVP Speed Reader",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.settings.updateRsvpWpm(wpm)
                            onDismiss(currentIndex)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close RSVP",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Center ORP Speed Reading Focus Box
                val currentWord = words[currentIndex]
                val orpIndex = when {
                    currentWord.length <= 1 -> 0
                    currentWord.length <= 5 -> 1
                    currentWord.length <= 9 -> 2
                    currentWord.length <= 13 -> 3
                    else -> 4
                }.coerceIn(0, (currentWord.length - 1).coerceAtLeast(0))

                val beforeOrp = currentWord.substring(0, orpIndex)
                val orpChar = if (currentWord.isNotEmpty()) currentWord[orpIndex].toString() else ""
                val afterOrp = if (currentWord.length > orpIndex + 1) currentWord.substring(orpIndex + 1) else ""

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Focus Notch
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Fixed Focal Point Word Display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left part of word (Right aligned to center line)
                        Text(
                            text = beforeOrp,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 38.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // ORP Center Highlighted Character
                        Text(
                            text = orpChar,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 40.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black
                            ),
                            color = Color(0xFFE53935) // Focal Red
                        )

                        // Right part of word (Left aligned from center line)
                        Text(
                            text = afterOrp,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 38.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // Bottom Focus Notch
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Word ${currentIndex + 1} of ${words.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Controls and Speed Selector
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Progress Slider
                    Slider(
                        value = currentIndex.toFloat(),
                        onValueChange = {
                            isPlaying = false
                            currentIndex = it.toInt().coerceIn(0, words.size - 1)
                        },
                        valueRange = 0f..(words.size - 1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // WPM Speed Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { wpm = (wpm - 25).coerceAtLeast(100) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("-25 WPM")
                        }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "$wpm WPM",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        FilledTonalButton(
                            onClick = { wpm = (wpm + 25).coerceAtMost(900) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+25 WPM")
                        }
                    }

                    // Main Action Buttons (Rewind, Play/Pause, Forward, Restart)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                currentIndex = (currentIndex - 25).coerceAtLeast(0)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Back 25 words",
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        FloatingActionButton(
                            onClick = { isPlaying = !isPlaying },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                currentIndex = (currentIndex + 25).coerceAtMost(words.size - 1)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 25 words",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
