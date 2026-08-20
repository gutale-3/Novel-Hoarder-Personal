package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapZonesConfigDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val settings = viewModel.settings

    val actionOptions = listOf(
        "PREV_PAGE" to "Previous Page",
        "NEXT_PAGE" to "Next Page",
        "TOGGLE_CONTROLS" to "Toggle Menu",
        "QUICK_BOOKMARK" to "Quick Bookmark",
        "TOGGLE_RSVP" to "RSVP Speed Reader",
        "TOGGLE_AUTOSCROLL" to "Auto-Scroll",
        "TOGGLE_BIONIC" to "Bionic Reading",
        "NONE" to "None (Ignore)"
    )

    var selectedZoneKey by remember { mutableStateOf<String?>(null) }
    var selectedZoneTitle by remember { mutableStateOf("") }

    val zoneActions = mapOf(
        "tl" to ("Top-Left" to settings.tapZoneTopLeftAction),
        "tc" to ("Top-Center" to settings.tapZoneTopCenterAction),
        "tr" to ("Top-Right" to settings.tapZoneTopRightAction),
        "ml" to ("Mid-Left" to settings.tapZoneMidLeftAction),
        "mc" to ("Center" to settings.tapZoneMidCenterAction),
        "mr" to ("Mid-Right" to settings.tapZoneMidRightAction),
        "bl" to ("Bottom-Left" to settings.tapZoneBottomLeftAction),
        "bc" to ("Bottom-Center" to settings.tapZoneBottomCenterAction),
        "br" to ("Bottom-Right" to settings.tapZoneBottomRightAction)
    )

    fun getActionLabel(key: String): String {
        return actionOptions.find { it.first == key }?.second ?: key
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Custom Reader Tap Zones",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Tap on any grid area below to assign what action occurs when tapping that part of the reader screen:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 3x3 Grid Visualizer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val rows = listOf(
                        listOf("tl", "tc", "tr"),
                        listOf("ml", "mc", "mr"),
                        listOf("bl", "bc", "br")
                    )

                    rows.forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowKeys.forEach { key ->
                                val (title, action) = zoneActions[key] ?: ("" to "")
                                val isCenter = key == "mc"

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(72.dp)
                                        .clickable {
                                            selectedZoneKey = key
                                            selectedZoneTitle = title
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCenter) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.surface
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selectedZoneKey == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = getActionLabel(action),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            ),
                                            textAlign = TextAlign.Center,
                                            color = if (action == "TOGGLE_CONTROLS") MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Zone Action Selector Submenu
                if (selectedZoneKey != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Select Action for $selectedZoneTitle:",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                actionOptions.forEach { (actionKey, label) ->
                                    val currentAction = zoneActions[selectedZoneKey]?.second
                                    val isSelected = currentAction == actionKey

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                selectedZoneKey?.let { key ->
                                                    settings.updateTapZoneAction(key, actionKey)
                                                }
                                                selectedZoneKey = null
                                            }
                                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
