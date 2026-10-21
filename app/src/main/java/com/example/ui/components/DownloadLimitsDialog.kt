package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.plugin.PluginManager
import com.example.data.scraper.DownloadQuota
import com.example.data.scraper.SourceKeys

/**
 * Per-site download rules: whether new chapters auto-download by default, and the hourly/daily
 * caps the site enforces. Individual novels can still be switched on or off from their menu.
 */
@Composable
fun DownloadLimitsDialog(pluginManager: PluginManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val quota = remember { DownloadQuota.get(context) }
    val version by quota.version.collectAsState()
    val plugins by pluginManager.plugins.collectAsState()

    val sources = remember(plugins) {
        listOf(SourceKeys.TOMATO to "TomatoMTL") +
            plugins.filter { it.isEnabled }.sortedBy { it.name.lowercase() }.map { it.id to it.name }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Download limits", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Auto-download fetches new chapters in the background. Limits are enforced for every " +
                            "download; when one is reached the download waits instead of failing. 0 = unlimited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(sources, key = { it.first }) { (key, name) ->
                    // Re-read on every change so the usage line and fields stay current.
                    val limits = remember(version, key) { quota.limitsFor(key) }
                    val usage = remember(version, key) { quota.usage(key) }
                    SourceLimitRow(
                        name = name,
                        limits = limits,
                        usage = usage,
                        onChange = { quota.setLimits(key, it) }
                    )
                }
            }
        }
    )
}

@Composable
private fun SourceLimitRow(
    name: String,
    limits: DownloadQuota.Limits,
    usage: DownloadQuota.Usage,
    onChange: (DownloadQuota.Limits) -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(
                        "Used: ${usage.lastHour} this hour · ${usage.lastDay} in 24h",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Auto", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Switch(checked = limits.autoDownload, onCheckedChange = { onChange(limits.copy(autoDownload = it)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Per hour", limits.perHour, Modifier.weight(1f)) { onChange(limits.copy(perHour = it)) }
                NumberField("Per day", limits.perDay, Modifier.weight(1f)) { onChange(limits.copy(perDay = it)) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(5)
            text = digits
            onValue(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        placeholder = { Text("∞") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
