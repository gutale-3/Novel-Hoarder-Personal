package com.example.ui.components

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.NovelHoarderApp
import com.example.data.local.BookEntity
import com.example.util.ChapterQualityChecker

/** Scans a novel for bad chapters and re-downloads them in one tap. */
@Composable
fun ChapterQualityDialog(book: BookEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember { NovelHoarderApp.getContainer(context.applicationContext as Application) }
    var report by remember { mutableStateOf<ChapterQualityChecker.Report?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(book.id) {
        report = ChapterQualityChecker.check(container.repository.getChapters(book.id))
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Chapter quality", fontWeight = FontWeight.Bold) },
        text = {
            val r = report
            when {
                r == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                r.isClean -> Text("All ${r.checkedCount} downloaded chapters look good.")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "Checked ${r.checkedCount} chapters: ${r.issues.size} problem(s)" +
                                if (r.missingNumbers.isNotEmpty()) ", ${r.missingNumbers.size} missing number(s)." else ".",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    items(r.issues, key = { it.chapter.id + it.problem }) { issue ->
                        Column {
                            Text(
                                "Ch. ${issue.chapter.chapterNumber} · ${issue.problem.label}",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                issue.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (r.missingNumbers.isNotEmpty()) {
                        item {
                            val shown = r.missingNumbers.take(30).joinToString(", ")
                            Text(
                                "Missing: $shown" + if (r.missingNumbers.size > 30) " …" else "" +
                                    "\nUse “Check for new chapters” / Scrape → search missing to fetch these.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val bad = report?.badChapters.orEmpty()
            if (bad.isNotEmpty()) {
                Button(
                    enabled = !working,
                    onClick = {
                        working = true
                        container.scraping.rescrapeBatchChapters(bad) { ok, msg ->
                            working = false
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            if (ok) onDismiss()
                        }
                    }
                ) { Text(if (working) "Re-downloading…" else "Re-download ${bad.size}") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (report?.badChapters.orEmpty().isNotEmpty() && !working) TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
