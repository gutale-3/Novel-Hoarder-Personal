package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.ModelStatus
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val modelStatus by viewModel.modelManager.status.collectAsState()
    var modelExists by remember { mutableStateOf(viewModel.modelManager.checkModelExists()) }
    var modelSizeBytes by remember { mutableStateOf(viewModel.modelManager.modelSizeBytes()) }

    fun refreshState() {
        modelExists = viewModel.modelManager.checkModelExists()
        modelSizeBytes = viewModel.modelManager.modelSizeBytes()
    }

    // File picker launcher for importing Gemma task files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.modelManager.importModel(uri)
                refreshState()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "On-device AI",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "On-device AI Settings",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Local Model Status & Import
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "MediaPipe LLM Model",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (modelExists) {
                            val sizeMb = modelSizeBytes / (1024 * 1024)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Installed",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "gemma.task",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "$sizeMb MB • Ready for offline AI",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                viewModel.modelManager.deleteModel()
                                                refreshState()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete")
                                    }
                                }
                            }
                        } else {
                            when (val status = modelStatus) {
                                is ModelStatus.Progress -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Importing model: ${status.percentage}%",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        }
                                        LinearProgressIndicator(
                                            progress = status.percentage / 100f,
                                            modifier = Modifier.fillMaxWidth(),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                is ModelStatus.Error -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = status.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { filePickerLauncher.launch("*/*") },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Try Again")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.modelManager.deleteModel()
                                                    refreshState()
                                                },
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("Clear")
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                text = "No model installed.",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Import a MediaPipe LLM .task file (Gemma, Phi, Llama, or similar) to enable AI glossary generation, prose polishing, and novel recommendations completely offline.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Button(
                                                onClick = { filePickerLauncher.launch("*/*") },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("import_model_button"),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(vertical = 12.dp)
                                            ) {
                                                Icon(Icons.Default.UploadFile, contentDescription = "Import", modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Import .task Model File", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // ML Kit On-Device Translation Section
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "ML Kit On-Device Translation",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Download offline translation models for fast, private translation of foreign language chapters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var requireWifi by remember { mutableStateOf(viewModel.settings.translationRequireWifi) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Require Wi-Fi for Downloads", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = requireWifi,
                                onCheckedChange = {
                                    requireWifi = it
                                    viewModel.settings.updateTranslationRequireWifi(it)
                                }
                            )
                        }

                        var zhDownloaded by remember { mutableStateOf(false) }
                        var jaDownloaded by remember { mutableStateOf(false) }
                        var koDownloaded by remember { mutableStateOf(false) }
                        var isDownloadingLang by remember { mutableStateOf<String?>(null) }

                        LaunchedEffect(Unit) {
                            zhDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE)
                            jaDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                            koDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.KOREAN)
                        }

                        listOf(
                            Triple("Chinese -> English", com.google.mlkit.nl.translate.TranslateLanguage.CHINESE, zhDownloaded),
                            Triple("Japanese -> English", com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE, jaDownloaded),
                            Triple("Korean -> English", com.google.mlkit.nl.translate.TranslateLanguage.KOREAN, koDownloaded)
                        ).forEach { (label, langCode, isDownloaded) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            if (isDownloaded) "Status: Downloaded (~30MB)" else "Status: Not Downloaded",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isDownloadingLang == langCode) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else if (isDownloaded) {
                                        OutlinedButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    try {
                                                        val model = com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(langCode).build()
                                                        com.google.mlkit.common.model.RemoteModelManager.getInstance().deleteDownloadedModel(model)
                                                        zhDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE)
                                                        jaDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                                                        koDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.KOREAN)
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Remove")
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    isDownloadingLang = langCode
                                                    com.example.util.TranslatorEngine.ensureModelDownloaded(
                                                        sourceLang = langCode,
                                                        targetLang = com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH,
                                                        allowMobileData = !requireWifi
                                                    )
                                                    zhDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.CHINESE)
                                                    jaDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                                                    koDownloaded = com.example.util.TranslatorEngine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.KOREAN)
                                                    isDownloadingLang = null
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Download")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Customizable Prompts Section
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Customize Prompts (Optional)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Adjust the system instructions provided to the local model when generating glossaries or polishing chapter prose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        var showPromptSettings by remember { mutableStateOf(false) }

                        OutlinedButton(
                            onClick = { showPromptSettings = !showPromptSettings },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (showPromptSettings) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Prompts"
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (showPromptSettings) "Hide Custom Prompts" else "Show & Edit Custom Prompts")
                        }

                        if (showPromptSettings) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Glossary Prompt
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Glossary Generator Prompt", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    OutlinedTextField(
                                        value = viewModel.glossaryPrompt,
                                        onValueChange = { viewModel.settings.updateGlossaryPrompt(it) },
                                        minLines = 3,
                                        maxLines = 5,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }

                                // Polish Prompt
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Prose Polish Prompt", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    OutlinedTextField(
                                        value = viewModel.polishPrompt,
                                        onValueChange = { viewModel.settings.updatePolishPrompt(it) },
                                        minLines = 3,
                                        maxLines = 5,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Offline Voice Info
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Offline Voice Narration (Piper TTS)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Realistic offline TTS voice models are managed separately from the Reader screen audio player controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Done Button
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
