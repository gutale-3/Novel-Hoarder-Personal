package com.example.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "Sync between phones": type the same sync password on each phone and your library, read
 * marks, bookmarks and where you are keep in step automatically, like a watch history.
 */
@Composable
fun PhoneSyncCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = remember { com.example.NovelHoarderApp.getContainer(context.applicationContext as android.app.Application).cloudSync }
    var refresh by remember { mutableIntStateOf(0) }
    val on = remember(refresh) { sync.hasPassword() }
    val last = remember(refresh) { sync.lastSync() }
    val error = remember(refresh) { sync.lastError() }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var serviceUrl by remember { mutableStateOf(sync.serviceUrl()) }

    fun report(r: Result<com.example.data.sync.CloudSync.Summary>) {
        message = r.fold(
            onSuccess = { s ->
                if (s.booksAdded + s.positionsUpdated + s.chaptersMarkedRead == 0) "In sync."
                else "Synced: ${s.booksAdded} novels added, ${s.positionsUpdated} reading positions updated, " +
                    "${s.chaptersMarkedRead} chapters marked read."
            },
            onFailure = { "Couldn't sync: ${it.message ?: "no connection"}" }
        )
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(10.dp))
                Text("Sync between phones", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (on) Text("On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(
                "Your library, chapter lists, read marks, bookmarks and exactly where you are in each novel, " +
                    "kept the same on every phone where you enter the same sync password. Everything is " +
                    "encrypted with your password before it leaves the phone. Chapter text downloads on each " +
                    "phone when you open a chapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (sync.serviceUrl().isBlank() || com.example.data.sync.CloudSync.DEFAULT_SERVICE_URL.isBlank()) {
                OutlinedTextField(
                    value = serviceUrl,
                    onValueChange = { serviceUrl = it; sync.setServiceUrl(it) },
                    label = { Text("Sync service address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!on) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Sync password (at least 8 characters)") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Use the same password on your other phone. Pick one only you know: anyone with it could " +
                        "see your library. It can't be recovered, so keep it somewhere safe.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    enabled = !working && password.trim().length >= 8 && sync.serviceUrl().isNotBlank(),
                    onClick = {
                        working = true
                        message = "Connecting…"
                        scope.launch {
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { sync.setPassword(password) }
                            report(r)
                            if (r.isFailure) sync.signOut() else password = ""
                            working = false
                            refresh++
                        }
                    }
                ) { Text(if (working) "Connecting…" else "Turn on sync") }
            } else {
                Text(
                    when {
                        error != null -> "Last sync failed: $error"
                        last > 0 -> "Last synced ${DateUtils.getRelativeTimeSpanString(last)} · syncs by itself when you open or leave the app and every few minutes while you read"
                        else -> "Not synced yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !working, onClick = {
                        working = true
                        message = "Syncing…"
                        scope.launch {
                            report(kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { sync.syncNow() })
                            working = false
                            refresh++
                        }
                    }) { Text("Sync now") }
                    TextButton(enabled = !working, onClick = { sync.signOut(); message = null; refresh++ }) {
                        Text("Turn off on this phone")
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
