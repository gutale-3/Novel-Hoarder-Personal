package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.util.AppUpdater
import kotlinx.coroutines.launch

/**
 * App update panel. With [alwaysShow] false (Home) it only appears when a newer build was found;
 * with true (Settings) it also offers a manual "Check for update".
 */
@Composable
fun AppUpdateCard(alwaysShow: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available by AppUpdater.available.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var checking by remember { mutableStateOf(false) }

    if (!alwaysShow && available == null) return

    val current = remember { AppUpdater.currentVersionCode(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (available != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (available != null) "Update available: build ${available!!.versionCode}" else "App updates",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Installed: build $current",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            available?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alwaysShow) {
                    OutlinedButton(
                        enabled = !checking && progress == null,
                        onClick = {
                            checking = true
                            status = null
                            scope.launch {
                                status = runCatching { AppUpdater.check(context) }.fold(
                                    onSuccess = { if (it == null) "You're on the newest build." else null },
                                    onFailure = { "Couldn't check: ${it.message}" }
                                )
                                checking = false
                            }
                        }
                    ) { Text(if (checking) "Checking…" else "Check for update") }
                }
                val release = available
                if (release != null) {
                    Button(
                        enabled = progress == null,
                        onClick = {
                            if (!AppUpdater.canInstall(context)) {
                                status = "Allow \"Install unknown apps\" for Inkvault, then tap Update again."
                                AppUpdater.openInstallPermissionSettings(context)
                                return@Button
                            }
                            progress = 0f
                            status = "Downloading…"
                            scope.launch {
                                runCatching { AppUpdater.download(context, release) { progress = it } }
                                    .onSuccess {
                                        status = null
                                        AppUpdater.install(context, it)
                                    }
                                    .onFailure { status = "Download failed: ${it.message}" }
                                progress = null
                            }
                        }
                    ) { Text("Update") }
                }
            }
        }
    }
}
