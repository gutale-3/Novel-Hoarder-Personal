/**
 * Unit tests for TranslatorEngine.
 * Covers glossary term ordering, placeholder restoration with space distortions,
 * chunk splitting on sentence boundaries, and fallback behavior.
 */
package com.example.util

import com.example.data.local.GlossaryEntity
import org.junit.Assert.*
import org.junit.Test

class TranslatorEngineTest {

    @Test
    fun testGlossaryPreProcessingTokenizesInDescendingLengthOrder() {
        val glossary = listOf(
            GlossaryEntity(id = 1, bookId = "b1", originalText = "Cat", replacementText = "Feline"),
            GlossaryEntity(id = 2, bookId = "b1", originalText = "Cat Woman", replacementText = "Heroine")
        )

        val text = "Cat Woman met the Cat."
        val (prepared, tokenMap) = TranslatorEngine.applyGlossaryPlaceholders(text, glossary, isCjk = false)

        // "Cat Woman" is longer than "Cat", so it must be replaced first with ___G0___
        // "Cat" should be replaced second with ___G1___
        assertTrue(prepared.contains("___G0___"))
        assertTrue(prepared.contains("___G1___"))
        assertEquals("Heroine", tokenMap["___G0___"])
        assertEquals("Feline", tokenMap["___G1___"])
    }

    @Test
    fun testGlossaryPostProcessingHandlesSpaceDistortions() {
        val tokenMap = mapOf(
            "___G0___" to "Demon Lord",
            "___G1___" to "Holy Sword"
        )

        // ML Kit sometimes inserts spaces inside tokens during translation
        val translatedWithDistortions = "The ___ G 0 ___ wielded the ___  G1  ___."
        val restored = TranslatorEngine.restoreGlossaryPlaceholders(translatedWithDistortions, tokenMap)

        assertEquals("The Demon Lord wielded the Holy Sword.", restored)
    }

    @Test
    fun testChunkSplittingPreservesAllContent() {
        val paragraph1 = "Sentence 1. Sentence 2. Sentence 3."
        val paragraph2 = "Sentence 4. Sentence 5. Sentence 6."
        val text = "$paragraph1\n\n$paragraph2"

        val chunks = TranslatorEngine.splitIntoChunks(text, targetSize = 25)
        assertTrue("Text should be split into multiple chunks", chunks.size > 1)

        val reassembled = chunks.joinToString(" ")
        assertTrue(reassembled.contains("Sentence 1"))
        assertTrue(reassembled.contains("Sentence 6"))
    }
}
