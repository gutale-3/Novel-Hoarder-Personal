package com.example.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.scraper.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ChapterUpdateWorker(
    val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "novel_chapter_update_worker"
        const val CHANNEL_ID = "chapter_updates_channel"
        const val NOTIFICATION_ID_BASE = 2000
        const val CHECK_NOW_WORK_NAME = "novel_chapter_update_check_now"
        /** Upper bound per book per background run, so one novel cannot hog the run. */
        const val MAX_AUTO_PER_RUN = 150

        /** Runs the update check once, right away, for the Updates tab's "Check now" button. */
        fun checkNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ChapterUpdateWorker>()
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                CHECK_NOW_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request
            )
        }

        fun schedulePeriodicUpdates(context: Context) {
            try {
                val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("enable_auto_check_updates", true)
                val workManager = androidx.work.WorkManager.getInstance(context)

                if (!isEnabled) {
                    workManager.cancelUniqueWork(WORK_NAME)
                    return
                }

                val intervalHours = prefs.getInt("update_check_interval_hours", 12).toLong().coerceIn(4L, 48L)
                val wifiOnly = prefs.getBoolean("update_check_wifi_only", true)

                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED
                    )
                    .build()

                val updateRequest = androidx.work.PeriodicWorkRequestBuilder<ChapterUpdateWorker>(
                    intervalHours, java.util.concurrent.TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    updateRequest
                )
            } catch (t: Throwable) {
                android.util.Log.e("ChapterUpdateWorker", "WorkManager schedule failed gracefully", t)
            }
        }
    }

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enable_auto_check_updates", true)
        if (!isEnabled) {
            return Result.success()
        }

        val database = AppDatabase.getDatabase(context)
        val bookDao = database.bookDao()

        val books = withContext(Dispatchers.IO) {
            bookDao.getAllBooks()
        }

        if (books.isEmpty()) return Result.success()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chapter Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when new novel chapters are released"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val updatesStore = ChapterUpdatesStore.get(context)
        val quota = com.example.data.scraper.DownloadQuota.get(context)
        for ((index, book) in books.withIndex()) {
            if (book.url.startsWith("local://")) continue
            try {
                val scraper = SourceManager.getSourceForUrl(book.url)
                val bookUrl = book.url

                var checkError: String? = null
                val chapterUrls = withContext(Dispatchers.Main) {
                    val webView = com.example.util.WebViewFactory.create(context, null)
                    var urls = emptyList<String>()
                    try {
                        urls = scraper.scrapeChapterList(webView, bookUrl)
                    } catch (e: com.example.util.CloudflareException) {
                        checkError = "Blocked by Cloudflare — open the book once to pass the check"
                    } catch (e: Exception) {
                        e.printStackTrace()
                        checkError = e.message ?: "Could not load the chapter list"
                    } finally {
                        try { webView.destroy() } catch (_: Exception) {}
                    }
                    urls
                }
                if (chapterUrls.isEmpty() && !book.url.startsWith("local://")) {
                    updatesStore.record(
                        ChapterUpdatesStore.Entry(book.id, 0, 0, System.currentTimeMillis(),
                            checkError ?: "Chapter list came back empty")
                    )
                }

                if (chapterUrls.isNotEmpty()) {
                    val localChapters = withContext(Dispatchers.IO) {
                        bookDao.getChaptersForBook(book.id)
                    }
                    val maxChapNum = localChapters.maxOfOrNull { it.chapterNumber } ?: 0

                    var newCount = 0
                    var nextChapterStart = 1
                    if (maxChapNum > 0) {
                        val sortedLocals = localChapters.sortedByDescending { it.chapterNumber }
                        var matchedIdx = -1
                        var matchedNum = maxChapNum
                        for (loc in sortedLocals) {
                            val idx = chapterUrls.indexOf(loc.url)
                            if (idx >= 0) {
                                matchedIdx = idx
                                matchedNum = loc.chapterNumber
                                break
                            }
                        }
                        val startIndex = if (matchedIdx >= 0) matchedIdx + 1 else maxChapNum.coerceAtMost(chapterUrls.size)
                        nextChapterStart = if (matchedIdx >= 0) matchedNum + 1 else maxChapNum + 1
                        newCount = (chapterUrls.size - startIndex).coerceAtLeast(0)
                    } else {
                        newCount = chapterUrls.size
                        nextChapterStart = 1
                    }

                    var downloaded = 0
                    if (newCount > 0 && quota.isAutoDownloadEnabled(book.id, book.url)) {
                        downloaded = autoDownload(book, scraper, chapterUrls.takeLast(newCount), nextChapterStart)
                    }
                    val stillNew = (newCount - downloaded).coerceAtLeast(0)
                    if (stillNew > 0) {
                        updatesStore.record(
                            ChapterUpdatesStore.Entry(
                                book.id, stillNew, nextChapterStart + downloaded, System.currentTimeMillis()
                            )
                        )
                    } else {
                        updatesStore.clear(book.id)
                    }

                    if (newCount > 0) {
                        val autoGrab = downloaded > 0
                        val openAppIntent = Intent(context, com.example.MainActivity::class.java)
                        val openAppPendingIntent = PendingIntent.getActivity(
                            context, index, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_popup_sync)
                            .setContentTitle("New Chapters for ${book.title}")
                            .setContentText(
                                when {
                                    autoGrab && stillNew == 0 -> "Downloaded $downloaded new chapters (from Ch. $nextChapterStart) — ready offline"
                                    autoGrab -> "Downloaded $downloaded of $newCount new chapters; the rest wait for the site's limit"
                                    else -> "Found $newCount new chapters (from Ch. $nextChapterStart) available to download!"
                                }
                            )
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setContentIntent(openAppPendingIntent)
                            .setAutoCancel(true)
                            .build()

                        notificationManager.notify(NOTIFICATION_ID_BASE + index, notification)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(1000)
        }

        return Result.success()
    }

    /**
     * Adds the new chapters to the book and downloads as many as the site's limit allows right
     * now. Whatever is left stays listed in the Updates tab and is picked up on the next run.
     * @return number of chapters downloaded.
     */
    private suspend fun autoDownload(
        book: com.example.data.local.BookEntity,
        scraper: com.example.data.scraper.NovelSource,
        newUrls: List<String>,
        firstNumber: Int
    ): Int {
        val container = com.example.NovelHoarderApp.getContainer(context.applicationContext as android.app.Application)
        val repository = container.repository
        val scraping = container.scraping
        val quota = com.example.data.scraper.DownloadQuota.get(context)
        val sourceKey = com.example.data.scraper.SourceKeys.keyFor(book.url)

        val placeholders = newUrls.mapIndexed { i, url ->
            val number = firstNumber + i
            val chapId = scraper.parseChapterId(url) ?: "ch_$number"
            com.example.data.local.ChapterEntity(
                id = "${book.id}_$chapId",
                bookId = book.id,
                chapterId = chapId,
                chapterNumber = number,
                title = "Chapter $number",
                url = url,
                content = "",
                hash = ""
            )
        }.filter { repository.getChapter(it.id) == null }
        if (placeholders.isNotEmpty()) {
            repository.insertChapters(placeholders)
            repository.updateBook(book.copy(totalChapters = repository.getChapters(book.id).size, updatedAt = System.currentTimeMillis()))
        }

        val budget = minOf(newUrls.size, quota.remaining(sourceKey), MAX_AUTO_PER_RUN)
        if (budget <= 0) return 0
        val delayMs = context.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
            .getInt("request_delay_ms", 3000).toLong().coerceAtLeast(1000L)

        var downloaded = 0
        var failuresInARow = 0
        for ((i, chapter) in repository.getPendingChapters(book.id, budget).withIndex()) {
            val result = scraping.downloadSingleChapter(chapter)
            if (result.isSuccess) {
                downloaded++
                failuresInARow = 0
            } else if (result.exceptionOrNull() is com.example.util.CloudflareException || ++failuresInARow >= 3) {
                break // a bot check or repeated refusals: leave the site alone until next run
            }
            delay(com.example.data.scraper.HumanPacing.nextDelayMs(context, sourceKey, delayMs, i + 1))
        }
        return downloaded
    }
}
