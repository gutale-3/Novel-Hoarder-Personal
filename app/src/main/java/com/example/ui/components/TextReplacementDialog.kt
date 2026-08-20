package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TextReplacementRuleEntity
import com.example.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReplacementDialog(
    bookId: String?,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val textRules = viewModel.textRules

    val rules by if (bookId != null) {
        textRules.getRulesForBookFlow(bookId).collectAsState(initial = emptyList())
    } else {
        textRules.getAllRulesFlow().collectAsState(initial = emptyList())
    }

    var showAddRuleForm by remember { mutableStateOf(false) }
    var patternInput by remember { mutableStateOf("") }
    var replacementInput by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var isCaseSensitive by remember { mutableStateOf(false) }
    var isGlobalRule by remember { mutableStateOf(bookId == null) }
    var sampleTestText by remember { mutableStateOf("Example: Lin Fan entered the Martial Dao Pavilion.") }
    var isApplyingBatch by remember { mutableStateOf(false) }

    val (previewText, regexError) = remember(patternInput, replacementInput, sampleTestText, isRegex, isCaseSensitive) {
        textRules.testRulePreview(patternInput, replacementInput, sampleTestText, isRegex, isCaseSensitive)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FindReplace,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (bookId != null) "Text Replacement Rules" else "Global Text Rules",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!showAddRuleForm) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Rules (${rules.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        FilledTonalButton(
                            onClick = { showAddRuleForm = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Rule")
                        }
                    }

                    if (rules.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Rule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No custom rules defined yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Add rules to fix machine translation names, punctuation, or ads",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rules, key = { it.id }) { rule ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = rule.pattern,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.ArrowForward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (rule.replacement.isEmpty()) "(Remove)" else rule.replacement,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (rule.isRegex) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                                    ) {
                                                        Text(
                                                            text = "Regex",
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                                        )
                                                    }
                                                }
                                                if (rule.isCaseSensitive) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Text(
                                                            text = "Match Case",
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                                        )
                                                    }
                                                }
                                                if (rule.bookId == null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer
                                                    ) {
                                                        Text(
                                                            text = "Global",
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(
                                                checked = rule.isEnabled,
                                                onCheckedChange = { checked ->
                                                    textRules.updateRule(rule.copy(isEnabled = checked))
                                                }
                                            )
                                            IconButton(
                                                onClick = { textRules.deleteRule(rule) }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete Rule",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (bookId != null && rules.isNotEmpty()) {
                        Button(
                            onClick = {
                                isApplyingBatch = true
                                textRules.batchApplyRulesToAllChapters(bookId) { modified, _ ->
                                    isApplyingBatch = false
                                    Toast.makeText(context, "Updated $modified chapters with text rules", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isApplyingBatch,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isApplyingBatch) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Applying Rules...")
                            } else {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Batch Apply to All Chapters")
                            }
                        }
                    }
                } else {
                    // Add Rule Form
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = patternInput,
                            onValueChange = { patternInput = it },
                            label = { Text("Find Pattern / Target Text") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = replacementInput,
                            onValueChange = { replacementInput = it },
                            label = { Text("Replace With") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isRegex,
                                    onCheckedChange = { isRegex = it }
                                )
                                Text("Regex", style = MaterialTheme.typography.bodyMedium)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isCaseSensitive,
                                    onCheckedChange = { isCaseSensitive = it }
                                )
                                Text("Match Case", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        if (bookId != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isGlobalRule,
                                    onCheckedChange = { isGlobalRule = it }
                                )
                                Text("Apply to all novels (Global)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        // Live Preview Test Box
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Live Test Preview:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                if (regexError != null) {
                                    Text(
                                        text = "Regex Error: $regexError",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(
                                        text = previewText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showAddRuleForm = false }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (patternInput.isNotBlank()) {
                                        val targetBookId = if (isGlobalRule) null else bookId
                                        textRules.addRule(
                                            bookId = targetBookId,
                                            pattern = patternInput,
                                            replacement = replacementInput,
                                            isRegex = isRegex,
                                            isCaseSensitive = isCaseSensitive
                                        ) {
                                            patternInput = ""
                                            replacementInput = ""
                                            showAddRuleForm = false
                                            Toast.makeText(context, "Rule saved successfully", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = patternInput.isNotBlank() && regexError == null
                            ) {
                                Text("Save Rule")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!showAddRuleForm) {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    )
}
