package com.example.background

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit

/**
 * Copies downloaded chapter text between phones in the background, so it carries on after the
 * app is closed and resumes where it stopped if interrupted (see CloudSync.syncText).
 */
class TextSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = NovelHoarderApp.getContainer(applicationContext as Application).cloudSync
        if (!sync.isEnabled) return Result.success()
        return if (sync.syncText().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val NOW = "text_sync_now"
        private const val PERIODIC = "text_sync_periodic"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<TextSyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // APPEND_OR_REPLACE: a run already going finishes, then one more picks up new changes.
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<TextSyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
