package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.NovelHoarderApp
import com.example.data.ai.AiProviderRegistry
import com.example.data.ai.ModelManager
import com.example.data.ai.PiperModelManager
import com.example.data.ai.PiperVoice
import com.example.data.ai.PiperVoiceCatalog
import com.example.data.ai.SherpaOnnxTtsEngine
import com.example.data.local.*
import com.example.data.plugin.PluginManager
import com.example.data.repository.NovelRepository
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = NovelHoarderApp.getContainer(application)

    val repository: NovelRepository get() = container.repository
    val settings: SettingsManager get() = container.settings
    val pluginManager: PluginManager get() = container.pluginManager
    val aiRegistry: AiProviderRegistry get() = container.aiRegistry
    val aiModelManager: ModelManager get() = container.aiModelManager
    val piperModelManager: PiperModelManager get() = container.piperModelManager
    val aiFeatures: AiFeaturesManager get() = container.aiFeatures
    val scraping: ScrapingManager get() = container.scraping
    val tts: TtsPlaybackManager get() = container.tts
    val library: LibraryManager get() = container.library
    val progress: ReadingProgressManager get() = container.progress
    val manualCapture: ManualCaptureManager get() = container.manualCapture

    private val prefs = application.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    var focusModeEnabled: Boolean
        get() = tts.focusModeEnabled
        set(value) { tts.focusModeEnabled = value }

    var ttsAutoScrollEnabled: Boolean
        get() = tts.ttsAutoScrollEnabled
        set(value) { tts.ttsAutoScrollEnabled = value }

    var ttsTotalParagraphs: Int
        get() = tts.ttsTotalParagraphs
        set(value) { tts.ttsTotalParagraphs = value }

    var showTtsSpeedDialog by mutableStateOf(false)
    var showTtsPitchDialog by mutableStateOf(false)
    var showTtsEngineDialog by mutableStateOf(false)
    var showTtsVoiceDialog by mutableStateOf(false)

    // Resumable session state
    var hasResumableSession: Boolean
        get() = progress.hasResumableSession
        set(value) { progress.hasResumableSession = value }

    var resumeBookId: String
        get() = progress.resumeBookId
        set(value) { progress.resumeBookId = value }

    var resumeChapterId: String
        get() = progress.resumeChapterId
        set(value) { progress.resumeChapterId = value }

    var resumeParagraph: Int
        get() = progress.resumeParagraph
        set(value) { progress.resumeParagraph = value }

    var resumeBookName: String
        get() = progress.resumeBookName
        set(value) { progress.resumeBookName = value }

    var resumeChapterTitle: String
        get() = progress.resumeChapterTitle
        set(value) { progress.resumeChapterTitle = value }

    var showAiSettings by mutableStateOf(false)

    val currentTheme: AppTheme get() = settings.currentTheme
    val readerFontSize: Int get() = settings.readerFontSize
    val readerFontFamily: String get() = settings.readerFontFamily
    val defaultUserAgent: String get() = settings.defaultUserAgent

    // --- Auto-download / Reading Queue ---
    val autoDownloadNextEnabled: Boolean get() = settings.autoDownloadNextEnabled

    // --- Reader Settings ---
    val readerTheme: String get() = settings.readerTheme
    val readerLineHeight: Float get() = settings.readerLineHeight
    val readerMargin: Int get() = settings.readerMargin
    val readerLetterSpacing: Float get() = settings.readerLetterSpacing
    val readerCustomFontPath: String get() = settings.readerCustomFontPath
    val readerCustomFontName: String get() = settings.readerCustomFontName
    val readerJustificationEnabled: Boolean get() = settings.readerJustificationEnabled
    val readerHyphenationEnabled: Boolean get() = settings.readerHyphenationEnabled
    val readerAmbientSyncEnabled: Boolean get() = settings.readerAmbientSyncEnabled

    // --- Customizable AI Prompts ---
    val glossaryPrompt: String get() = settings.glossaryPrompt
    val polishPrompt: String get() = settings.polishPrompt
    val recapPrompt: String get() = settings.recapPrompt

    // --- Multi-Select Mode for Library ---
    var isLibraryMultiSelectMode by mutableStateOf(false)
    var selectedLibraryBookIds by mutableStateOf<Set<String>>(emptySet())

    // --- Deleted Items (Trash) Flows ---
    val deletedBooks get() = repository.deletedBooks
    val deletedChapters get() = repository.deletedChapters

    // --- Scraper State Delegations ---
    var failedChaptersList: List<MissingChapter>
        get() = scraping.failedChaptersList
        set(value) { scraping.failedChaptersList = value }

    var scrapeUrl: String
        get() = scraping.scrapeUrl
        set(value) { scraping.scrapeUrl = value }

    var scrapeBookName: String
        get() = scraping.scrapeBookName
        set(value) { scraping.scrapeBookName = value }

    var maxChaptersInput: String
        get() = scraping.maxChaptersInput
        set(value) { scraping.maxChaptersInput = value }

    var fromChapterInput: String
        get() = scraping.fromChapterInput
        set(value) { scraping.fromChapterInput = value }

    var toChapterInput: String
        get() = scraping.toChapterInput
        set(value) { scraping.toChapterInput = value }

    var selectedFormat: String
        get() = scraping.selectedFormat
        set(value) { scraping.selectedFormat = value }

    var aggressiveClean: Boolean
        get() = scraping.aggressiveClean
        set(value) { scraping.aggressiveClean = value }

    val scrapeLogs get() = scraping.scrapeLogs

    var isScraping: Boolean
        get() = scraping.isScraping
        set(value) { scraping.isScraping = value }

    var isScrapePaused: Boolean
        get() = scraping.isScrapePaused
        set(value) { scraping.isScrapePaused = value }

    var shouldSkipCurrentChapter: Boolean
        get() = scraping.shouldSkipCurrentChapter
        set(value) { scraping.shouldSkipCurrentChapter = value }

    var isSearchingMissing: Boolean
        get() = scraping.isSearchingMissing
        set(value) { scraping.isSearchingMissing = value }

    var missingChaptersToScrape: List<MissingChapter>
        get() = scraping.missingChaptersToScrape
        set(value) { scraping.missingChaptersToScrape = value }

    var missingChaptersSummary: String
        get() = scraping.missingChaptersSummary
        set(value) { scraping.missingChaptersSummary = value }

    var checkingNewChaptersBookId: String?
        get() = scraping.checkingNewChaptersBookId
        set(value) { scraping.checkingNewChaptersBookId = value }

    var isCheckingNewChapters: Boolean
        get() = scraping.isCheckingNewChapters
        set(value) { scraping.isCheckingNewChapters = value }

    var showNewChaptersDialog: Boolean
        get() = scraping.showNewChaptersDialog
        set(value) { scraping.showNewChaptersDialog = value }

    var newChaptersFoundCount: Int
        get() = scraping.newChaptersFoundCount
        set(value) { scraping.newChaptersFoundCount = value }

    var checkedBookEntity: BookEntity?
        get() = scraping.checkedBookEntity
        set(value) { scraping.checkedBookEntity = value }

    var newChaptersList: List<MissingChapter>
        get() = scraping.newChaptersList
        set(value) { scraping.newChaptersList = value }

    var scrapingStatus: String
        get() = scraping.scrapingStatus
        set(value) { scraping.scrapingStatus = value }

    var currentChapterNum: Int
        get() = scraping.currentChapterNum
        set(value) { scraping.currentChapterNum = value }

    var totalChaptersToScrape: Int
        get() = scraping.totalChaptersToScrape
        set(value) { scraping.totalChaptersToScrape = value }

    var scrapeProgress: Float
        get() = scraping.scrapeProgress
        set(value) { scraping.scrapeProgress = value }

    var showCaptchaDialog: Boolean
        get() = scraping.showCaptchaDialog
        set(value) { scraping.showCaptchaDialog = value }

    var captchaUrl: String
        get() = scraping.captchaUrl
        set(value) { scraping.captchaUrl = value }

    var showManualBrowser: Boolean
        get() = scraping.showManualBrowser
        set(value) { scraping.showManualBrowser = value }

    var manualBrowserUrl: String
        get() = scraping.manualBrowserUrl
        set(value) { scraping.manualBrowserUrl = value }

    var rescrapingChapterId: String?
        get() = scraping.rescrapingChapterId
        set(value) { scraping.rescrapingChapterId = value }

    var isRescrapingBookId: String?
        get() = scraping.isRescrapingBookId
        set(value) { scraping.isRescrapingBookId = value }

    var rescrapeBookProgress: Float
        get() = scraping.rescrapeBookProgress
        set(value) { scraping.rescrapeBookProgress = value }

    init {
        container.library.onClearSelection = {
            isLibraryMultiSelectMode = false
            selectedLibraryBookIds = emptySet()
        }
        container.aiFeatures.aggressiveCleanProvider = { container.scraping.aggressiveClean }
        container.library.scheduleChapterUpdatesCheck()
        container.tts.onSpeakStarted = { isTtsPlayerBarMinimized = false }
    }

    // --- Stats state ---
    val totalBooks = repository.allBooks.map { it.size }
    val totalChapters = flow {
        emit(repository.getTotalChapterCount())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- TTS Minimize State ---
    var isTtsPlayerBarMinimized by mutableStateOf(false)

    // --- Text To Speech (TTS) System ---
    var isTtsReady: Boolean
        get() = tts.isTtsReady
        set(value) { tts.isTtsReady = value }

    var ttsVoices: List<VoiceOption>
        get() = tts.ttsVoices
        set(value) { tts.ttsVoices = value }

    var selectedVoiceId: String
        get() = tts.selectedVoiceId
        set(value) { tts.selectedVoiceId = value }

    var previewingVoiceId: String?
        get() = tts.previewingVoiceId
        set(value) { tts.previewingVoiceId = value }

    // TTS Playback state
    var ttsPlayingBook: BookEntity?
        get() = tts.ttsPlayingBook
        set(value) { tts.ttsPlayingBook = value }

    var ttsPlayingChapter: ChapterEntity?
        get() = tts.ttsPlayingChapter
        set(value) { tts.ttsPlayingChapter = value }

    var ttsIsPlaying: Boolean
        get() = tts.ttsIsPlaying
        set(value) { tts.ttsIsPlaying = value }

    var ttsIsPaused: Boolean
        get() = tts.ttsIsPaused
        set(value) { tts.ttsIsPaused = value }

    var ttsPitch: Float
        get() = tts.ttsPitch
        set(value) { tts.ttsPitch = value }

    var ttsSpeed: Float
        get() = tts.ttsSpeed
        set(value) { tts.ttsSpeed = value }

    var ttsActiveParagraphIndex: Int?
        get() = tts.ttsActiveParagraphIndex
        set(value) { tts.ttsActiveParagraphIndex = value }

    val sherpaOnnxTtsEngine: SherpaOnnxTtsEngine
        get() = tts.sherpaOnnxTtsEngine

    val isPreparingVoice: Boolean
        get() = tts.isPreparingVoice

    val premiumVoiceDownloading: Boolean
        get() = tts.premiumVoiceDownloading

    val premiumVoiceDownloadProgress: Int
        get() = tts.premiumVoiceDownloadProgress

    val premiumVoiceDownloadError: String?
        get() = tts.premiumVoiceDownloadError

    var sleepTimerMinutes: Int
        get() = tts.sleepTimerMinutes
        set(value) { tts.sleepTimerMinutes = value }

    var sleepTimerRemainingSeconds: Int?
        get() = tts.sleepTimerRemainingSeconds
        set(value) { tts.sleepTimerRemainingSeconds = value }

    // --- Glossary AI State ---
    val isGeneratingGlossary: Boolean get() = aiFeatures.isGeneratingGlossary
    val glossaryStatusMessage: String get() = aiFeatures.glossaryStatusMessage

    // --- Translation Polish State ---
    val polishedChaptersLoading get() = aiFeatures.polishedChaptersLoading

    // --- Chapter Recap State ---
    private val _chapterRecapLoading = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val chapterRecapLoading = _chapterRecapLoading.asStateFlow()

    // --- AI Novel Discovery State ---
    val discoveryItems: List<DiscoveryItem> get() = aiFeatures.discoveryItems
    val isDiscovering: Boolean get() = aiFeatures.isDiscovering
    val discoveryError: String get() = aiFeatures.discoveryError

    // --- Chapter Recap ---
    suspend fun getChapterRecap(chapter: ChapterEntity, force: Boolean = false): String? {
        if (!force) {
            val cached = repository.getChapterRecap(chapter.id)
            if (cached != null) return cached.summary
        }

        _chapterRecapLoading.update { it + (chapter.id to true) }
        try {
            val provider = aiRegistry.localProvider
            if (!provider.isAvailable()) {
                return "No on-device model installed. Import one in Settings → On-device AI."
            }

            val basePrompt = settings.recapPrompt.ifBlank {
                "Provide a concise summary ('Previously on...') of the following chapter. Focus on key plot points and character actions in 2-3 sentences. Do not add metadata or conversational padding."
            }

            val prompt = """
                $basePrompt
                
                Chapter Title: ${chapter.title}
                
                Chapter Content:
                ${chapter.content.take(3000)}
            """.trimIndent()

            val response = provider.generate(prompt, jsonMode = false)
            if (!response.startsWith("Error:") && !response.startsWith("No on-device") && !response.startsWith("That model")) {
                val recap = response.trim()
                repository.insertChapterRecap(
                    ChapterRecapEntity(
                        chapterId = chapter.id,
                        bookId = chapter.bookId,
                        summary = recap
                    )
                )
                return recap
            }
            return response
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _chapterRecapLoading.update { it - chapter.id }
        }
        return null
    }

    // --- Ask About Selection ---
    suspend fun askAboutSelection(selection: String, question: String): String {
        try {
            val provider = aiRegistry.localProvider
            if (!provider.isAvailable()) {
                return "No on-device model installed. Import one in Settings → On-device AI."
            }

            val prompt = """
                You are an expert novel reading assistant. A user has selected the following text from a novel: "$selection".
                They have a question about it: "$question".
                Provide a concise, helpful answer explaining the context, translating terms, or clarifying plot details as requested.
            """.trimIndent()
            return provider.generate(prompt, jsonMode = false)
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    // --- Bulk Find & Replace (Non-AI Utility) ---
    suspend fun bulkFindAndReplace(
        bookId: String?,
        findText: String,
        replaceText: String,
        scopeAllBooks: Boolean
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (findText.isEmpty()) return@withContext Pair(0, 0)

        var chaptersModified = 0
        var totalMatchesReplaced = 0

        val targetChapters = if (scopeAllBooks) {
            val books = repository.allBooks.firstOrNull() ?: emptyList()
            books.flatMap { repository.getChapters(it.id) }
        } else {
            if (bookId == null) emptyList() else repository.getChapters(bookId)
        }

        for (chapter in targetChapters) {
            if (chapter.content.contains(findText, ignoreCase = true)) {
                val regex = Regex(Regex.escape(findText), RegexOption.IGNORE_CASE)
                val matches = regex.findAll(chapter.content).count()
                if (matches > 0) {
                    val updatedContent = chapter.content.replace(findText, replaceText, ignoreCase = true)
                    repository.updateChapterContent(chapter.id, updatedContent)
                    chaptersModified++
                    totalMatchesReplaced += matches
                }
            }
        }

        return@withContext Pair(chaptersModified, totalMatchesReplaced)
    }

    override fun onCleared() {
        super.onCleared()
        tts.unregister()
    }
}

data class VoiceOption(
    val id: String,
    val name: String,
    val locale: java.util.Locale
)

data class DiscoveryItem(
    val title: String,
    val description: String,
    val searchUrl: String
)
