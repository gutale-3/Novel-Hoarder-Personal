package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.BookEntity
import com.example.viewmodel.MainViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToScrape: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val books by viewModel.repository.allBooks.collectAsState(emptyList())
    val totalBooksCount by viewModel.totalBooks.collectAsState(0)
    val totalChaptersCount by viewModel.totalChapters.collectAsState(0)
    val bookStatsMap by viewModel.allBookStatsMap.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val recent = remember(books, bookStatsMap) { books.sortedByDescending { LibraryPrefs.lastReadAt(context, it.id) } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + com.example.ui.components.LocalPlayerOverlayPadding.current)
    ) {
        item {
            com.example.ui.components.InterruptedDownloadCard(viewModel.scraping, onResumed = onNavigateToScrape)
        }

        item {
            com.example.ui.components.CrashReportCard()
        }

        // Reading streak at a glance (details in Reading Stats)
        if (viewModel.settings.enableReadingStats &&
            (viewModel.stats.currentStreakDays > 0 || viewModel.stats.todayReadingSeconds >= 60)
        ) {
            item {
                val streak = viewModel.stats.currentStreakDays
                val todayMin = (viewModel.stats.todayReadingSeconds / 60).toInt()
                Text(
                    buildString {
                        if (streak > 0) append("🔥 $streak-day streak")
                        if (streak > 0 && todayMin > 0) append("  ·  ")
                        if (todayMin > 0) append("$todayMin min today")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item {
            com.example.ui.components.AppUpdateCard(alwaysShow = false)
        }

        // Continue reading: the novel you were last in, one tap away.
        recent.firstOrNull()?.takeIf { LibraryPrefs.lastReadAt(context, it.id) > 0 }?.let { last ->
            item {
                val progress = com.example.util.ReadingProgress.of(last, bookStatsMap[last.id])
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenBook(last.id) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val cover: Any? = last.coverLocalPath?.takeIf { it.isNotEmpty() && File(it).exists() }?.let { File(it) }
                            ?: last.coverUrl?.takeIf { it.isNotEmpty() }
                        if (cover != null) {
                            AsyncImage(
                                model = cover, contentDescription = null,
                                modifier = Modifier.size(width = 60.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Continue reading", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                            Text(
                                last.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                progress.label, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                            )
                        }
                        FilledIconButton(onClick = { onOpenBook(last.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Continue reading ${last.title}")
                        }
                    }
                }
            }
        }

        // Library at a glance
        item {
            Text(
                "$totalBooksCount novels · $totalChaptersCount chapters saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Quick Actions Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNavigateToScrape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("home_to_scrape_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = "New Scrape"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Scrape", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onNavigateToLibrary,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("home_to_library_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryBooks,
                        contentDescription = "Library"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Library", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Recent Novels Header
        item {
            Text(
                text = "Recent Novels",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // List of Recent Books
        if (books.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Empty download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No saved novels found.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = "Paste any novel's URL to download it, or import an EPUB you already have.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(recent.take(5), key = { it.id }) { book ->
                RecentBookRow(
                    book = book,
                    stats = bookStatsMap[book.id],
                    onClick = { onOpenBook(book.id) }
                )
            }
        }
    }
}

@Composable
fun RecentBookRow(
    book: BookEntity,
    stats: com.example.data.local.BookStats?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember(book, stats) { com.example.util.ReadingProgress.of(book, stats) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recent_book_${book.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elegant Cover Image with text fallback
            val hasLocalCover = !book.coverLocalPath.isNullOrEmpty() && File(book.coverLocalPath).exists()
            if (hasLocalCover) {
                AsyncImage(
                    model = File(book.coverLocalPath!!),
                    contentDescription = "${book.title} Cover",
                    modifier = Modifier
                        .size(width = 42.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else if (!book.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = "${book.title} Cover",
                    modifier = Modifier
                        .size(width = 42.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = book.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Black
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = progress.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.caughtUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            }

            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Read",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
