package com.example.data.ai

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mediapipe.tasks.genai.llminference.LlmInference

class MediaPipeLocalProvider(private val context: Context) : AiProvider {
    override val id: String = "mediapipe_local"
    override val displayName: String = "On-device AI"

    companion object {
        private var instanceRef: MediaPipeLocalProvider? = null

        fun reset() {
            instanceRef?.resetInternal()
        }
    }

    init {
        instanceRef = this
    }

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "gemma.task")
        modelFile.exists() && modelFile.length() >= 50L * 1024 * 1024
    }

    @Synchronized
    private fun getOrInitLlmInference(modelPath: String): LlmInference {
        if (llmInference == null || loadedModelPath != modelPath) {
            llmInference?.close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(4096)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            loadedModelPath = modelPath
        }
        return llmInference!!
    }

    override suspend fun generate(prompt: String, jsonMode: Boolean): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "No on-device model installed. Open Settings → On-device AI and import a model file (.task) from your device to enable AI features."
        }

        try {
            val modelFile = File(context.filesDir, "gemma.task")
            val inference = try {
                getOrInitLlmInference(modelFile.absolutePath)
            } catch (t: Throwable) {
                return@withContext "That model file could not be loaded. It may be the wrong format — MediaPipe needs a .task file built for LLM Inference. (${t.message})"
            }

            val finalPrompt = if (jsonMode) {
                "$prompt\nIMPORTANT: Return ONLY a valid JSON array containing objects with 'original' and 'replacement' properties. Do not include markdown code blocks, backticks, or other conversation filler."
            } else {
                prompt
            }

            val response = inference.generateResponse(finalPrompt)
            response ?: "Error: Empty response from on-device model"
        } catch (t: Throwable) {
            "That model file could not be loaded. It may be the wrong format — MediaPipe needs a .task file built for LLM Inference. (${t.message})"
        }
    }

    @Synchronized
    fun resetInternal() {
        llmInference?.close()
        llmInference = null
        loadedModelPath = null
    }
}
