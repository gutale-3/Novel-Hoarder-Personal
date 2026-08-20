package com.example.viewmodel

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.util.TomatoScraper
import com.example.util.CloudflareException
import com.example.util.NovelCompiler
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import com.example.service.ScrapeService

data class MissingChapter(
    val url: String,
    val chapterNumber: Int
)

/**
 * Applies the cleaner that belongs to the source the text came from.
 * TomatoScraper's cleaner truncates on TomatoMTL-specific page furniture, so it must
 * never run on text scraped from another site.
 */
internal fun cleanScrapedBody(
    scraper: com.example.data.scraper.NovelSource,
    raw: String,
    aggressive: Boolean
): String = if (scraper === com.example.util.TomatoScraper) {
    com.example.util.TomatoScraper.sanitizeText(raw, aggressive)
} else {
    com.example.util.GenericScraper.sanitizeText(raw, aggressive)
}

class ScrapingManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val settings: SettingsManager,
    private val coroutineScope: CoroutineScope,
    val pluginManager: com.example.data.plugin.PluginManager? = null,
    val manualCapture: ManualCaptureManager? = null
) {
    /**
     * Normalises a book URL to its canonical form for the site it came from.
     * Only TomatoMTL needs rewriting; every other source is left exactly as the user typed it.
     */
    private fun canonicalBookUrl(rawUrl: String, scraper: com.example.data.scraper.NovelSource): String {
        if (scraper !== com.example.util.TomatoScraper) return rawUrl
        val idx = rawUrl.indexOf("/book/")
        if (idx < 0) return rawUrl
        val segments = rawUrl.substring(idx).split("/").filter { it.isNotEmpty() }
        return if (segments.size >= 2) "https://tomatomtl.com/book/${segments[1]}" else rawUrl
    }

    /**
     * A plugin with its own TOC script may page through a site API and legitimately needs
     * minutes, not seconds. The plugin engine's own internal budget is 120 s; this must be
     * larger than that or it cancels the work it is supposed to be waiting for.
     */
    private fun tocTimeoutMs(scraper: com.example.data.scraper.NovelSource): Long {
        val engine = scraper as? com.example.data.plugin.PluginExecutionEngine
        return if (engine != null && !engine.config.customTocJs.isNullOrBlank()) 150_000L
        else 60_000L
    }

    // --- Scraper State ---
    var scrapeUrl by mutableStateOf("")
    var scrapeBookName by mutableStateOf("")
    var maxChaptersInput by mutableStateOf("")
    var fromChapterInput by mutableStateOf("")
    var toChapterInput by mutableStateOf("")
    var selectedFormat by mutableStateOf("Both") // EPUB, PDF, Both, Database only
    var aggressiveClean by mutableStateOf(false)
    var failedChaptersList by mutableStateOf<List<MissingChapter>>(emptyList())

    // Live terminal log output (just like the desktop version!)
    private val _scrapeLogs = MutableStateFlow<List<String>>(emptyList())
    val scrapeLogs = _scrapeLogs.asStateFlow()

    var isScraping by mutableStateOf(false)
        internal set
    var isScrapePaused by mutableStateOf(false)
        internal set
    var shouldSkipCurrentChapter by mutableStateOf(false)
        internal set

    // Missing chapters variables
    var isSearchingMissing by mutableStateOf(false)
        internal set
    var missingChaptersToScrape by mutableStateOf<List<MissingChapter>>(emptyList())
        internal set
    var missingChaptersSummary by mutableStateOf("")
        internal set

    // --- Check for New Chapters State ---
    var checkingNewChaptersBookId by mutableStateOf<String?>(null)
    var isCheckingNewChapters by mutableStateOf(false)
    var showNewChaptersDialog by mutableStateOf(false)
    var newChaptersFoundCount by mutableStateOf(0)
    var checkedBookEntity by mutableStateOf<BookEntity?>(null)
    var newChaptersList by mutableStateOf<List<MissingChapter>>(emptyList())

    var scrapingStatus by mutableStateOf("● Idle")
        internal set
    var currentChapterNum by mutableStateOf(0)
        internal set
    var totalChaptersToScrape by mutableStateOf(0)
        internal set
    var scrapeProgress by mutableStateOf(0f)
        internal set

    // CAPTCHA verification variables
    var showCaptchaDialog by mutableStateOf(false)
    var captchaUrl by mutableStateOf("")
    private var captchaContinuation: CancellableContinuation<Unit>? = null

    init {
        com.example.data.plugin.PluginExecutionEngine.progressListener = { count ->
            scrapingStatus = "● Fetching TOC (Found $count chapters...)"
            addLog("TOC live progress: Found $count chapters...")
            notifyProgressMade()
        }
    }

    // Manual Interactive Browser variables
    var showManualBrowser by mutableStateOf(false)
    var manualBrowserUrl by mutableStateOf(settings.lastBrowserUrl.ifBlank { "https://www.google.com" })

    fun grabInfoFromWebView(webView: WebView, onResult: (String) -> Unit) {
        manualCapture?.grabInfoFromCurrentPage(webView, onResult) ?: onResult("Manual capture unavailable")
    }

    fun grabChapterFromWebView(webView: WebView, onResult: (String) -> Unit) {
        manualCapture?.grabChapterFromCurrentPage(webView, onResult) ?: onResult("Manual capture unavailable")
    }

    fun resolveBookUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        val lower = rawUrl.lowercase()
        if (lower.contains("/chapter-") || lower.contains("/chapter/") || lower.contains("/ch-") || lower.endsWith(".html")) {
            val lastSlash = rawUrl.lastIndexOf('/')
            if (lastSlash > 8) {
                return rawUrl.substring(0, lastSlash)
            }
        }
        return rawUrl
    }

    fun saveBrowserUrl(url: String) {
        manualBrowserUrl = url
        settings.updateLastBrowserUrl(url)
    }

    // --- Single Chapter and Novel Rescraping Logic ---
    var rescrapingChapterId by mutableStateOf<String?>(null)
    var isRescrapingBookId by mutableStateOf<String?>(null)
    var rescrapeBookProgress by mutableStateOf(0f)

    private val defaultUserAgent: String get() = settings.defaultUserAgent
    private var scrapeJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastProgressTimestamp: Long = 0L

    fun notifyProgressMade() {
        lastProgressTimestamp = System.currentTimeMillis()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        lastProgressTimestamp = System.currentTimeMillis()
        watchdogJob = coroutineScope.launch(Dispatchers.IO) {
            while (isScraping) {
                delay(5000L)
                if (isScraping && !isScrapePaused && !showCaptchaDialog && !showManualBrowser) {
                    val elapsed = System.currentTimeMillis() - lastProgressTimestamp
                    if (elapsed >= 180_000L) {
                        addLog("[WATCHDOG] Scraper progress stalled in limbo for ${elapsed / 1000}s (>180s)! Triggering automatic stop-clear-restart cycle...")
                        lastProgressTimestamp = System.currentTimeMillis()
                        
                        withContext(Dispatchers.Main) {
                            stopScraping()
                        }
                        delay(2000L)
                        
                        withContext(Dispatchers.Main) {
                            clearLogs()
                            addLog("[WATCHDOG] Auto-restarting scraper session from last saved state...")
                            continueScraping()
                        }
                        break
                    }
                }
            }
        }
    }

    fun pauseScraping() {
        if (isScraping && !isScrapePaused) {
            isScrapePaused = true
            addLog("Scraping session paused by user.")
        }
    }

    fun resumeScraping() {
        if (isScraping && isScrapePaused) {
            isScrapePaused = false
            notifyProgressMade()
            addLog("Scraping session resumed.")
        }
    }

    fun skipCurrentChapter() {
        if (isScraping) {
            shouldSkipCurrentChapter = true
            notifyProgressMade()
            addLog("Skip requested for current chapter.")
        }
    }

    fun launchInteractiveBrowser() {
        val lastUrl = settings.lastBrowserUrl.trim()
        manualBrowserUrl = if (lastUrl.isNotEmpty() && (lastUrl.startsWith("http://") || lastUrl.startsWith("https://"))) {
            lastUrl
        } else {
            val url = scrapeUrl.trim()
            if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                url
            } else {
                "https://www.google.com"
            }
        }
        showManualBrowser = true
        addLog("Launching Interactive Browser to solve Cloudflare / Log in: $manualBrowserUrl")
    }

    fun addLog(msg: String) {
        notifyProgressMade()
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _scrapeLogs.update { current ->
            val updated = current + "[$time] $msg"
            if (updated.size > 1000) updated.drop(100) else updated
        }
    }

    fun clearLogs() {
        _scrapeLogs.value = emptyList()
    }

    fun startScraping() {
        if (isScraping) return
        val url = scrapeUrl.trim()
        val bookName = scrapeBookName.trim()

        if (url.isEmpty()) {
            addLog("ERROR: Please enter a Novel or Chapter URL!")
            return
        }

        isScraping = true
        isScrapePaused = false
        shouldSkipCurrentChapter = false
        clearLogs()
        notifyProgressMade()
        startWatchdog()
        if (bookName.isNotEmpty()) {
            addLog("Initiating Scrape Session for: $bookName")
        } else {
            addLog("Initiating Scrape Session (Novel Name will be auto-scraped from URL)")
        }
        scrapingStatus = "● Starting..."
        ScrapeService.start(
            application.applicationContext,
            if (bookName.isNotEmpty()) bookName else "Novel",
            0
        )

        scrapeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                runScraperLoop(url, bookName)
            } catch (e: CancellationException) {
                scrapingStatus = "● Cancelled"
                addLog("Scraping session cancelled by user.")
            } catch (e: Exception) {
                scrapingStatus = "● Error: ${e.message}"
                addLog("CRITICAL ERROR: ${e.message}")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isScraping = false
                    isScrapePaused = false
                    shouldSkipCurrentChapter = false
                    scrapeProgress = 0f
                    watchdogJob?.cancel()
                    ScrapeService.stop(application.applicationContext)
                }
            }
        }
    }

    fun stopScraping() {
        addLog("Stopping scrape session...")
        scrapingStatus = "● Stopping..."
        isScrapePaused = false
        shouldSkipCurrentChapter = false
        watchdogJob?.cancel()
        scrapeJob?.cancel()
        captchaContinuation?.cancel()
        isScraping = false
        ScrapeService.stop(application.applicationContext)
    }

    fun searchMissingChapters() {
        if (isSearchingMissing || isScraping) return
        val url = scrapeUrl.trim()
        if (url.isEmpty()) {
            addLog("ERROR: Please enter a Novel or Chapter URL!")
            return
        }
        isSearchingMissing = true
        missingChaptersToScrape = emptyList()
        missingChaptersSummary = ""
        clearLogs()
        addLog("Starting search for missing chapters...")
        
        coroutineScope.launch(Dispatchers.IO) {
            val scraper = SourceManager.getSourceForUrl(url, pluginManager)
            addLog("Using source: ${scraper.sourceName}")
            val bookId = scraper.parseBookId(url) ?: "novel_${System.currentTimeMillis()}"
            val bookUrl = canonicalBookUrl(url, scraper)

            addLog("Initializing WebView to fetch Table of Contents...")
            var webView = createFreshWebView()

            try {
                // Fetch chapter list (TOC)
                var chapterUrls = emptyList<String>()
                var tries = 0
                while (chapterUrls.isEmpty() && tries < 3) {
                    try {
                        chapterUrls = withTimeoutOrNull(tocTimeoutMs(scraper)) {
                            scraper.scrapeChapterList(webView, bookUrl)
                        } ?: emptyList()

                        if (chapterUrls.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                webView.loadUrl(bookUrl)
                            }
                            delay(5000)
                            chapterUrls = withTimeoutOrNull(tocTimeoutMs(scraper)) {
                                scraper.scrapeChapterList(webView, bookUrl)
                            } ?: emptyList()
                        }
                    } catch (e: CloudflareException) {
                        addLog("Cloudflare detected. Attempting to bypass...")
                        handleCaptchaChallenge(bookUrl)
                    } catch (e: Exception) {
                        tries++
                        addLog("TOC fetch retry $tries/3: ${e.message}")
                        addLog("Auto-recovering: Refreshing background web session...")
                        withContext(Dispatchers.Main) {
                            try { webView.stopLoading() } catch (ignored: Exception) {}
                            try { webView.destroy() } catch (ignored: Exception) {}
                        }
                        webView = createFreshWebView()
                        delay(2000)
                    }
                }

                if (chapterUrls.isEmpty()) {
                    addLog("ERROR: Could not fetch Table of Contents.")
                    withContext(Dispatchers.Main) {
                        isSearchingMissing = false
                        missingChaptersSummary = "Could not load Table of Contents."
                    }
                    return@launch
                }

                addLog("TOC loaded: ${chapterUrls.size} chapters found.")
                
                // Fetch local chapters
                val localChapters = repository.getChapters(bookId)
                val localUrls = localChapters.map { it.url }.toSet()
                val localNums = localChapters.map { it.chapterNumber }.toSet()

                val missingList = mutableListOf<MissingChapter>()
                for ((index, chapUrl) in chapterUrls.withIndex()) {
                    val chapNum = index + 1
                    if (!localUrls.contains(chapUrl) && !localNums.contains(chapNum)) {
                        missingList.add(MissingChapter(chapUrl, chapNum))
                    }
                }

                withContext(Dispatchers.Main) {
                    missingChaptersToScrape = missingList
                    if (missingList.isEmpty()) {
                        missingChaptersSummary = "All ${chapterUrls.size} chapters are already downloaded!"
                        addLog("All chapters are already downloaded locally.")
                    } else {
                        missingChaptersSummary = "Found ${missingList.size} missing chapters."
                        addLog("Found ${missingList.size} missing chapters out of ${chapterUrls.size} total chapters.")
                    }
                    isSearchingMissing = false
                }
            } catch (e: Exception) {
                addLog("ERROR: ${e.message}")
                withContext(Dispatchers.Main) {
                    isSearchingMissing = false
                    missingChaptersSummary = "Error during search: ${e.message}"
                }
            }
        }
    }

    fun checkForNewChapters(book: BookEntity) {
        if (isCheckingNewChapters || isScraping) return
        isCheckingNewChapters = true
        checkingNewChaptersBookId = book.id
        checkedBookEntity = book
        newChaptersFoundCount = 0
        newChaptersList = emptyList()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val scraper = SourceManager.getSourceForUrl(book.url, pluginManager)
                val bookUrl = canonicalBookUrl(book.url, scraper)

                val webView = createFreshWebView()

                var chapterUrls = emptyList<String>()
                var tries = 0
                try {
                    while (chapterUrls.isEmpty() && tries < 3) {
                        try {
                            chapterUrls = withTimeoutOrNull(tocTimeoutMs(scraper)) {
                                scraper.scrapeChapterList(webView, bookUrl)
                            } ?: emptyList()
                            if (chapterUrls.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    webView.loadUrl(bookUrl)
                                }
                                delay(5000)
                                chapterUrls = withTimeoutOrNull(tocTimeoutMs(scraper)) {
                                    scraper.scrapeChapterList(webView, bookUrl)
                                } ?: emptyList()
                            }
                        } catch (e: Exception) {
                            tries++
                            delay(2000)
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                }

                if (chapterUrls.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isCheckingNewChapters = false
                        checkingNewChaptersBookId = null
                    }
                    return@launch
                }

                val localChapters = repository.getChapters(book.id)
                val localUrls = localChapters.map { it.url }.toSet()
                val localNums = localChapters.map { it.chapterNumber }.toSet()

                val missingList = mutableListOf<MissingChapter>()
                for ((index, chapUrl) in chapterUrls.withIndex()) {
                    val chapNum = index + 1
                    if (!localUrls.contains(chapUrl) && !localNums.contains(chapNum)) {
                        missingList.add(MissingChapter(chapUrl, chapNum))
                    }
                }

                withContext(Dispatchers.Main) {
                    newChaptersList = missingList
                    newChaptersFoundCount = missingList.size
                    showNewChaptersDialog = true
                    isCheckingNewChapters = false
                    checkingNewChaptersBookId = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isCheckingNewChapters = false
                    checkingNewChaptersBookId = null
                }
            }
        }
    }

    fun startScrapingNewChapters() {
        val book = checkedBookEntity ?: return
        if (newChaptersList.isEmpty()) return
        scrapeUrl = book.url
        scrapeBookName = book.title
        missingChaptersToScrape = newChaptersList
        startScrapingMissing()
        showNewChaptersDialog = false
    }

    fun startScrapingMissing() {
        if (isScraping) return
        val url = scrapeUrl.trim()
        val bookName = scrapeBookName.trim()

        if (url.isEmpty()) {
            addLog("ERROR: Please enter a Novel or Chapter URL!")
            return
        }

        if (missingChaptersToScrape.isEmpty()) {
            addLog("ERROR: No missing chapters found to scrape. Run search first!")
            return
        }

        isScraping = true
        isScrapePaused = false
        shouldSkipCurrentChapter = false
        clearLogs()
        notifyProgressMade()
        startWatchdog()
        addLog("Initiating Scrape Session for ${missingChaptersToScrape.size} Missing Chapters.")
        scrapingStatus = "● Starting..."
        ScrapeService.start(
            application.applicationContext,
            if (bookName.isNotEmpty()) bookName else "Novel",
            missingChaptersToScrape.size
        )

        scrapeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                runScraperLoop(url, bookName, missingChaptersToScrape)
            } catch (e: CancellationException) {
                scrapingStatus = "● Cancelled"
                addLog("Scraping session cancelled by user.")
            } catch (e: Exception) {
                scrapingStatus = "● Error: ${e.message}"
                addLog("CRITICAL ERROR: ${e.message}")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isScraping = false
                    isScrapePaused = false
                    shouldSkipCurrentChapter = false
                    scrapeProgress = 0f
                    ScrapeService.stop(application.applicationContext)
                }
            }
        }
    }

    fun continueScraping() {
        if (isScraping) return
        val url = scrapeUrl.trim()
        if (url.isEmpty()) {
            addLog("ERROR: Please enter a Novel or Chapter URL!")
            return
        }
        val scraper = SourceManager.getSourceForUrl(url, pluginManager)
        val bookId = scraper.parseBookId(url)
        if (bookId == null) {
            addLog("ERROR: Could not determine Novel ID from URL.")
            return
        }
        
        isScraping = true
        isScrapePaused = false
        shouldSkipCurrentChapter = false
        clearLogs()
        notifyProgressMade()
        startWatchdog()
        addLog("Finding last downloaded chapter to continue...")
        scrapingStatus = "● Initializing..."
        ScrapeService.start(
            application.applicationContext,
            if (scrapeBookName.trim().isNotEmpty()) scrapeBookName.trim() else "Novel",
            0
        )

        scrapeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val existingChapters = repository.getChapters(bookId)
                val maxChapterNum = existingChapters.maxOfOrNull { it.chapterNumber } ?: 0
                addLog("Last downloaded chapter number: $maxChapterNum")
                
                withContext(Dispatchers.Main) {
                    fromChapterInput = (maxChapterNum + 1).toString()
                    toChapterInput = "" // all remaining
                    addLog("Resuming scrape from Chapter ${maxChapterNum + 1}")
                }
                
                runScraperLoop(url, scrapeBookName.trim())
            } catch (e: CancellationException) {
                scrapingStatus = "● Cancelled"
                addLog("Scraping session cancelled by user.")
            } catch (e: Exception) {
                scrapingStatus = "● Error: ${e.message}"
                addLog("CRITICAL ERROR: ${e.message}")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isScraping = false
                    isScrapePaused = false
                    shouldSkipCurrentChapter = false
                    scrapeProgress = 0f
                    ScrapeService.stop(application.applicationContext)
                }
            }
        }
    }

    private suspend fun createFreshWebView(): WebView = withContext(Dispatchers.Main) {
        com.example.util.WebViewFactory.create(application.applicationContext, defaultUserAgent)
    }

    private suspend fun runScraperLoop(
        url: String,
        bookName: String,
        specificChapters: List<MissingChapter>? = null
    ) {
        val scraper = SourceManager.getSourceForUrl(url, pluginManager)
        addLog("Using source: ${scraper.sourceName}")
        val bookId = scraper.parseBookId(url) ?: "novel_${System.currentTimeMillis()}"
        val bookUrl = canonicalBookUrl(url, scraper)

        addLog("Initializing scraping WebView on Main thread...")
        var webView = createFreshWebView()

        try {
            // Setup User-Agent & initial cookies
            val userAgent = defaultUserAgent
            var cookies = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(bookUrl) ?: ""
            }

            // 1. Scrape Book Info / TOC
            addLog("Connecting to Book page to extract meta and cover...")
            var bookEntity: BookEntity? = null
            var retryCount = 0

            while (bookEntity == null && retryCount < 3) {
                try {
                    bookEntity = withTimeoutOrNull(35_000L) {
                        scraper.scrapeBookInfo(webView, bookUrl)
                    } ?: throw IOException("Book metadata connection timed out")
                } catch (e: CloudflareException) {
                    addLog("Cloudflare detected on Book page. Opening Captcha bypass...")
                    handleCaptchaChallenge(bookUrl)
                    // update cookies after bypass
                    cookies = withContext(Dispatchers.Main) {
                        CookieManager.getInstance().getCookie(bookUrl) ?: ""
                    }
                } catch (e: Exception) {
                    retryCount++
                    addLog("TOC Connection retry $retryCount/3 due to: ${e.message}")
                    addLog("Auto-recovering: Refreshing background web session...")
                    withContext(Dispatchers.Main) {
                        try { webView.stopLoading() } catch (ignored: Exception) {}
                        try { webView.destroy() } catch (ignored: Exception) {}
                    }
                    webView = createFreshWebView()
                    delay(2000)
                }
            }

            if (bookEntity == null) {
                addLog("ABORTED: Cannot load book metadata or bypass Cloudflare.")
                scrapingStatus = "● Blocked"
                return
            }

            val finalBook = if (bookName.isNotEmpty()) {
                bookEntity.copy(title = bookName)
            } else {
                bookEntity
            }
            var currentBookState = downloadCoverAndSaveMetadata(finalBook, cookies)
            repository.insertBook(currentBookState)
            addLog("Successfully saved book info: ${currentBookState.title} by ${currentBookState.author}")

            // 2. Fetch Chapter List
            addLog("Extracting Table of Contents...")
            var chapterUrls = emptyList<String>()
            try {
                chapterUrls = withTimeoutOrNull(tocTimeoutMs(scraper)) {
                    scraper.scrapeChapterList(webView, bookUrl)
                } ?: emptyList()
            } catch (e: Exception) {
                addLog("TOC parsing failed, using direct scraping if possible: ${e.message}")
            }

            if (chapterUrls.isEmpty()) {
                addLog("No chapter list found. If you pasted a chapter URL directly, we will try standard crawling.")
            } else {
                addLog("TOC loaded: ${chapterUrls.size} chapters found.")
            }

            // Determine chapters to process (either supplied missing list or range-based list)
            val chaptersToProcess = specificChapters ?: run {
                val maxCap = maxChaptersInput.toIntOrNull() ?: 0
                val fromCap = fromChapterInput.toIntOrNull() ?: 1
                val toCap = toChapterInput.toIntOrNull() ?: 0

                // Filter the URLs to download
                val startIndex = (fromCap - 1).coerceAtLeast(0)
                var filteredUrls = if (chapterUrls.isNotEmpty() && startIndex < chapterUrls.size) {
                    chapterUrls.subList(startIndex, chapterUrls.size)
                } else {
                    listOf(url) // paste chapter URL fallback
                }

                if (toCap > 0 && toCap >= fromCap && toCap - fromCap + 1 <= filteredUrls.size) {
                    filteredUrls = filteredUrls.subList(0, toCap - fromCap + 1)
                }

                if (maxCap > 0 && maxCap < filteredUrls.size) {
                    filteredUrls = filteredUrls.subList(0, maxCap)
                }

                filteredUrls.mapIndexed { idx, chapUrl ->
                    MissingChapter(chapUrl, idx + fromCap)
                }
            }

            totalChaptersToScrape = chaptersToProcess.size
            addLog("Preparing to scrape $totalChaptersToScrape chapters...")
            ScrapeService.start(
                application.applicationContext,
                currentBookState.title,
                totalChaptersToScrape
            )

            var sessionDownloadedCount = 0
            val glossaries = repository.getGlossary(currentBookState.id)

            for ((index, item) in chaptersToProcess.withIndex()) {
                if (!isScraping) break

                val chapterUrl = item.url
                val absoluteChapterNum = item.chapterNumber

                shouldSkipCurrentChapter = false // reset for each chapter

                // Wait if paused
                while (isScrapePaused && isScraping) {
                    scrapingStatus = "● Paused (${index + 1}/$totalChaptersToScrape)"
                    ScrapeService.update(
                        application.applicationContext,
                        index + 1,
                        "Paused"
                    )
                    delay(500)
                }

                if (!isScraping) break

                currentChapterNum = index + 1
                scrapeProgress = currentChapterNum.toFloat() / totalChaptersToScrape
                scrapingStatus = "● Downloading ($currentChapterNum/$totalChaptersToScrape)..."
                ScrapeService.update(
                    application.applicationContext,
                    currentChapterNum,
                    "Downloading"
                )

                val chapId = scraper.parseChapterId(chapterUrl) ?: "ch_$absoluteChapterNum"
                val fullChapId = "${currentBookState.id}_$chapId"

                // Check if already downloaded locally
                val existing = repository.getChapter(fullChapId)
                if (existing != null && existing.content.length > 100) {
                    addLog("Chapter $absoluteChapterNum already downloaded. Skipping.")
                    continue
                }

                var downloadSuccess = false
                var tries = 0

                while (!downloadSuccess && tries < 3 && isScraping) {
                    if (shouldSkipCurrentChapter) {
                        break
                    }
                    try {
                        val rawContent = withTimeoutOrNull(40_000L) {
                            scraper.scrapeChapterContent(webView, chapterUrl) { shouldSkipCurrentChapter }
                        } ?: throw IOException("Chapter download timed out (Limbo detected)")

                        val title = rawContent.first
                        var cleanedBody = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)

                        // Apply customized glossaries if any exist
                        if (glossaries.isNotEmpty()) {
                            cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                        }

                        // Create MD5 Hash
                        val md5 = java.security.MessageDigest.getInstance("MD5")
                        val hash = md5.digest(cleanedBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                        val chapterEntity = ChapterEntity(
                            id = fullChapId,
                            bookId = currentBookState.id,
                            chapterId = chapId,
                            chapterNumber = absoluteChapterNum,
                            title = title,
                            url = chapterUrl,
                            content = cleanedBody,
                            hash = hash
                        )

                        repository.insertChapter(chapterEntity)
                        addLog("Saved Chapter: $title (${cleanedBody.length} chars)")
                        downloadSuccess = true
                        sessionDownloadedCount++

                        // Save reading progress if not set
                        if (index == 0 && currentBookState.lastReadChapterId == null) {
                            currentBookState = currentBookState.copy(lastReadChapterId = fullChapId)
                            repository.updateBook(currentBookState)
                        }
                    } catch (e: CloudflareException) {
                        addLog("Cloudflare CAPTCHA blocked download on chapter $currentChapterNum. Pausing loop...")
                        handleCaptchaChallenge(chapterUrl)
                        // refresh cookies
                        cookies = withContext(Dispatchers.Main) {
                            CookieManager.getInstance().getCookie(bookUrl) ?: ""
                        }
                    } catch (e: Exception) {
                        tries++
                        if (shouldSkipCurrentChapter) {
                            break
                        }
                        addLog("Stall/Limbo on chapter $currentChapterNum, retry $tries/3: ${e.message}")
                        addLog("Auto-recovering: Refreshing web session & restarting WebView...")
                        withContext(Dispatchers.Main) {
                            try { webView.stopLoading() } catch (ignored: Exception) {}
                            try { webView.destroy() } catch (ignored: Exception) {}
                        }
                        webView = createFreshWebView()
                        delay(2000)
                    }
                }

                if (shouldSkipCurrentChapter) {
                    addLog("Skipped Chapter $absoluteChapterNum by user request.")
                    shouldSkipCurrentChapter = false
                    continue
                }

                // Simple delay to respect scraping etiquette and rate limits based on user settings
                if (isScraping && index < chaptersToProcess.size - 1) {
                    val source = SourceManager.getSourceForUrl(chapterUrl, pluginManager)
                    val siteDelay = (source as? com.example.data.plugin.PluginExecutionEngine)?.config?.requestDelayMs ?: 0
                    val delayTime = (if (siteDelay > 0) siteDelay.toLong() else settings.requestDelayMs.toLong()).coerceAtLeast(300L)
                    delay(delayTime)
                }
            }

            // 3. Post-compile formats (EPUB/PDF) if required
            if (sessionDownloadedCount > 0 || chapterUrls.isNotEmpty()) {
                val allLocalChapters = repository.getChapters(currentBookState.id)
                currentBookState = currentBookState.copy(totalChapters = allLocalChapters.size)
                repository.updateBook(currentBookState)

                val context = application.applicationContext
                val outputFolder = File(context.filesDir, currentBookState.id)
                if (!outputFolder.exists()) outputFolder.mkdirs()

                if (selectedFormat == "EPUB" || selectedFormat == "Both") {
                    scrapingStatus = "● Packaging EPUB..."
                    ScrapeService.update(
                        application.applicationContext,
                        currentChapterNum,
                        "Packaging EPUB"
                    )
                    addLog("Compiling downloaded chapters into EPUB ebook...")
                    val epubFile = File(outputFolder, "${currentBookState.id}.epub")
                    val ok = NovelCompiler.compileEpub(context, currentBookState, allLocalChapters, epubFile)
                    if (ok) {
                        addLog("EPUB Compilation Successful! Path: ${epubFile.name}")
                    } else {
                        addLog("EPUB compilation failed!")
                    }
                }

                if (selectedFormat == "PDF" || selectedFormat == "Both") {
                    scrapingStatus = "● Packaging PDF..."
                    ScrapeService.update(
                        application.applicationContext,
                        currentChapterNum,
                        "Packaging PDF"
                    )
                    addLog("Compiling downloaded chapters into PDF ebook...")
                    val pdfFile = File(outputFolder, "${currentBookState.id}.pdf")
                    val ok = NovelCompiler.compilePdf(context, currentBookState, allLocalChapters, pdfFile)
                    if (ok) {
                        addLog("PDF Compilation Successful! Path: ${pdfFile.name}")
                    } else {
                        addLog("PDF compilation failed!")
                    }
                }
            }

            scrapingStatus = "● Finished"
            addLog("Scrape Session completed! Successfully processed all targeted chapters.")
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    webView.destroy()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private suspend fun handleCaptchaChallenge(url: String) {
        withContext(Dispatchers.Main) {
            captchaUrl = url
            showCaptchaDialog = true
            scrapingStatus = "● Captcha Verification Required"
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            captchaContinuation = continuation
            continuation.invokeOnCancellation {
                captchaContinuation = null
            }
        }
    }

    fun resumeAfterCaptcha() {
        showCaptchaDialog = false
        captchaContinuation?.resume(Unit) {
            // Cancellation cleanups
        }
        captchaContinuation = null
    }

    suspend fun downloadSingleChapter(chapter: ChapterEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (chapter.url.startsWith("local://")) {
                return@runCatching
            }
            val scraper = SourceManager.getSourceForUrl(chapter.url, pluginManager)
            val webView = createFreshWebView()
            try {
                val rawContent = scraper.scrapeChapterContent(webView, chapter.url) { false }
                val title = rawContent.first
                var cleanedBody = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)

                val glossaries = repository.getGlossary(chapter.bookId)
                if (glossaries.isNotEmpty()) {
                    cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                }

                val md5 = java.security.MessageDigest.getInstance("MD5")
                val hash = md5.digest(cleanedBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                val updatedChapter = chapter.copy(
                    title = if (title.isNotBlank()) title else chapter.title,
                    content = cleanedBody,
                    hash = hash,
                    downloadedAt = System.currentTimeMillis()
                )

                repository.deletePolishedChapter(chapter.id)
                repository.deleteChapterRecap(chapter.id)
                repository.insertChapter(updatedChapter)
            } finally {
                withContext(Dispatchers.Main) {
                    try { webView.destroy() } catch (_: Exception) {}
                }
            }
        }
    }

    fun downloadNextChapters(bookId: String, limit: Int = 10, onComplete: ((Int, Int) -> Unit)? = null) {
        coroutineScope.launch(Dispatchers.IO) {
            val pending = repository.getPendingChapters(bookId, limit)
            var success = 0
            var fail = 0
            for ((idx, ch) in pending.withIndex()) {
                val res = downloadSingleChapter(ch)
                if (res.isSuccess) success++ else fail++
                if (idx < pending.size - 1) {
                    val source = SourceManager.getSourceForUrl(ch.url, pluginManager)
                    val siteDelay = (source as? com.example.data.plugin.PluginExecutionEngine)?.config?.requestDelayMs ?: 0
                    val delayTime = (if (siteDelay > 0) siteDelay.toLong() else settings.requestDelayMs.toLong()).coerceAtLeast(300L)
                    delay(delayTime)
                }
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke(success, fail)
            }
        }
    }

    fun downloadAllRemainingChapters(bookId: String, onComplete: ((Int, Int) -> Unit)? = null) {
        downloadNextChapters(bookId, limit = 10000, onComplete = onComplete)
    }

    fun refreshTableOfContents(bookId: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val book = repository.getBook(bookId)
                if (book == null) {
                    withContext(Dispatchers.Main) { onComplete?.invoke(false, "Novel not found.") }
                    return@launch
                }
                if (book.url.startsWith("local://")) {
                    withContext(Dispatchers.Main) { onComplete?.invoke(false, "Cannot refresh Table of Contents for local imported files.") }
                    return@launch
                }

                val scraper = SourceManager.getSourceForUrl(book.url, pluginManager)
                val webView = createFreshWebView()

                val chapterUrls = try {
                    withTimeoutOrNull(tocTimeoutMs(scraper)) {
                        scraper.scrapeChapterList(webView, book.url)
                    } ?: emptyList()
                } finally {
                    withContext(Dispatchers.Main) {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                }

                val existingChapters = repository.getChapters(bookId).associateBy { it.url }
                var newCount = 0
                val chapterEntities = mutableListOf<ChapterEntity>()

                chapterUrls.forEachIndexed { index, chapterUrl ->
                    val chapNum = index + 1
                    val chapId = scraper.parseChapterId(chapterUrl) ?: "ch_$chapNum"
                    val fullChapId = "${bookId}_$chapId"
                    val existing = existingChapters[chapterUrl]
                    if (existing != null) {
                        chapterEntities.add(existing)
                    } else {
                        newCount++
                        chapterEntities.add(
                            ChapterEntity(
                                id = fullChapId,
                                bookId = bookId,
                                chapterId = chapId,
                                chapterNumber = chapNum,
                                title = "Chapter $chapNum",
                                url = chapterUrl,
                                content = "",
                                hash = ""
                            )
                        )
                    }
                }

                repository.insertChapters(chapterEntities)
                val updatedBook = book.copy(
                    totalChapters = chapterEntities.size,
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateBook(updatedBook)

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, "Table of contents updated! Found $newCount new chapter(s). Total: ${chapterEntities.size}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, "Failed to refresh table of contents: ${e.message}")
                }
            }
        }
    }

    fun rescrapeSingleChapter(chapter: ChapterEntity, onComplete: (Boolean, String) -> Unit) {
        if (rescrapingChapterId != null) {
            onComplete(false, "Already rescraping another chapter.")
            return
        }
        rescrapingChapterId = chapter.id
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val scraper = SourceManager.getSourceForUrl(chapter.url, pluginManager)
                val webView = createFreshWebView()
                
                try {
                    val rawContent = scraper.scrapeChapterContent(webView, chapter.url) { false }
                    val title = rawContent.first
                    var cleanedBody = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)
                    
                    // Apply customized glossaries if any exist
                    val glossaries = repository.getGlossary(chapter.bookId)
                    if (glossaries.isNotEmpty()) {
                        cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                    }
                    
                    // Create MD5 Hash
                    val md5 = java.security.MessageDigest.getInstance("MD5")
                    val hash = md5.digest(cleanedBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    
                    val updatedChapter = chapter.copy(
                        title = title,
                        content = cleanedBody,
                        hash = hash,
                        downloadedAt = System.currentTimeMillis()
                    )
                    
                    // Delete any cached polished/translated or recap data
                    repository.deletePolishedChapter(chapter.id)
                    repository.deleteChapterRecap(chapter.id)
                    
                    // Insert the fresh chapter content
                    repository.insertChapter(updatedChapter)
                    
                    withContext(Dispatchers.Main) {
                        rescrapingChapterId = null
                        onComplete(true, "Successfully rescraped chapter: $title")
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        try {
                            webView.destroy()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    rescrapingChapterId = null
                    onComplete(false, "Error: ${e.message}")
                }
            }
        }
    }

    fun rescrapeCorruptedChapters(bookId: String, onComplete: (Boolean, String) -> Unit) {
        if (isRescrapingBookId != null) {
            onComplete(false, "Already rescraping another novel.")
            return
        }
        isRescrapingBookId = bookId
        rescrapeBookProgress = 0f
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val chapters = repository.getChapters(bookId)
                val book = repository.getBook(bookId)
                if (book == null) {
                    withContext(Dispatchers.Main) {
                        isRescrapingBookId = null
                        onComplete(false, "Novel not found.")
                    }
                    return@launch
                }
                
                val corrupted = chapters.filter { chapter ->
                    val body = chapter.content
                    val bodyLower = body.lowercase()
                    body.length < 2500 && (
                        bodyLower.contains("login to") || 
                        bodyLower.contains("log in") || 
                        bodyLower.contains("sign in to") || 
                        bodyLower.contains("limit exceeded") || 
                        bodyLower.contains("rate limit") || 
                        bodyLower.contains("too many requests") || 
                        bodyLower.contains("access denied") || 
                        bodyLower.contains("unauthorized") || 
                        bodyLower.contains("forbidden") || 
                        bodyLower.contains("create an account") || 
                        bodyLower.contains("membership") || 
                        bodyLower.contains("please register") ||
                        bodyLower.contains("sign in with") ||
                        bodyLower.contains("google login") ||
                        bodyLower.contains("facebook login")
                    )
                }
                
                if (corrupted.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isRescrapingBookId = null
                        onComplete(true, "No corrupted or invalid chapters detected in this novel.")
                    }
                    return@launch
                }
                
                val scraper = SourceManager.getSourceForUrl(book.url, pluginManager)
                val webView = createFreshWebView()
                
                var successCount = 0
                val glossaries = repository.getGlossary(bookId)
                
                try {
                    corrupted.forEachIndexed { index, chapter ->
                        try {
                            val rawContent = scraper.scrapeChapterContent(webView, chapter.url) { false }
                            val title = rawContent.first
                            var cleanedBody = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)
                            
                            if (glossaries.isNotEmpty()) {
                                cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                            }
                            
                            val md5 = java.security.MessageDigest.getInstance("MD5")
                            val hash = md5.digest(cleanedBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                            
                            val updatedChapter = chapter.copy(
                                title = title,
                                content = cleanedBody,
                                hash = hash,
                                downloadedAt = System.currentTimeMillis()
                            )
                            
                            repository.deletePolishedChapter(chapter.id)
                            repository.deleteChapterRecap(chapter.id)
                            repository.insertChapter(updatedChapter)
                            successCount++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        withContext(Dispatchers.Main) {
                            rescrapeBookProgress = (index + 1).toFloat() / corrupted.size
                        }
                        delay(1500)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        try {
                            webView.destroy()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    isRescrapingBookId = null
                    onComplete(true, "Completed! Successfully rescraped $successCount out of ${corrupted.size} corrupted chapters.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRescrapingBookId = null
                    onComplete(false, "Error: ${e.message}")
                }
            }
        }
    }

    private fun downloadCoverAndSaveMetadata(book: BookEntity, cookies: String): BookEntity {
        val context = application.applicationContext
        val outputFolder = File(context.filesDir, book.id)
        if (!outputFolder.exists()) outputFolder.mkdirs()

        // 1. Download Cover Image
        var updatedBook = book
        val coverUrl = book.coverUrl
        if (!coverUrl.isNullOrEmpty()) {
            try {
                addLog("Downloading book cover from $coverUrl...")
                val coverFile = File(outputFolder, "cover.jpg")
                val url = java.net.URL(coverUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", defaultUserAgent)
                if (cookies.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookies)
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val responseCode = conn.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(coverFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    updatedBook = book.copy(coverLocalPath = coverFile.absolutePath)
                    addLog("Book cover downloaded successfully to: ${coverFile.name}")
                } else {
                    addLog("Failed to download book cover (HTTP $responseCode)")
                }
            } catch (e: Exception) {
                addLog("Error downloading book cover: ${e.message}")
                e.printStackTrace()
            }
        }

        // 2. Save info.json metadata
        try {
            val infoFile = File(outputFolder, "info.json")
            val json = org.json.JSONObject().apply {
                put("id", updatedBook.id)
                put("url", updatedBook.url)
                put("title", updatedBook.title)
                put("author", updatedBook.author)
                put("synopsis", updatedBook.synopsis)
                put("coverUrl", updatedBook.coverUrl ?: "")
                put("coverLocalPath", updatedBook.coverLocalPath ?: "")
                put("totalChapters", updatedBook.totalChapters)
                put("updatedAt", updatedBook.updatedAt)
            }
            infoFile.writeText(json.toString(4))
            addLog("Saved metadata info.json for book: ${updatedBook.title}")
        } catch (e: Exception) {
            addLog("Error saving info.json: ${e.message}")
            e.printStackTrace()
        }

        return updatedBook
    }
}
