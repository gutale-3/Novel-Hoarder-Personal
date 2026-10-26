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
/**
 * Every downloaded chapter passes through here before it is saved, so this is where an anti-bot
 * page is refused: throwing [com.example.util.CloudflareException] makes the download loop open
 * the solve-it dialog instead of storing the notice as chapter text.
 */
internal fun cleanScrapedBody(
    scraper: com.example.data.scraper.NovelSource,
    raw: String,
    aggressive: Boolean
): String {
    if (com.example.util.CloudflareDetector.isChallengeText(raw)) {
        throw com.example.util.CloudflareException("${scraper.sourceName} showed a security check instead of the chapter")
    }
    val cleaned = if (scraper === com.example.util.TomatoScraper) {
        com.example.util.TomatoScraper.sanitizeText(raw, aggressive)
    } else {
        com.example.util.GenericScraper.sanitizeText(raw, aggressive)
    }
    return com.example.util.ChapterTextCleaner.removeJunk(cleaned)
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
    /**
     * The User-Agent the solver dialog must use. Cloudflare binds its clearance cookie to the UA
     * that solved the challenge, so a plugin with a custom UA needs the dialog to match it.
     */
    var captchaUserAgent by mutableStateOf("")
    private var captchaSourceKey: String? = null
    private var captchaContinuation: CancellableContinuation<Unit>? = null

    private val sessionPrefs = application.getSharedPreferences("novel_hoarder_prefs", android.content.Context.MODE_PRIVATE)

    /**
     * A download that was still running when the app process died (swiped away, killed by the
     * OS, phone restarted). The marker is written when a session starts and cleared in the
     * session's `finally`, which never runs if the process dies — so a leftover marker means
     * the download was interrupted and can be resumed.
     */
    var interruptedSessionUrl by mutableStateOf(sessionPrefs.getString(KEY_ACTIVE_URL, null))
        private set
    var interruptedSessionName by mutableStateOf(sessionPrefs.getString(KEY_ACTIVE_NAME, null).orEmpty())
        private set

    private fun markSessionActive(url: String, name: String) {
        sessionPrefs.edit().putString(KEY_ACTIVE_URL, url).putString(KEY_ACTIVE_NAME, name).apply()
    }

    private fun markSessionEnded() {
        sessionPrefs.edit().remove(KEY_ACTIVE_URL).remove(KEY_ACTIVE_NAME).apply()
    }

    fun resumeInterruptedSession() {
        val url = interruptedSessionUrl ?: return
        scrapeUrl = url
        scrapeBookName = interruptedSessionName
        dismissInterruptedSession()
        continueScraping()
    }

    fun dismissInterruptedSession() {
        interruptedSessionUrl = null
        interruptedSessionName = ""
        if (!isScraping) markSessionEnded()
    }

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

    private val quota by lazy { com.example.data.scraper.DownloadQuota.get(application) }

    /** Chapter title without the novel's name or the site's name around it. */
    private suspend fun cleanTitleFor(
        chapter: ChapterEntity,
        rawTitle: String,
        scraper: com.example.data.scraper.NovelSource
    ): String {
        val bookTitle = repository.getBook(chapter.bookId)?.title.orEmpty()
        return com.example.util.ChapterTitleCleaner.clean(rawTitle, bookTitle, scraper.sourceName, chapter.chapterNumber)
    }
    private val sourceFallback by lazy { com.example.NovelHoarderApp.getContainer(application).sourceFallback }

    /**
     * Holds the session while [sourceKey]'s hourly/daily limit is used up, rather than fetching
     * pages the site will answer with a login/limit notice. Returns false if stopped meanwhile.
     */
    /** Set by "Continue now" on the Scrape screen: skip the current limit wait once. */
    @Volatile var skipQuotaWaitRequested = false
    var isWaitingForQuota by mutableStateOf(false)
        private set

    private suspend fun awaitQuota(sourceKey: String, chapterUrl: String): Boolean {
        // A site paused after a background bot check: the user is here now, so hand them the
        // check instead of sitting out the pause.
        if (quota.challengePausedUntil(sourceKey) > 0) {
            addLog("$sourceKey showed a security check earlier. Opening it so you can pass it…")
            handleCaptchaChallenge(chapterUrl, "This site asked for a security check earlier. Pass it, then tap Resume.")
            quota.clearChallenge(sourceKey)
        }
        var announced = false
        skipQuotaWaitRequested = false
        try {
            while (isScraping) {
                val wait = quota.waitMs(sourceKey)
                if (wait == 0L) {
                    if (announced) addLog("Download limit window reopened for $sourceKey. Continuing.")
                    return true
                }
                if (skipQuotaWaitRequested) {
                    skipQuotaWaitRequested = false
                    addLog("Continuing now as requested (limit for $sourceKey ignored this once).")
                    return true
                }
                isWaitingForQuota = true
                val resumeAt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                    .format(java.util.Date(System.currentTimeMillis() + wait))
                if (!announced) {
                    val l = quota.limitsFor(sourceKey)
                    val used = quota.usage(sourceKey)
                    addLog("Site limit reached for $sourceKey: ${used.lastHour}/${l.perHour} this hour, " +
                        "${used.lastDay}/${if (l.perDay == 0) "∞" else l.perDay} today. Waiting until $resumeAt " +
                        "(tap “Continue now” to skip).")
                    announced = true
                }
                scrapingStatus = "● Waiting for site limit (resumes $resumeAt)"
                notifyProgressMade() // a deliberate wait is not a stall
                var slept = 0L
                while (slept < minOf(wait, 15_000L) && !skipQuotaWaitRequested && isScraping) {
                    delay(500); slept += 500
                }
            }
            return false
        } finally {
            isWaitingForQuota = false
        }
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
                        
                        // In its own job: stopScraping() cancels this watchdog, which would
                        // otherwise kill the restart before it happens.
                        coroutineScope.launch(Dispatchers.Main) {
                            stopScraping()
                            delay(2000L)
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

        markSessionActive(url, bookName)
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
                markSessionEnded()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
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
                            com.example.util.WebViewFactory.destroySafely(webView)
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
                val maxLocalChapNum = localChapters.maxOfOrNull { it.chapterNumber } ?: 0
                val userFromCap = fromChapterInput.toIntOrNull()
                val deletedUrls = com.example.util.ChapterUrls.deletedFor(application, bookId)

                val missingList = mutableListOf<MissingChapter>()
                if (maxLocalChapNum == 0) {
                    val localUrls = localChapters.map { com.example.util.ChapterUrls.normalize(it.url) }.toSet()
                    val localNums = localChapters.map { it.chapterNumber }.toSet()
                    for ((index, chapUrl) in chapterUrls.withIndex()) {
                        val chapNum = index + 1
                        if (!localUrls.contains(com.example.util.ChapterUrls.normalize(chapUrl)) && !localNums.contains(chapNum)) {
                            missingList.add(MissingChapter(chapUrl, chapNum))
                        }
                    }
                } else {
                    val sortedLocals = localChapters.sortedByDescending { it.chapterNumber }
                    val tocNormalized = chapterUrls.map { com.example.util.ChapterUrls.normalize(it) }
                    var matchedTocIndex = -1
                    var matchedChapNum = maxLocalChapNum
                    for (loc in sortedLocals) {
                        val idx = tocNormalized.indexOf(com.example.util.ChapterUrls.normalize(loc.url))
                        if (idx >= 0) {
                            matchedTocIndex = idx
                            matchedChapNum = loc.chapterNumber
                            break
                        }
                    }
                    val startIndex = if (matchedTocIndex >= 0) {
                        matchedTocIndex + 1
                    } else {
                        maxLocalChapNum.coerceAtMost(chapterUrls.size)
                    }

                    for (i in startIndex until chapterUrls.size) {
                        val chapUrl = chapterUrls[i]
                        val chapNum = if (matchedTocIndex >= 0) {
                            matchedChapNum + (i - matchedTocIndex)
                        } else {
                            maxLocalChapNum + 1 + (i - startIndex)
                        }
                        if ((userFromCap == null || chapNum >= userFromCap) && com.example.util.ChapterUrls.normalize(chapUrl) !in deletedUrls) {
                            missingList.add(MissingChapter(chapUrl, chapNum))
                        }
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
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    com.example.util.WebViewFactory.destroySafely(webView)
                }
            }
        }
    }

    /**
     * @param autoGrab start downloading the new chapters straight away instead of asking; the
     * Updates tab passes true because the user already chose "Download".
     */
    fun checkForNewChapters(book: BookEntity, autoGrab: Boolean = settings.autoGrabNewChapters) {
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
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        com.example.util.WebViewFactory.destroySafely(webView)
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
                val maxLocalChapNum = localChapters.maxOfOrNull { it.chapterNumber } ?: 0
                val deletedUrls = com.example.util.ChapterUrls.deletedFor(application, book.id)

                val missingList = mutableListOf<MissingChapter>()
                if (maxLocalChapNum == 0) {
                    val localUrls = localChapters.map { com.example.util.ChapterUrls.normalize(it.url) }.toSet()
                    val localNums = localChapters.map { it.chapterNumber }.toSet()
                    for ((index, chapUrl) in chapterUrls.withIndex()) {
                        val chapNum = index + 1
                        if (!localUrls.contains(com.example.util.ChapterUrls.normalize(chapUrl)) && !localNums.contains(chapNum)) {
                            missingList.add(MissingChapter(chapUrl, chapNum))
                        }
                    }
                } else {
                    // Strictly grab chapters starting after the user's latest downloaded chapter (e.g. 501 if 500, 672 if 671, 1001 if 1000)
                    // Even if earlier chapters were deleted to save scrolling length, never grab deleted chapters.
                    val sortedLocals = localChapters.sortedByDescending { it.chapterNumber }
                    val tocNormalized = chapterUrls.map { com.example.util.ChapterUrls.normalize(it) }
                    var matchedTocIndex = -1
                    var matchedChapNum = maxLocalChapNum
                    for (loc in sortedLocals) {
                        val idx = tocNormalized.indexOf(com.example.util.ChapterUrls.normalize(loc.url))
                        if (idx >= 0) {
                            matchedTocIndex = idx
                            matchedChapNum = loc.chapterNumber
                            break
                        }
                    }
                    val startIndex = if (matchedTocIndex >= 0) {
                        matchedTocIndex + 1
                    } else {
                        maxLocalChapNum.coerceAtMost(chapterUrls.size)
                    }

                    for (i in startIndex until chapterUrls.size) {
                        val chapUrl = chapterUrls[i]
                        val chapNum = if (matchedTocIndex >= 0) {
                            matchedChapNum + (i - matchedTocIndex)
                        } else {
                            maxLocalChapNum + 1 + (i - startIndex)
                        }
                        if (com.example.util.ChapterUrls.normalize(chapUrl) !in deletedUrls) missingList.add(MissingChapter(chapUrl, chapNum))
                    }
                }

                withContext(Dispatchers.Main) {
                    newChaptersList = missingList
                    newChaptersFoundCount = missingList.size
                    isCheckingNewChapters = false
                    checkingNewChaptersBookId = null

                    val updates = com.example.background.ChapterUpdatesStore.get(application)
                    if (missingList.isEmpty() || autoGrab) {
                        updates.clear(book.id)
                    } else {
                        updates.record(
                            com.example.background.ChapterUpdatesStore.Entry(
                                book.id, missingList.size, missingList.first().chapterNumber, System.currentTimeMillis()
                            )
                        )
                    }

                    if (missingList.isNotEmpty() && autoGrab) {
                        addLog("Auto-Grab enabled: Found ${missingList.size} new chapters (starting from Ch. ${missingList.first().chapterNumber}). Automatically grabbing...")
                        startScrapingNewChapters()
                    } else {
                        showNewChaptersDialog = true
                    }
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

        markSessionActive(url, bookName)
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
                markSessionEnded()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
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

        markSessionActive(url, scrapeBookName.trim())
        scrapeJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                // Empty placeholders (from a table-of-contents refresh or update check) don't count.
                val existingChapters = repository.getChapters(bookId).filter { it.content.isNotBlank() }
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
                markSessionEnded()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
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
                        com.example.util.WebViewFactory.destroySafely(webView)
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
            val localizedBook = if (com.example.data.scraper.RawTranslation.isRawSource(bookUrl) &&
                com.example.data.scraper.RawTranslation.looksUntranslated(finalBook.title + finalBook.synopsis)
            ) {
                runCatching {
                    val t = com.example.util.TranslatorEngine.translateText(
                        application, finalBook.title, allowMobileData = !settings.translationRequireWifi
                    )
                    val s = com.example.util.TranslatorEngine.translateText(
                        application, finalBook.synopsis, allowMobileData = !settings.translationRequireWifi
                    )
                    finalBook.copy(title = t.trim(), synopsis = s)
                }.getOrDefault(finalBook)
            } else finalBook
            var currentBookState = BookMetadataWriter.downloadCoverAndSaveMetadata(application, localizedBook, cookies, defaultUserAgent, ::addLog)
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
            val knownByUrl = repository.getAllChaptersIncludingHidden(currentBookState.id)
                .associateBy { com.example.util.ChapterUrls.normalize(it.url) }
            val deletedUrls = com.example.util.ChapterUrls.deletedFor(application, currentBookState.id)
            val glossaries = repository.getGlossary(currentBookState.id)
            val sourceKey = com.example.data.scraper.SourceKeys.keyFor(bookUrl)

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

                // The same chapter saved under another address (e.g. from the browser) counts as
                // this one: fill or skip it instead of adding a second copy.
                val sameChapter = knownByUrl[com.example.util.ChapterUrls.normalize(chapterUrl)]
                if (sameChapter?.isDeleted == true || com.example.util.ChapterUrls.normalize(chapterUrl) in deletedUrls) {
                    addLog("Chapter $absoluteChapterNum was deleted by you. Skipping.")
                    continue
                }
                val chapId = sameChapter?.chapterId ?: scraper.parseChapterId(chapterUrl) ?: "ch_$absoluteChapterNum"
                val fullChapId = sameChapter?.id ?: "${currentBookState.id}_$chapId"
                val slotNumber = sameChapter?.chapterNumber ?: absoluteChapterNum

                // Check if already downloaded locally
                val existing = sameChapter ?: repository.getChapter(fullChapId)
                if (existing != null && existing.content.length > 100) {
                    addLog("Chapter $absoluteChapterNum already downloaded. Skipping.")
                    continue
                }

                if (!awaitQuota(sourceKey, chapterUrl)) break

                var downloadSuccess = false
                var tries = 0

                while (!downloadSuccess && tries < 3 && isScraping) {
                    if (shouldSkipCurrentChapter) {
                        break
                    }
                    try {
                        val rawContent = withTimeoutOrNull(com.example.util.ScrapeTimeouts.chapterMs + 15_000L) {
                            scraper.scrapeChapterContent(webView, chapterUrl) { shouldSkipCurrentChapter }
                        } ?: throw IOException("Chapter download timed out (Limbo detected)")

                        var title = rawContent.first
                        var cleanedBody = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)

                        // Raw (Chinese) sources: translate before the glossary/hash steps.
                        com.example.data.scraper.RawTranslation.localize(
                            application, currentBookState.id, chapterUrl, title, cleanedBody, glossaries,
                            allowMobileData = !settings.translationRequireWifi
                        ).let { (t, b) -> title = t; cleanedBody = b }

                        // Apply customized glossaries if any exist
                        if (glossaries.isNotEmpty()) {
                            cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                        }

                        // Create MD5 Hash
                        val hash = com.example.util.ContentHash.of(cleanedBody)

                        val chapterEntity = ChapterEntity(
                            id = fullChapId,
                            bookId = currentBookState.id,
                            chapterId = chapId,
                            chapterNumber = slotNumber,
                            title = com.example.util.ChapterTitleCleaner.clean(title, currentBookState.title, scraper.sourceName, absoluteChapterNum),
                            url = chapterUrl,
                            content = cleanedBody,
                            hash = hash
                        )

                        repository.insertChapter(chapterEntity)
                        quota.record(sourceKey)
                        addLog("Saved Chapter: $title (${cleanedBody.length} chars)")
                        downloadSuccess = true
                        sessionDownloadedCount++

                        // Save reading progress if not set
                        if (index == 0 && currentBookState.lastReadChapterId == null) {
                            currentBookState = currentBookState.copy(lastReadChapterId = fullChapId)
                            repository.updateBook(currentBookState)
                        }
                    } catch (e: CloudflareException) {
                        com.example.data.scraper.HumanPacing.onChallenge(application, sourceKey)
                        addLog("Security check on chapter $currentChapterNum (${e.message}). Nothing was saved. " +
                            "Solve it in the window that opens; downloads from this site will then go slower.")
                        handleCaptchaChallenge(chapterUrl)
                        // Give the site a calm moment after the check before asking again.
                        delay(4_000L + kotlin.random.Random.nextLong(4_000L))
                        // refresh cookies
                        cookies = withContext(Dispatchers.Main) {
                            CookieManager.getInstance().getCookie(bookUrl) ?: ""
                        }
                    } catch (e: Exception) {
                        tries++
                        if (shouldSkipCurrentChapter) {
                            break
                        }
                        if (tries == 1) {
                            // First problem on this chapter: hand it to the user straight away rather
                            // than retrying blindly — usually it is a check that takes a second to pass.
                            val secs = com.example.util.ScrapeTimeouts.chapterSeconds
                            addLog("Chapter $absoluteChapterNum didn't load within ${secs}s (${e.message}). Opening it for you…")
                            handleCaptchaChallenge(
                                chapterUrl,
                                "Chapter $absoluteChapterNum didn't load in ${secs}s. Fix whatever the page shows " +
                                    "(security check, button, popup), then tap Resume."
                            )
                            continue
                        }
                        addLog("Stall/Limbo on chapter $currentChapterNum, retry $tries/3: ${e.message}")
                        addLog("Auto-recovering: Refreshing web session & restarting WebView...")
                        withContext(Dispatchers.Main) {
                            com.example.util.WebViewFactory.destroySafely(webView)
                        }
                        webView = createFreshWebView()
                        delay(2000)
                    }
                }

                if (!downloadSuccess && isScraping && !shouldSkipCurrentChapter && settings.sourceFallbackEnabled) {
                    addLog("Chapter $absoluteChapterNum failed on ${scraper.sourceName}; trying backup sites…")
                    val altWebView = createFreshWebView()
                    try {
                        val alt = runCatching {
                            sourceFallback.fetchChapter(currentBookState, absoluteChapterNum, altWebView)
                        }.getOrNull()
                        // Backup text gets the generic cleaner (Tomato's would cut real lines), and a
                        // security page there must not end the whole download.
                        val altBody = alt?.let {
                            runCatching { cleanScrapedBody(com.example.util.GenericScraper, it.second, aggressiveClean) }.getOrNull()
                        }?.takeIf { it.isNotBlank() }
                        if (alt != null && altBody != null) {
                            var body = altBody
                            if (glossaries.isNotEmpty()) body = repository.applyGlossary(body, glossaries)
                            repository.insertChapter(
                                ChapterEntity(
                                    id = fullChapId,
                                    bookId = currentBookState.id,
                                    chapterId = chapId,
                                    chapterNumber = slotNumber,
                                    title = com.example.util.ChapterTitleCleaner.clean(alt.first, currentBookState.title, alt.third, absoluteChapterNum),
                                    url = chapterUrl,
                                    content = body,
                                    hash = com.example.util.ContentHash.of(body)
                                )
                            )
                            sessionDownloadedCount++
                            downloadSuccess = true
                            addLog("Saved Chapter $absoluteChapterNum from backup site ${alt.third}.")
                        } else {
                            addLog("No backup site could provide chapter $absoluteChapterNum.")
                        }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { com.example.util.WebViewFactory.destroySafely(altWebView) }
                    }
                }

                if (shouldSkipCurrentChapter) {
                    addLog("Skipped Chapter $absoluteChapterNum by user request.")
                    shouldSkipCurrentChapter = false
                    continue
                }

                // Uneven, human-like gaps; slower for a site that has recently shown a bot check.
                if (isScraping && index < chaptersToProcess.size - 1) {
                    val source = SourceManager.getSourceForUrl(chapterUrl, pluginManager)
                    val siteDelay = (source as? com.example.data.plugin.PluginExecutionEngine)?.config?.requestDelayMs ?: 0
                    val base = (if (siteDelay > 0) siteDelay.toLong() else settings.requestDelayMs.toLong()).coerceAtLeast(300L)
                    val wait = com.example.data.scraper.HumanPacing.nextDelayMs(application, sourceKey, base, index + 1)
                    if (wait > 15_000L) {
                        addLog("Pausing ${wait / 1000}s between chapters to keep the pace human…")
                        scrapingStatus = "● Short pause (${wait / 1000}s)"
                    }
                    var left = wait
                    while (left > 0 && isScraping) {
                        notifyProgressMade()
                        val step = minOf(left, 5_000L)
                        delay(step)
                        left -= step
                    }
                }
            }

            // Flag chapters that downloaded as login/limit pages, duplicates or cut-off text.
            if (sessionDownloadedCount > 0) {
                val report = com.example.util.ChapterQualityChecker.check(repository.getChapters(currentBookState.id))
                if (report.issues.isNotEmpty()) {
                    addLog("Quality check: ${report.issues.size} suspicious chapter(s) " +
                        report.issues.take(8).joinToString { "#${it.chapter.chapterNumber} (${it.problem.label})" } +
                        ". Re-download them from the novel's menu → Check Chapter Quality.")
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
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                com.example.util.WebViewFactory.destroySafely(webView)
            }
        }
    }

    /** Why the solver window is open, shown at its top. */
    var captchaReason by mutableStateOf("")

    private suspend fun handleCaptchaChallenge(
        url: String,
        reason: String = "The site is showing a security check. Solve it below, then tap Resume."
    ) {
        val pluginUa = (SourceManager.getSourceForUrl(url, pluginManager) as? com.example.data.plugin.PluginExecutionEngine)
            ?.config?.customUserAgent.orEmpty()
        captchaSourceKey = com.example.data.scraper.SourceKeys.keyFor(url)
        withContext(Dispatchers.Main) {
            captchaReason = reason
            captchaUserAgent = pluginUa.ifBlank { defaultUserAgent }
            captchaUrl = url
            ScrapeNotifications.checkNeeded(application, reason)
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
        // Persist the clearance cookie before the scraping WebView reloads the page.
        CookieManager.getInstance().flush()
        captchaSourceKey?.let { quota.clearChallenge(it) }
        ScrapeNotifications.cancelCheckNeeded(application)
        showCaptchaDialog = false
        captchaContinuation?.resume(Unit) {
            // Cancellation cleanups
        }
        captchaContinuation = null
    }

    suspend fun downloadSingleChapter(chapter: ChapterEntity): Result<Unit> = withContext(Dispatchers.IO) {
        downloadSingleChapterInner(chapter).onFailure { e ->
            // Nobody is watching a background/read-ahead download, so leave the site alone for a
            // while instead of sending more requests into a bot check.
            if (e is CloudflareException) {
                val key = com.example.data.scraper.SourceKeys.keyFor(chapter.url)
                quota.markChallenged(key)
                com.example.data.scraper.HumanPacing.onChallenge(application, key)
            }
        }
    }

    private suspend fun downloadSingleChapterInner(chapter: ChapterEntity): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (chapter.url.startsWith("local://")) {
                return@runCatching
            }
            val scraper = SourceManager.getSourceForUrl(chapter.url, pluginManager)
            val sourceKey = com.example.data.scraper.SourceKeys.keyFor(chapter.url)
            if (quota.remaining(sourceKey) <= 0) {
                throw IOException("Download limit for $sourceKey reached; try again later")
            }
            val webView = createFreshWebView()
            try {
                var usedBackup = false
                val rawContent = try {
                    scraper.scrapeChapterContent(webView, chapter.url) { false }
                } catch (e: Exception) {
                    if (e is CancellationException || !settings.sourceFallbackEnabled) throw e
                    val book = repository.getBook(chapter.bookId) ?: throw e
                    val alt = sourceFallback.fetchChapter(book, chapter.chapterNumber, webView) ?: throw e
                    usedBackup = true
                    alt.first to alt.second
                }
                var title = rawContent.first
                var cleanedBody = cleanScrapedBody(
                    if (usedBackup) com.example.util.GenericScraper else scraper, rawContent.second, aggressiveClean
                )

                val glossaries = repository.getGlossary(chapter.bookId)
                com.example.data.scraper.RawTranslation.localize(
                    application, chapter.bookId, chapter.url, title, cleanedBody, glossaries,
                    allowMobileData = !settings.translationRequireWifi
                ).let { (t, b) -> title = t; cleanedBody = b }
                if (glossaries.isNotEmpty()) {
                    cleanedBody = repository.applyGlossary(cleanedBody, glossaries)
                }

                val hash = com.example.util.ContentHash.of(cleanedBody)

                val updatedChapter = chapter.copy(
                    title = if (title.isNotBlank()) cleanTitleFor(chapter, title, scraper) else chapter.title,
                    content = cleanedBody,
                    hash = hash,
                    downloadedAt = System.currentTimeMillis()
                )

                repository.deletePolishedChapter(chapter.id)
                repository.deleteChapterRecap(chapter.id)
                repository.insertChapter(updatedChapter)
                if (!usedBackup) quota.record(sourceKey)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
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
                if (res.exceptionOrNull() is CloudflareException) break // site wants a check; stop here
                if (idx < pending.size - 1) {
                    val source = SourceManager.getSourceForUrl(ch.url, pluginManager)
                    val siteDelay = (source as? com.example.data.plugin.PluginExecutionEngine)?.config?.requestDelayMs ?: 0
                    val base = (if (siteDelay > 0) siteDelay.toLong() else settings.requestDelayMs.toLong()).coerceAtLeast(300L)
                    delay(com.example.data.scraper.HumanPacing.nextDelayMs(application, com.example.data.scraper.SourceKeys.keyFor(ch.url), base, idx + 1))
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

    /**
     * "Fix chapter order & numbering": reads the site's chapter list (no chapter text), puts the
     * saved chapters in the site's order, adds the chapters you don't have yet as empty entries,
     * and numbers everything 1, 2, 3… See [ChapterStructure].
     */
    fun fixChapterStructure(bookId: String, onComplete: (Boolean, String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val book = repository.getBook(bookId) ?: error("Novel not found.")
                if (book.url.startsWith("local://")) error("Imported novels have no site list to compare with.")
                val scraper = SourceManager.getSourceForUrl(book.url, pluginManager)
                val webView = createFreshWebView()
                val urls = try {
                    withTimeoutOrNull(tocTimeoutMs(scraper)) { scraper.scrapeChapterList(webView, book.url) } ?: emptyList()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        com.example.util.WebViewFactory.destroySafely(webView)
                    }
                }
                if (urls.isEmpty()) error("The site's chapter list didn't load. Open the novel in the browser once, then try again.")

                val toc = urls.map { ChapterStructure.TocItem(it, com.example.util.TocTitles.get(it).orEmpty()) }
                val all = repository.getAllChaptersIncludingHidden(bookId)
                val live = all.filter { !it.isDeleted }
                val skip = com.example.util.ChapterUrls.deletedFor(application, bookId) +
                    all.filter { it.isDeleted }.map { com.example.util.ChapterUrls.normalize(it.url) }
                val plan = ChapterStructure.plan(live, toc, skip)

                // Trashed chapters move out of the way so numbers never repeat.
                val top = (plan.numbers.values + plan.added.map { it.second }).maxOrNull() ?: 0
                all.filter { it.isDeleted }.forEachIndexed { i, c -> repository.updateChapterNumber(c.id, top + 1 + i) }
                plan.numbers.forEach { (id, n) -> repository.updateChapterNumber(id, n) }
                plan.titles.forEach { (id, t) -> repository.updateChapterTitle(id, t) }
                val added = plan.added.map { (t, n) ->
                    val chapId = scraper.parseChapterId(t.url) ?: "ch_$n"
                    ChapterEntity(
                        id = "${bookId}_$chapId",
                        bookId = bookId,
                        chapterId = chapId,
                        chapterNumber = n,
                        title = com.example.util.ChapterTitleCleaner.clean(t.title.ifBlank { "Chapter $n" }, book.title, chapterNumber = n),
                        url = t.url,
                        content = "",
                        hash = ""
                    )
                }.filter { repository.getChapter(it.id) == null }
                if (added.isNotEmpty()) repository.insertChapters(added)
                val total = repository.getChapters(bookId).size
                repository.getBook(bookId)?.let { repository.updateBook(it.copy(totalChapters = total, updatedAt = System.currentTimeMillis())) }

                buildString {
                    append("Chapters now follow the site's order (${urls.size} on the site).")
                    if (added.isNotEmpty()) append(" Added ${added.size} chapter${if (added.size == 1) "" else "s"} you didn't have (not downloaded yet).")
                    if (plan.extras > 0) append(" Kept ${plan.extras} page${if (plan.extras == 1) "" else "s"} the site doesn't list, in place.")
                }
            }
            withContext(Dispatchers.Main) {
                result.fold(onSuccess = { onComplete(true, it) }, onFailure = { onComplete(false, it.message ?: "Couldn't check the chapter list.") })
            }
        }
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
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                }

                if (chapterUrls.isEmpty()) {
                    throw java.io.IOException("the site returned no chapters (try again, or open it in the browser)")
                }
                // Hidden and trashed chapters count as existing; match by id too, since a site
                // can change domain while chapter ids stay the same.
                val known = repository.getAllChaptersIncludingHidden(bookId)
                val existingChapters = known.associateBy { com.example.util.ChapterUrls.normalize(it.url) }
                val deletedUrls = com.example.util.ChapterUrls.deletedFor(application, bookId)
                val existingIds = known.map { it.id }.toHashSet()
                var newCount = 0
                val chapterEntities = mutableListOf<ChapterEntity>()

                chapterUrls.forEachIndexed { index, chapterUrl ->
                    val chapNum = index + 1
                    val chapId = scraper.parseChapterId(chapterUrl) ?: "ch_$chapNum"
                    val fullChapId = "${bookId}_$chapId"
                    val existing = existingChapters[com.example.util.ChapterUrls.normalize(chapterUrl)]
                    if (existing != null || fullChapId in existingIds || com.example.util.ChapterUrls.normalize(chapterUrl) in deletedUrls) {
                        // Left untouched: re-writing it could overwrite text downloaded meanwhile.
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

                if (chapterEntities.isNotEmpty()) repository.insertChapters(chapterEntities)
                val total = repository.getChapters(bookId).size
                val updatedBook = (repository.getBook(bookId) ?: book).copy(
                    totalChapters = total,
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateBook(updatedBook)

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, "Table of contents updated! Found $newCount new chapter(s). Total: $total")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, "Failed to refresh table of contents: ${e.message}")
                }
            }
        }
    }

    /** Translates every stored chapter of [bookId] that is still raw Chinese (e.g. saved offline). */
    fun translateRawChapters(bookId: String, onComplete: (String) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val msg = com.example.data.scraper.RawTranslation.translateStoredChapters(
                application, repository, bookId, allowMobileData = !settings.translationRequireWifi
            )
            withContext(Dispatchers.Main) { onComplete(msg) }
        }
    }

    /**
     * Downloads [chapter] again and replaces its text: the shared step behind every "re-download"
     * action (single, selected, corrupted). Cleans, translates raw sources, applies the glossary,
     * and drops cached polish/recap for the old text. Returns the new title.
     */
    private suspend fun refetchChapter(chapter: ChapterEntity, webView: WebView): String {
        val scraper = SourceManager.getSourceForUrl(chapter.url, pluginManager)
        val rawContent = scraper.scrapeChapterContent(webView, chapter.url) { false }
        var title = rawContent.first
        var body = cleanScrapedBody(scraper, rawContent.second, aggressiveClean)
        val glossaries = repository.getGlossary(chapter.bookId)
        com.example.data.scraper.RawTranslation.localize(
            application, chapter.bookId, chapter.url, title, body, glossaries,
            allowMobileData = !settings.translationRequireWifi
        ).let { (t, b) -> title = t; body = b }
        if (glossaries.isNotEmpty()) body = repository.applyGlossary(body, glossaries)
        val cleanTitle = cleanTitleFor(chapter, title, scraper)
        repository.deletePolishedChapter(chapter.id)
        repository.deleteChapterRecap(chapter.id)
        repository.insertChapter(
            chapter.copy(
                title = cleanTitle,
                content = body,
                hash = com.example.util.ContentHash.of(body),
                downloadedAt = System.currentTimeMillis()
            )
        )
        quota.record(com.example.data.scraper.SourceKeys.keyFor(chapter.url))
        return cleanTitle
    }

    fun rescrapeSingleChapter(chapter: ChapterEntity, onComplete: (Boolean, String) -> Unit) {
        if (rescrapingChapterId != null) {
            onComplete(false, "Already rescraping another chapter.")
            return
        }
        rescrapingChapterId = chapter.id
        coroutineScope.launch(Dispatchers.IO) {
            val webView = createFreshWebView()
            val result = try {
                runCatching { refetchChapter(chapter, webView) }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { com.example.util.WebViewFactory.destroySafely(webView) }
            }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                rescrapingChapterId = null
                result.fold(
                    onSuccess = { onComplete(true, "Successfully rescraped chapter: $it") },
                    onFailure = { onComplete(false, "Error: ${it.message}") }
                )
            }
        }
    }

    fun rescrapeBatchChapters(chapters: List<ChapterEntity>, onComplete: (Boolean, String) -> Unit) {
        if (chapters.isEmpty()) {
            onComplete(false, "No chapters selected.")
            return
        }
        if (rescrapingChapterId != null) {
            onComplete(false, "Already rescraping another chapter.")
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (chapter in chapters) {
                withContext(Dispatchers.Main) { rescrapingChapterId = chapter.id }
                val webView = createFreshWebView()
                try {
                    refetchChapter(chapter, webView)
                    successCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { com.example.util.WebViewFactory.destroySafely(webView) }
                }
            }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                rescrapingChapterId = null
                onComplete(true, "Rescraped $successCount of ${chapters.size} chapters.")
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
                if (repository.getBook(bookId) == null) {
                    withContext(Dispatchers.Main) {
                        isRescrapingBookId = null
                        onComplete(false, "Novel not found.")
                    }
                    return@launch
                }
                // Login/limit/locked notices and bot-check pages saved as chapter text.
                val corrupted = repository.getChapters(bookId).filter {
                    it.content.isNotBlank() && com.example.util.ChapterQualityChecker.looksLikeErrorPage(it.content)
                }
                if (corrupted.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isRescrapingBookId = null
                        onComplete(true, "No corrupted or invalid chapters detected in this novel.")
                    }
                    return@launch
                }

                val webView = createFreshWebView()
                var successCount = 0
                try {
                    corrupted.forEachIndexed { index, chapter ->
                        try {
                            refetchChapter(chapter, webView)
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
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { com.example.util.WebViewFactory.destroySafely(webView) }
                }

                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
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

    private companion object {
        const val KEY_ACTIVE_URL = "scrape_active_session_url"
        const val KEY_ACTIVE_NAME = "scrape_active_session_name"
    }
}
