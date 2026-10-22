package com.example.background

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.NovelHoarderApp
import com.example.data.scraper.DownloadQuota
import com.example.data.scraper.HumanPacing
import com.example.data.scraper.SourceKeys
import com.example.util.CloudflareException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Novels waiting for their remaining chapters. The worker below takes turns between them, a
 * handful of chapters each, inside every site's limits, so a queue of five novels finishes
 * overnight without one of them using up Tomato's daily allowance.
 */
class DownloadQueue private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("download_queue", Context.MODE_PRIVATE)
    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<String>> = _books.asStateFlow()

    private fun load(): List<String> = runCatching {
        JSONArray(prefs.getString(KEY_BOOKS, "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY_BOOKS, JSONArray(list).toString()).apply()
        _books.value = list
    }

    fun add(bookId: String) { if (bookId !in _books.value) save(_books.value + bookId) }
    fun remove(bookId: String) = save(_books.value - bookId)
    fun contains(bookId: String) = bookId in _books.value

    var lastRunSummary: String
        get() = prefs.getString(KEY_SUMMARY, "").orEmpty()
        set(v) { prefs.edit().putString(KEY_SUMMARY, v).putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply() }
    val lastRunAt: Long get() = prefs.getLong(KEY_LAST_RUN, 0L)

    companion object {
        private const val KEY_BOOKS = "books"
        private const val KEY_SUMMARY = "summary"
        private const val KEY_LAST_RUN = "last_run"
        const val WORK_PERIODIC = "download_queue_periodic"
        const val WORK_NOW = "download_queue_now"

        @Volatile private var instance: DownloadQueue? = null
        fun get(context: Context): DownloadQueue =
            instance ?: synchronized(this) { instance ?: DownloadQueue(context).also { instance = it } }

        private fun constraints(context: Context): Constraints {
            val wifiOnly = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
                .getBoolean("update_check_wifi_only", true)
            return Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        }

        /** Hourly background pass while anything is queued. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (get(context).books.value.isEmpty()) { wm.cancelUniqueWork(WORK_PERIODIC); return }
            val req = PeriodicWorkRequestBuilder<DownloadQueueWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(context)).build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}

class DownloadQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val queue = DownloadQueue.get(applicationContext)
        val container = NovelHoarderApp.getContainer(applicationContext as Application)
        val repository = container.repository
        val scraping = container.scraping
        val quota = DownloadQuota.get(applicationContext)
        val baseDelay = applicationContext.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
            .getInt("request_delay_ms", 3000).toLong().coerceAtLeast(1000L)

        // WorkManager stops a worker after ~10 minutes; stay well inside that and resume next run.
        val deadline = System.currentTimeMillis() + 8 * 60 * 1000L
        val blockedSites = HashSet<String>()
        var downloaded = 0
        var index = 0
        // The foreground download fetches in order too; running both doubles the site's load.
        if (scraping.isScraping) return Result.success()
        // A chapter that keeps failing (removed from the site, broken page) is set aside after
        // three tries so it can't block the rest of the novel forever.
        val failPrefs = applicationContext.getSharedPreferences("download_queue_fails", Context.MODE_PRIVATE)
        fun fails(id: String) = failPrefs.getInt(id, 0)

        while (System.currentTimeMillis() < deadline) {
            val active = queue.books.value.filter { id ->
                val book = repository.getBook(id)
                book != null && SourceKeys.keyFor(book.url) !in blockedSites
            }
            if (active.isEmpty()) break
            var progressThisRound = false
            for (bookId in active) {
                if (System.currentTimeMillis() >= deadline) break
                val book = repository.getBook(bookId) ?: continue
                val key = SourceKeys.keyFor(book.url)
                if (key in blockedSites) continue
                val pending = repository.getPendingVisibleChapters(bookId, 500)
                    .filter { fails(it.id) < MAX_FAILS }.take(PER_TURN)
                if (pending.isEmpty()) { queue.remove(bookId); continue } // finished (or only failures left)
                for (ch in pending) {
                    if (isStopped || scraping.isScraping) break
                    if (quota.remaining(key) <= 0) { blockedSites += key; break }
                    val r = scraping.downloadSingleChapter(ch)
                    if (r.isSuccess) {
                        downloaded++; progressThisRound = true
                        failPrefs.edit().remove(ch.id).apply()
                    } else if (r.exceptionOrNull() is CloudflareException) {
                        blockedSites += key; break // needs the user; other sites carry on
                    } else {
                        failPrefs.edit().putInt(ch.id, fails(ch.id) + 1).apply()
                        progressThisRound = true // the next try moves past it
                    }
                    delay(HumanPacing.nextDelayMs(applicationContext, key, baseDelay, ++index))
                }
            }
            if (!progressThisRound) break
        }

        val left = queue.books.value.sumOf { repository.getPendingVisibleCount(it) }
        queue.lastRunSummary = "Downloaded $downloaded chapters; $left still queued" +
            if (blockedSites.isNotEmpty()) " (waiting on: ${blockedSites.joinToString()})" else ""
        DownloadQueue.schedule(applicationContext)
        return Result.success()
    }

    private companion object {
        const val PER_TURN = 5
        const val MAX_FAILS = 3
    }
}
