package com.example.ui.components

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    visible: Boolean,
    bookState: BookEntity?,
    activeChapter: ChapterEntity?,
    viewModel: MainViewModel,
    context: Context,
    barBgColor: Color,
    barContentColor: Color,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRsvp: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenTextRules: () -> Unit,
    onOpenTapZones: () -> Unit,
    onOpenSourceMigration: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isCompact = configuration.screenWidthDp < 600
        var showMenu by remember { mutableStateOf(false) }

        TopAppBar(
            title = {
                Text(
                    text = bookState?.title ?: "Offline Reader",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // Audio / TTS Narration (Always visible)
                IconButton(onClick = {
                    val activeCh = activeChapter
                    val bk = bookState
                    if (activeCh != null && bk != null) {
                        if (viewModel.ttsIsPlaying && viewModel.ttsPlayingChapter?.id == activeCh.id) {
                            viewModel.tts.pauseTts()
                        } else if (viewModel.ttsIsPaused && viewModel.ttsPlayingChapter?.id == activeCh.id) {
                            viewModel.tts.resumeTts()
                        } else {
                            viewModel.tts.speak(activeCh.content, bk, activeCh)
                        }
                    }
                }) {
                    val icon = if (viewModel.ttsIsPlaying && viewModel.ttsPlayingChapter?.id == activeChapter?.id) {
                        Icons.Default.VolumeOff
                    } else {
                        Icons.Default.RecordVoiceOver
                    }
                    Icon(imageVector = icon, contentDescription = "Listen to Chapter")
                }

                // Open TOC drawer (Always visible)
                IconButton(onClick = onOpenDrawer) {
                    Icon(imageVector = Icons.Default.FormatListNumbered, contentDescription = "Chapter Index")
                }

                if (!isCompact) {
                    // Text style settings (Visible on larger screens)
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.TextFormat, contentDescription = "Font settings")
                    }

                    if (viewModel.settings.enableRsvpSpeedReading) {
                        IconButton(onClick = onOpenRsvp) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = "RSVP Speed Reader")
                        }
                    }

                    if (viewModel.settings.enableBionicReading) {
                        IconButton(onClick = {
                            viewModel.settings.updateBionicReadingActiveInReader(!viewModel.settings.bionicReadingActiveInReader)
                        }) {
                            Icon(
                                imageVector = Icons.Default.FormatBold,
                                contentDescription = "Toggle Bionic Reading",
                                tint = if (viewModel.settings.bionicReadingActiveInReader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (viewModel.settings.enableReadingStats) {
                        IconButton(onClick = onOpenStats) {
                            Icon(imageVector = Icons.Default.Insights, contentDescription = "Reading Stats")
                        }
                    }

                    // Rescrape Current Chapter (Visible on larger screens)
                    IconButton(onClick = {
                        val activeCh = activeChapter
                        if (activeCh != null) {
                            viewModel.scraping.rescrapeSingleChapter(activeCh) { _, msg ->
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        if (viewModel.rescrapingChapterId == activeChapter?.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescrape Chapter")
                        }
                    }
                }

                // Dropdown/Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Font & Reader Settings") },
                            leadingIcon = { Icon(Icons.Default.TextFormat, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                showMenu = false
                                onOpenSettings()
                            }
                        )

                        if (viewModel.settings.enableRsvpSpeedReading) {
                            DropdownMenuItem(
                                text = { Text("RSVP Speed Reader") },
                                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showMenu = false
                                    onOpenRsvp()
                                }
                            )
                        }

                        if (viewModel.settings.enableBionicReading) {
                            DropdownMenuItem(
                                text = { Text(if (viewModel.settings.bionicReadingActiveInReader) "Disable Bionic Reading" else "Enable Bionic Reading") },
                                leadingIcon = { Icon(Icons.Default.FormatBold, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.settings.updateBionicReadingActiveInReader(!viewModel.settings.bionicReadingActiveInReader)
                                }
                            )
                        }

                        if (viewModel.settings.enableReadingStats) {
                            DropdownMenuItem(
                                text = { Text("Reading Stats & Insights") },
                                leadingIcon = { Icon(Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showMenu = false
                                    onOpenStats()
                                }
                            )
                        }

                        if (viewModel.settings.enableAutoApplyTextRules) {
                            DropdownMenuItem(
                                text = { Text("Text Replacement Rules") },
                                leadingIcon = { Icon(Icons.Default.FindReplace, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showMenu = false
                                    onOpenTextRules()
                                }
                            )
                        }

                        if (viewModel.settings.enableTapZonesCustomization) {
                            DropdownMenuItem(
                                text = { Text("Customize Tap Zones") },
                                leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showMenu = false
                                    onOpenTapZones()
                                }
                            )
                        }

                        if (viewModel.settings.enableSourceMigration && bookState != null) {
                            DropdownMenuItem(
                                text = { Text("Switch Novel Source") },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    showMenu = false
                                    onOpenSourceMigration()
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = {
                                if (viewModel.rescrapingChapterId == activeChapter?.id) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Rescraping...")
                                    }
                                } else {
                                    Text("Rescrape Chapter")
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            enabled = viewModel.rescrapingChapterId != activeChapter?.id,
                            onClick = {
                                showMenu = false
                                val activeCh = activeChapter
                                if (activeCh != null) {
                                    viewModel.scraping.rescrapeSingleChapter(activeCh) { _, msg ->
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barBgColor,
                titleContentColor = barContentColor,
                navigationIconContentColor = barContentColor,
                actionIconContentColor = barContentColor
            )
        )
    }
}
