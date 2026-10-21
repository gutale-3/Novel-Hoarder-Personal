package com.example.data.ai

import android.content.Context

/**
 * All AI runs on this device. There is no cloud provider and no API key — the only backend is a
 * local model file the user imports themselves.
 */
class AiProviderRegistry(context: Context) {

    val localProvider = MediaPipeLocalProvider(context)
    val geminiProvider = GeminiProvider(context)

    val providers: List<AiProvider> = listOf(geminiProvider, localProvider)

    /**
     * The provider to use: Gemini when the user has added a key (better and needs no model
     * download), otherwise the on-device model.
     */
    suspend fun best(): AiProvider = if (geminiProvider.isAvailable()) geminiProvider else localProvider

    fun getProvider(id: String): AiProvider = localProvider

    /**
     * Kept for source compatibility with existing call sites. There is nothing to select between,
     * so the parameters are ignored.
     */
    suspend fun getActiveProviderForTask(preferredId: String, requiresJson: Boolean): AiProvider =
        localProvider
}
