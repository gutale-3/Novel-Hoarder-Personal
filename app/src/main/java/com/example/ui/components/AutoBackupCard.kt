package com.example.ui.components

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import com.example.background.AutoBackupWorker

/** Settings card for nightly backups and device sync through a user-chosen folder (e.g. Google Drive). */
@Composable
fun AutoBackupCard() {
    val context = LocalContext.current
    val prefs = remember { AutoBackupWorker.prefs(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val enabled = remember(refresh) { prefs.getBoolean(AutoBackupWorker.KEY_ENABLED, false) }
    val folder = remember(refresh) { AutoBackupWorker.folderLabel(context) }
    val lastOk = remember(refresh) { prefs.getLong(AutoBackupWorker.KEY_LAST_OK, 0L) }
    val lastMsg = remember(refresh) { prefs.getString(AutoBackupWorker.KEY_LAST_MSG, null) }
    val lastError = remember(refresh) { prefs.getString(AutoBackupWorker.KEY_LAST_ERROR, null) }

    val nowInfo by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("auto_backup_now")
    }.collectAsState(initial = emptyList())
    val running = nowInfo.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(running) { if (!running) refresh++ }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            AutoBackupWorker.setFolder(context, uri)
            AutoBackupWorker.setEnabled(context, true)
            refresh++
        }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Automatic daily backup", fontWeight = FontWeight.Bold)
                    Text(
                        folder?.let { "Folder: $it · keeps last ${AutoBackupWorker.KEEP}" }
                            ?: "Pick a folder — choose Google Drive in the picker to keep backups off the phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled && folder != null,
                    onCheckedChange = { on ->
                        if (on && folder == null) picker.launch(null)
                        else { AutoBackupWorker.setEnabled(context, on); refresh++ }
                    }
                )
            }
            when {
                running -> Text("Backing up…", style = MaterialTheme.typography.bodySmall)
                lastError != null -> Text("Last backup failed: $lastError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                lastOk > 0 -> Text(
                    "Last backup ${DateUtils.getRelativeTimeSpanString(lastOk)}" + (lastMsg?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) { Text(if (folder == null) "Choose folder" else "Change folder") }
                Button(enabled = folder != null && !running, onClick = { AutoBackupWorker.backupNow(context) }) { Text("Back up now") }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            DeviceSyncSection(folderChosen = folder != null, onChooseFolder = { picker.launch(null) })
        }
    }
}

/**
 * Sync between devices through the same folder: install the app on each device, choose the
 * same Google Drive folder, and turn this on.
 */
@Composable
private fun DeviceSyncSection(folderChosen: Boolean, onChooseFolder: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = remember {
        com.example.NovelHoarderApp.getContainer(context.applicationContext as android.app.Application).cloudSync
    }
    var refresh by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val enabled = remember(refresh, folderChosen) { sync.isEnabled }
    val last = remember(refresh) { sync.lastSync() }
    val error = remember(refresh) { sync.lastError() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sync across devices", fontWeight = FontWeight.Bold)
            Text(
                "Library, reading position, read marks and bookmarks. Use the same Drive folder on every " +
                    "device; chapters download on each device when opened.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on && !folderChosen) { onChooseFolder(); return@Switch }
                sync.setEnabled(on)
                refresh++
            }
        )
    }
    when {
        syncing -> Text("Syncing…", style = MaterialTheme.typography.bodySmall)
        result != null -> Text(result!!, style = MaterialTheme.typography.bodySmall)
        error != null -> Text("Last sync failed: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        last > 0 -> Text("Last synced ${DateUtils.getRelativeTimeSpanString(last)}", style = MaterialTheme.typography.bodySmall)
    }
    if (enabled) {
        OutlinedButton(
            enabled = !syncing,
            onClick = {
                syncing = true
                result = null
                scope.launch {
                    result = sync.syncNow().fold(
                        onSuccess = {
                            "Synced: ${it.booksAdded} novels added, ${it.positionsUpdated} positions updated, " +
                                "${it.chaptersMarkedRead} chapters marked read"
                        },
                        onFailure = { "Sync failed: ${it.message}" }
                    )
                    syncing = false
                    refresh++
                }
            }
        ) { Text("Sync now") }
    }
}
