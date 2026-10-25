package com.example.background

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.NovelHoarderApp

/** Polishes the next chapters of a novel ahead of where the reader is, in the background. */
class PolishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK) ?: return Result.success()
        val count = inputData.getInt(KEY_COUNT, 20)
        val container = NovelHoarderApp.getContainer(applicationContext as Application)
        val repository = container.repository
        val polisher = container.polisher
        val prefs = applicationContext.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
        val currentId = prefs.getString("progress_chapter_$bookId", null)

        val chapters = repository.getChapters(bookId)
        val start = chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val deadline = System.currentTimeMillis() + 8 * 60 * 1000L
        var done = 0
        var message = ""
        for (ch in chapters.drop(start)) {
            if (done >= count || isStopped || System.currentTimeMillis() > deadline) break
            if (ch.content.isBlank() || repository.getPolishedChapter(ch.id)?.let { polisher.isCurrent(it, ch) } == true) continue
            val err = polisher.polish(ch)
            if (err != null) { message = err; break }
            done++
        }
        prefs(applicationContext).edit()
            .putString("last_$bookId", "Polished $done chapter${if (done == 1) "" else "s"}" + if (message.isNotEmpty()) " — $message" else "")
            .apply()
        // Ran out of time with chapters left: carry on in a new run.
        if (done < count && message.isEmpty() && System.currentTimeMillis() > deadline) {
            enqueue(applicationContext, bookId, count - done)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_BOOK = "book"
        private const val KEY_COUNT = "count"

        fun prefs(context: Context) = context.getSharedPreferences("chapter_polish", Context.MODE_PRIVATE)
        fun lastResult(context: Context, bookId: String) = prefs(context).getString("last_$bookId", null)

        fun enqueue(context: Context, bookId: String, count: Int) {
            val request = OneTimeWorkRequestBuilder<PolishWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_COUNT to count))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("polish_$bookId", ExistingWorkPolicy.REPLACE, request)
        }

        fun workName(bookId: String) = "polish_$bookId"
    }
}
