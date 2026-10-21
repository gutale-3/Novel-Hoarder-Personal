package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.BookEntity

/** Pick which library novel a manually saved chapter belongs to. */
@Composable
fun NovelPickerDialog(
    books: List<BookEntity>,
    onPick: (BookEntity) -> Unit,
    onNewNovel: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(books, query) {
        val q = query.trim().lowercase()
        books.filter { q.isEmpty() || it.title.lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save chapter to…", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search your library") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${book.totalChapters} chapters", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { onPick(book) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewNovel) { Text("New novel from this page") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
