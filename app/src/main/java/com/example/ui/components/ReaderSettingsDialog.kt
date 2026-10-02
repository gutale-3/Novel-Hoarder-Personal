package com.example.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsDialog(
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit,
    onOpenTextRules: () -> Unit,
    onOpenTapZones: () -> Unit
) {
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.settings.importCustomFont(context, it)
        }
    }

    val isDark = isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading Style & Controls", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Navigation / Paging Mode (Continuous Scroll vs Manual Button)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Chapter Navigation Mode",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isContinuous = viewModel.settings.readerPagingMode == "continuous"
                        
                        OutlinedCard(
                            onClick = { viewModel.settings.updateReaderPagingMode("continuous") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (isContinuous) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                            ),
                            border = CardDefaults.outlinedCardBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.UnfoldMore, 
                                    contentDescription = null, 
                                    tint = if (isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Continuous",
                                    fontWeight = if (isContinuous) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = if (isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Seamless scroll",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedCard(
                            onClick = { viewModel.settings.updateReaderPagingMode("manual") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (!isContinuous) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                            ),
                            border = CardDefaults.outlinedCardBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (!isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.TouchApp, 
                                    contentDescription = null, 
                                    tint = if (!isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Manual Next",
                                    fontWeight = if (!isContinuous) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = if (!isContinuous) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tap button",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // TOC Subdivision Mode
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Table of Contents Subdivision",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Structure chapters with collapsible drop-downs in the navigation drawer:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val currentMode = viewModel.settings.defaultTocSubdivisionMode
                        listOf(
                            Triple("as_is", "As Is", Icons.Default.FormatListNumbered),
                            Triple("chapters", "Chapters", Icons.Default.Layers),
                            Triple("volumes", "Volumes", Icons.Default.AutoStories)
                        ).forEach { (code, label, icon) ->
                            val isSel = currentMode == code
                            OutlinedCard(
                                onClick = { viewModel.settings.updateDefaultTocSubdivisionMode(code) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface
                                ),
                                border = CardDefaults.outlinedCardBorder(enabled = true).copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(
                                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = label,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Divider()

                // 2. Background Theme Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Background Theme",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("Auto", "auto", MaterialTheme.colorScheme.background),
                                Triple("Pristine", "light", Color(0xFFFCFBF9)),
                                Triple("Sepia", "sepia", Color(0xFFF4ECD8))
                            ).forEach { (name, themeKey, color) ->
                                val selected = viewModel.readerTheme == themeKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .clickable { viewModel.settings.updateReaderTheme(themeKey) }
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (themeKey == "light" || themeKey == "sepia" || (themeKey == "auto" && !isDark)) Color.Black else Color.White
                                        )
                                    )
                                }
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple("Charcoal", "charcoal", Color(0xFF2C2C2C)),
                                Triple("OLED", "oled", Color(0xFF000000)),
                                Triple("E-Ink", "eink", Color(0xFFFFFFFF))
                            ).forEach { (name, themeKey, color) ->
                                val selected = viewModel.readerTheme == themeKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color)
                                        .clickable { viewModel.settings.updateReaderTheme(themeKey) }
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (themeKey == "eink") Color.Black else Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Font Size Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Font Size: ${viewModel.readerFontSize} sp",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = viewModel.readerFontSize.toFloat(),
                        onValueChange = { viewModel.settings.updateFontSize(it.toInt()) },
                        valueRange = 12f..40f,
                        steps = 28
                    )
                }

                // 4. Line Spacing Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Line Spacing: ${"%.1f".format(viewModel.readerLineHeight)}x",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = viewModel.readerLineHeight,
                        onValueChange = { viewModel.settings.updateReaderLineHeight(it) },
                        valueRange = 1.0f..2.5f
                    )
                }

                // 5. Paragraph Margin Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Paragraph Padding: ${viewModel.readerMargin} dp",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = viewModel.readerMargin.toFloat(),
                        onValueChange = { viewModel.settings.updateReaderMargin(it.toInt()) },
                        valueRange = 8f..48f,
                        steps = 5
                    )
                }

                // 6. Letter Spacing Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Letter Spacing: ${"%.2f".format(viewModel.readerLetterSpacing)} em",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = viewModel.readerLetterSpacing,
                        onValueChange = { viewModel.settings.updateReaderLetterSpacing(it) },
                        valueRange = -0.05f..0.25f
                    )
                }

                // 7. Word Spacing Slider (Typography Pack)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Word Spacing: ${"%.2f".format(viewModel.settings.readerWordSpacing)} em",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Slider(
                        value = viewModel.settings.readerWordSpacing,
                        onValueChange = { viewModel.settings.updateReaderWordSpacing(it) },
                        valueRange = 0f..0.5f
                    )
                }

                // 8. Font Family Selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Font Style",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Pair("Serif", "serif"),
                            Pair("Sans", "sans"),
                            Pair("Mono", "mono")
                        ).forEach { (name, fontKey) ->
                            val selected = viewModel.readerFontFamily == fontKey
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.settings.updateFontFamily(fontKey) },
                                label = { Text(name) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                    
                    if (viewModel.readerCustomFontPath.isNotEmpty()) {
                        val selected = viewModel.readerFontFamily == "custom"
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.settings.updateFontFamily("custom") },
                            label = { Text("Custom: ${viewModel.readerCustomFontName}") },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedButton(
                        onClick = { fontPickerLauncher.launch("*/*") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = "Upload Font")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Upload Custom Font (.ttf/.otf)", fontWeight = FontWeight.Bold)
                    }
                }

                Divider()

                // 9. Ambient Light Sensor Sync
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ambient Light Sync",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Automatically adapt contrast based on room lighting",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.readerAmbientSyncEnabled,
                        onCheckedChange = { viewModel.settings.updateAmbientSyncEnabled(it) }
                    )
                }

                // 10. Text Justification
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Text Justification",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Align text with both left and right margins",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.readerJustificationEnabled,
                        onCheckedChange = { viewModel.settings.updateJustificationEnabled(it) }
                    )
                }

                // 11. Soft Hyphenation Engine
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Soft Hyphenation Engine",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Break long words with soft-hyphens for better spacing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.readerHyphenationEnabled,
                        onCheckedChange = { viewModel.settings.updateHyphenationEnabled(it) }
                    )
                }

                // 12. Focus Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Focus Mode",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Dim non-active paragraphs during TTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.focusModeEnabled,
                        onCheckedChange = { viewModel.tts.toggleFocusMode() }
                    )
                }

                // 13. Bionic Reading Toggle
                if (viewModel.settings.enableBionicReading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bionic Reading",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Bold first fixation letters of words for faster comprehension",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.settings.bionicReadingActiveInReader,
                            onCheckedChange = { viewModel.settings.updateBionicReadingActiveInReader(it) }
                        )
                    }
                }

                // 14. Reading Focus Guide Toggle
                if (viewModel.settings.enableReadingGuide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reading Focus Guide",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Highlight bar to anchor eye tracking on the page",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.settings.enableReadingGuide,
                            onCheckedChange = { viewModel.settings.updateEnableReadingGuide(it) }
                        )
                    }
                }

                Divider()

                // Extra Dim Night Reading Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Extra Dim (Sub-Zero)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Software black filter for pitch-black night reading",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.settings.readerExtraDimEnabled,
                        onCheckedChange = { viewModel.settings.updateReaderExtraDimEnabled(it) }
                    )
                }

                if (viewModel.settings.readerExtraDimEnabled) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Dim Intensity", style = MaterialTheme.typography.labelMedium)
                            Text("${viewModel.settings.readerExtraDimPercent}%", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = viewModel.settings.readerExtraDimPercent.toFloat(),
                            onValueChange = { viewModel.settings.updateReaderExtraDimPercent(it.toInt()) },
                            valueRange = 5f..80f,
                            steps = 14
                        )
                    }
                }

                // Watermark & Aggregator Noise Removal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Strip Watermarks & Noise",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Auto-removes chapter header/footer watermarks & aggregator spam",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.settings.watermarkRemovalEnabled,
                        onCheckedChange = { viewModel.settings.updateWatermarkRemovalEnabled(it) }
                    )
                }

                if (viewModel.settings.watermarkRemovalEnabled) {
                    var showWatermarkFilterDialog by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showWatermarkFilterDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (viewModel.settings.customWatermarkPhrases.isNotBlank()) "Custom Watermarks (Configured)" else "Configure Custom Watermarks",
                            fontSize = 12.sp
                        )
                    }

                    if (showWatermarkFilterDialog) {
                        var tempPhrases by remember { mutableStateOf(viewModel.settings.customWatermarkPhrases) }
                        AlertDialog(
                            onDismissRequest = { showWatermarkFilterDialog = false },
                            title = { Text("Custom Watermark Filters") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Enter phrases, author watermarks, or site URLs to strip (comma or newline separated):",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedTextField(
                                        value = tempPhrases,
                                        onValueChange = { tempPhrases = it },
                                        placeholder = { Text("e.g. read at novelupdates.com, chapter updated by bot") },
                                        modifier = Modifier.fillMaxWidth().height(120.dp),
                                        maxLines = 5
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.settings.updateCustomWatermarkPhrases(tempPhrases)
                                        showWatermarkFilterDialog = false
                                    }
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWatermarkFilterDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }

                // 15. Quick Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (viewModel.settings.enableAutoApplyTextRules) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenTextRules()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FindReplace, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Text Rules", fontSize = 12.sp)
                        }
                    }

                    if (viewModel.settings.enableTapZonesCustomization) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenTapZones()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Tap Zones", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
