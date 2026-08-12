package com.example.data.ai

import android.content.Context

/**
 * All AI runs on this device. There is no cloud provider and no API key — the only backend is a
 * local model file the user imports themselves.
 */
class AiProviderRegistry(context: Context) {

    val localProvider = MediaPipeLocalProvider(context)

    val providers: List<AiProvider> = listOf(localProvider)

    fun getProvider(id: String): AiProvider = localProvider

    /**
     * Kept for source compatibility with existing call sites. There is nothing to select between,
     * so the parameters are ignored.
     */
    suspend fun getActiveProviderForTask(preferredId: String, requiresJson: Boolean): AiProvider =
        localProvider
}
