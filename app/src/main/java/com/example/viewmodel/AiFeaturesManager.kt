package com.example.viewmodel

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.ai.AiProviderRegistry
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.GlossaryEntity
import com.example.data.local.LocalNovelCatalog
import com.example.data.local.PolishedChapterEntity
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.util.TomatoScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class AiFeaturesManager(
    private val repository: NovelRepository,
    private val aiRegistry: AiProviderRegistry,
    private val settings: SettingsManager
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var aggressiveCleanProvider: () -> Boolean = { false }

    // --- Glossary AI State ---
    var isGeneratingGlossary by mutableStateOf(false)
        internal set
    var glossaryStatusMessage by mutableStateOf("")
        internal set

    // --- Translation Polish State ---
    private val _polishedChaptersLoading = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val polishedChaptersLoading = _polishedChaptersLoading.asStateFlow()

    // --- AI Novel Discovery State ---
    var discoveryItems by mutableStateOf<List<DiscoveryItem>>(emptyList())
        internal set
    var isDiscovering by mutableStateOf(false)
        internal set
    var discoveryError by mutableStateOf("")
        internal set

    private fun extractJsonSubstring(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        var cleaned = trimmed
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter("```json").substringAfter("```").substringBeforeLast("```").trim()
        }

        val firstBracket = cleaned.indexOf('[')
        val lastBracket = cleaned.lastIndexOf(']')
        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            return cleaned.substring(firstBracket, lastBracket + 1)
        }

        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return cleaned.substring(firstBrace, lastBrace + 1)
        }

        return null
    }

    // --- AI Glossary Generator ---
    fun generateGlossaryWithAi(book: BookEntity) {
        coroutineScope.launch {
            isGeneratingGlossary = true
            glossaryStatusMessage = "Analyzing book content..."
            try {
                val provider = aiRegistry.localProvider
                if (!provider.isAvailable()) {
                    glossaryStatusMessage = "No on-device model installed. Import one in Settings → On-device AI."
                    isGeneratingGlossary = false
                    return@launch
                }

                val chapters = repository.getChapters(book.id)
                if (chapters.isEmpty()) {
                    glossaryStatusMessage = "No chapters found for this book."
                    isGeneratingGlossary = false
                    return@launch
                }

                // Sample first 3 chapters, max 4000 characters for on-device 2B model
                val sampleText = chapters.take(3).joinToString("\n\n") { it.content }.take(4000)
                glossaryStatusMessage = "Running on-device AI..."

                val basePrompt = settings.glossaryPrompt.ifBlank {
                    "Analyze the following novel content and identify character names, locations, and unique terms that are poorly machine-translated or require a consistent translation glossary."
                }

                val prompt = """
                    $basePrompt
                    Return a JSON array containing objects with 'original' and 'replacement' properties.
                    Only return a JSON array: [{"original": "...", "replacement": "..."}].
                    
                    Novel Content:
                    $sampleText
                """.trimIndent()

                val response = provider.generate(prompt, jsonMode = true)
                if (response.startsWith("Error:") || response.startsWith("No on-device") || response.startsWith("That model")) {
                    glossaryStatusMessage = response
                    isGeneratingGlossary = false
                    return@launch
                }

                val jsonStr = extractJsonSubstring(response)
                if (jsonStr == null) {
                    glossaryStatusMessage = "The model did not return usable results — try again, or shorten the sample."
                    isGeneratingGlossary = false
                    return@launch
                }

                glossaryStatusMessage = "Parsing terms..."
                val jsonArray = org.json.JSONArray(jsonStr)
                val existing = repository.getGlossary(book.id)
                var count = 0
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val original = obj.optString("original", "").trim()
                    val replacement = obj.optString("replacement", "").trim()

                    if (original.isNotEmpty() && replacement.isNotEmpty()) {
                        val alreadyExists = existing.any { it.originalText.equals(original, ignoreCase = true) }
                        if (!alreadyExists) {
                            repository.insertGlossary(
                                GlossaryEntity(
                                    bookId = book.id,
                                    originalText = original,
                                    replacementText = replacement
                                )
                            )
                            count++
                        }
                    }
                }
                glossaryStatusMessage = "Success! Added $count new terms to your glossary."
            } catch (e: Exception) {
                glossaryStatusMessage = "Error: ${e.message}"
                e.printStackTrace()
            } finally {
                isGeneratingGlossary = false
            }
        }
    }

    // --- Translation Polish ---
    fun polishChapter(chapter: ChapterEntity) {
        coroutineScope.launch {
            val provider = aiRegistry.localProvider
            if (!provider.isAvailable()) {
                return@launch
            }

            _polishedChaptersLoading.update { it + (chapter.id to true) }
            try {
                // Split chapter into ~1500 character sections on paragraph boundaries to prevent model truncation
                val paragraphs = chapter.content.split("\n\n").filter { it.isNotBlank() }
                val chunks = mutableListOf<String>()
                val currentChunk = StringBuilder()

                for (para in paragraphs) {
                    if (currentChunk.length + para.length > 1500 && currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.toString().trim())
                        currentChunk.clear()
                    }
                    if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                    currentChunk.append(para)
                }
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                }

                val basePrompt = settings.polishPrompt.ifBlank {
                    "Rewrite this machine-translated chapter to be in fluent, literary, highly readable English. Preserve the exact original plot, character actions, and meaning. Do not add any commentary or prefix/suffix notes. Only return the polished story text."
                }

                val polishedChunks = mutableListOf<String>()
                var failed = false

                for (chunk in chunks.ifEmpty { listOf(chapter.content) }) {
                    val prompt = """
                        $basePrompt
                        
                        Text to polish:
                        $chunk
                    """.trimIndent()

                    val response = provider.generate(prompt, jsonMode = false)
                    if (response.startsWith("Error:") || response.startsWith("No on-device") || response.startsWith("That model")) {
                        failed = true
                        break
                    }
                    polishedChunks.add(response.trim())
                }

                if (!failed && polishedChunks.isNotEmpty()) {
                    val fullPolished = polishedChunks.joinToString("\n\n")
                    repository.insertPolishedChapter(
                        PolishedChapterEntity(
                            chapterId = chapter.id,
                            bookId = chapter.bookId,
                            content = fullPolished
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _polishedChaptersLoading.update { it - chapter.id }
            }
        }
    }

    fun getPolishedChapterFlow(chapterId: String): Flow<PolishedChapterEntity?> {
        return repository.getPolishedChapterFlow(chapterId)
    }

    // --- AI Novel Discovery ---
    fun discoverNovels(topicsAndGenres: String) {
        coroutineScope.launch {
            isDiscovering = true
            discoveryError = ""
            discoveryItems = emptyList()
            try {
                val catalog = LocalNovelCatalog.all()
                val provider = aiRegistry.localProvider

                // Offline fallback when no model is installed
                if (!provider.isAvailable()) {
                    val keywords = topicsAndGenres.lowercase()
                        .split(Regex("[\\s,\\.]+"))
                        .filter { it.length > 2 }

                    val scored = catalog.map { item ->
                        val text = "${item.title} ${item.description}".lowercase()
                        val matchCount = keywords.count { kw -> text.contains(kw) }
                        item to matchCount
                    }.sortedByDescending { it.second }

                    val topMatches = scored.take(5).map { it.first }
                    discoveryItems = topMatches
                    if (topMatches.isEmpty()) {
                        discoveryError = "No matching novels found."
                    }
                    isDiscovering = false
                    return@launch
                }

                val candidateCatalog = catalog.take(20).mapIndexed { idx, item ->
                    "${idx + 1}. Title: ${item.title}\nDescription/Tropes: ${item.description}"
                }.joinToString("\n\n")

                val prompt = """
                    You are a web novel recommendation assistant.
                    Based on these preferences: "$topicsAndGenres", choose 3 to 5 matching web novels from the catalog below.
                    
                    Catalog:
                    $candidateCatalog
                    
                    Return ONLY a JSON array of objects with 'title' and 'description' keys: [{"title": "...", "description": "..."}].
                """.trimIndent()

                val response = provider.generate(prompt, jsonMode = true)
                val jsonStr = extractJsonSubstring(response)

                val items = mutableListOf<DiscoveryItem>()
                if (jsonStr != null) {
                    try {
                        val jsonArray = org.json.JSONArray(jsonStr)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val title = obj.optString("title", "").trim()
                            val description = obj.optString("description", "").trim()
                            if (title.isNotEmpty()) {
                                val searchUrl = "https://tomatomtl.com/#/search?search=${URLEncoder.encode(title, "UTF-8")}"
                                items.add(DiscoveryItem(title, description, searchUrl))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (items.isEmpty()) {
                    // Fallback to keyword matching
                    val keywords = topicsAndGenres.lowercase().split(Regex("[\\s,\\.]+")).filter { it.length > 2 }
                    val matched = catalog.filter { candidate ->
                        keywords.any { keyword ->
                            candidate.title.lowercase().contains(keyword) ||
                            candidate.description.lowercase().contains(keyword)
                        }
                    }.take(5)

                    val fallbackList = matched.ifEmpty { catalog.shuffled().take(4) }
                    fallbackList.forEach { candidate ->
                        items.add(
                            DiscoveryItem(
                                candidate.title,
                                candidate.description,
                                "https://tomatomtl.com/#/search?search=${URLEncoder.encode(candidate.title, "UTF-8")}"
                            )
                        )
                    }
                }

                discoveryItems = items
            } catch (e: Exception) {
                discoveryError = "Error: ${e.message}"
                e.printStackTrace()
            } finally {
                isDiscovering = false
            }
        }
    }

    // --- Auto-download Next Chapters ---
    fun triggerAutoDownloadNextChapters(book: BookEntity, currentChapter: ChapterEntity) {
        if (!settings.autoDownloadNextEnabled) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (book.url.startsWith("local://")) return@launch
                val scraper = SourceManager.getSourceForUrl(book.url)
                val webView = withContext(Dispatchers.Main) {
                    WebView(settings.application.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        @Suppress("DEPRECATION")
                        settings.databaseEnabled = true
                    }
                }

                val chapterUrls = scraper.scrapeChapterList(webView, book.url)
                if (chapterUrls.isEmpty()) return@launch

                val currentIdx = chapterUrls.indexOfFirst { it == currentChapter.url }
                if (currentIdx == -1) return@launch

                val nextUrls = chapterUrls.drop(currentIdx + 1).take(3)
                val glossaries = repository.getGlossary(book.id)

                for ((offset, nextUrl) in nextUrls.withIndex()) {
                    val absoluteChapterNum = currentChapter.chapterNumber + 1 + offset
                    val chapId = scraper.parseChapterId(nextUrl) ?: "ch_$absoluteChapterNum"
                    val fullChapId = "${book.id}_$chapId"

                    val existing = repository.getChapter(fullChapId)
                    if (existing != null && existing.content.length > 100) {
                        continue
                    }

                    try {
                        val rawContent = scraper.scrapeChapterContent(webView, nextUrl) { false }
                        val title = rawContent.first
                        var cleanedBody = TomatoScraper.sanitizeText(rawContent.second, aggressiveCleanProvider())

                        if (glossaries.isNotEmpty()) {
                            cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                        }

                        val md5 = java.security.MessageDigest.getInstance("MD5")
                        val hash = md5.digest(cleanedBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                        val chapterEntity = ChapterEntity(
                            id = fullChapId,
                            bookId = book.id,
                            chapterId = chapId,
                            chapterNumber = absoluteChapterNum,
                            title = title,
                            url = nextUrl,
                            content = cleanedBody,
                            hash = hash
                        )
                        repository.insertChapter(chapterEntity)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
