package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.viewmodel.ChapterNumbering
import com.example.viewmodel.ChapterStructure.Numbering
import com.example.viewmodel.MainViewModel

/** "Fix chapter order & numbering" for one novel: pick the numbering, and line up with the site. */
@Composable
fun ChapterStructureDialog(bookId: String, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var numbering by remember { mutableStateOf(ChapterNumbering.get(context, bookId)) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Chapter order & numbering") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Show chapter numbers as", fontWeight = FontWeight.SemiBold)
                listOf(
                    Numbering.NOVEL to ("The novel's own numbers" to "As written in the titles, e.g. \"Chapter 79: The Duel\""),
                    Numbering.SITE to ("The site's list" to "Every entry counts, author notes too, e.g. \"85. The Duel\"")
                ).forEach { (value, text) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = numbering == value, onClick = {
                            numbering = value
                            ChapterNumbering.set(context, bookId, value)
                        }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = numbering == value, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(text.first)
                            Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    "Check with the site: puts your chapters in the site's order, numbers them 1, 2, 3… with no " +
                        "repeats or gaps, and adds the chapters you don't have yet (titles only; nothing is " +
                        "downloaded). Pages the site doesn't list stay where they are.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Reading the site's chapter list…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            Button(enabled = !working, onClick = {
                working = true
                result = null
                viewModel.scraping.fixChapterStructure(bookId) { _, msg ->
                    working = false
                    result = msg
                }
            }) { Text("Check with the site") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Done") } }
    )
}
