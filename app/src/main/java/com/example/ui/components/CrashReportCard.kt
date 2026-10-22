package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.util.CrashReports

/** Shown on Home after a crash until the report is sent or dismissed. */
@Composable
fun CrashReportCard() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(CrashReports.hasUnsent(context)) }
    if (!visible) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Inkvault closed unexpectedly", fontWeight = FontWeight.Bold)
                    Text(
                        "A crash report was saved. Sending it helps get the problem fixed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    CrashReports.latest(context)?.let { CrashReports.send(context, it) }
                    visible = false
                }) { Text("Send report") }
                TextButton(onClick = { CrashReports.markHandled(context); visible = false }) { Text("Dismiss") }
            }
        }
    }
}
