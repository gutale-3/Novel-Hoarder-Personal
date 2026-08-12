package com.example.data.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class ModelStatus {
    object Idle : ModelStatus()
    data class Progress(val percentage: Int) : ModelStatus()
    object Success : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

/**
 * Manages the on-device language model file.
 *
 * There is deliberately no download. The model is large, licence-gated, and the app is offline by
 * design — the user picks a .task or .bin file they already have and it is copied into app storage.
 */
class ModelManager(private val context: Context) {

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Idle)
    val status: StateFlow<ModelStatus> = _status

    private val modelFile: File get() = File(context.filesDir, "gemma.task")
    private val tempFile: File get() = File(context.filesDir, "gemma.task.part")

    /** Minimum plausible size for a real model. Anything smaller is a stub or a wrong file. */
    private val minimumValidBytes = 50L * 1024 * 1024

    fun checkModelExists(): Boolean = modelFile.exists() && modelFile.length() >= minimumValidBytes

    fun modelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    fun deleteModel(): Boolean {
        if (tempFile.exists()) tempFile.delete()
        val deleted = if (modelFile.exists()) modelFile.delete() else true
        _status.value = ModelStatus.Idle
        MediaPipeLocalProvider.reset()
        return deleted
    }

    suspend fun importModel(uri: Uri) = withContext(Dispatchers.IO) {
        _status.value = ModelStatus.Progress(0)
        if (tempFile.exists()) tempFile.delete()

        try {
            val contentResolver = context.contentResolver
            var totalBytes = -1L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    totalBytes = cursor.getLong(sizeIndex)
                }
            }

            if (totalBytes <= 0) {
                totalBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }

            val usableSpace = context.filesDir.usableSpace
            if (totalBytes > 0 && usableSpace < totalBytes) {
                val neededMb = totalBytes / (1024 * 1024)
                val freeMb = usableSpace / (1024 * 1024)
                _status.value = ModelStatus.Error("Not enough storage space. Model needs ~${neededMb} MB, but only ${freeMb} MB is available.")
                return@withContext
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                _status.value = ModelStatus.Error("Cannot open selected file. Please choose a valid .task or .bin model file from local storage.")
                return@withContext
            }

            val buffer = ByteArray(64 * 1024) // 64 KB chunks
            var bytesRead: Int
            var totalBytesCopied = 0L

            FileOutputStream(tempFile).use { outputStream ->
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesCopied += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((totalBytesCopied * 100) / totalBytes).toInt().coerceIn(0, 99)
                            _status.value = ModelStatus.Progress(progress)
                        }
                    }
                }
            }

            val finalLength = tempFile.length()
            if (finalLength < minimumValidBytes) {
                val sizeMb = finalLength / (1024 * 1024)
                tempFile.delete()
                _status.value = ModelStatus.Error("That file is only ${sizeMb} MB — it does not look like a model file. Please import a valid MediaPipe .task LLM model.")
                return@withContext
            }

            if (modelFile.exists()) modelFile.delete()
            val renamed = tempFile.renameTo(modelFile)
            if (!renamed) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            MediaPipeLocalProvider.reset()
            _status.value = ModelStatus.Success
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            _status.value = ModelStatus.Error("Failed to import model file: ${e.message ?: "Unknown error"}. Please make sure the file is accessible.")
        }
    }
}
