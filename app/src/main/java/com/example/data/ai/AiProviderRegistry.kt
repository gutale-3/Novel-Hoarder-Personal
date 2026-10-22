package com.example.data.ai

import android.content.Context

/**
 * Google Gemini (with the user's free API key) is the main AI; an imported on-device model is the
 * fallback when there is no key.
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

    fun getProvider(id: String): AiProvider = if (id == geminiProvider.id) geminiProvider else localProvider

    /** Kept for existing call sites; always the best available provider. */
    suspend fun getActiveProviderForTask(preferredId: String, requiresJson: Boolean): AiProvider = best()
}
