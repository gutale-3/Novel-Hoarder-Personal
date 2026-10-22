package com.example.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.plugin.SourceHealthChecker
import java.util.concurrent.TimeUnit

/**
 * Once a week, tests every enabled site that has a novel in the library and notifies when one
 * that used to work has broken, so it can be fixed before the next big download.
 */
class SiteHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as android.app.Application
        val container = com.example.NovelHoarderApp.getContainer(app)
        val pluginManager = container.pluginManager
        val checker = SourceHealthChecker(app, pluginManager)
        val libraryUrls = container.repository.getAllBooks().filter { !it.isDeleted }.map { it.url }
        val before = checker.lastResults()

        val newlyBroken = mutableListOf<String>()
        for (plugin in pluginManager.plugins.value.filter { it.isEnabled }) {
            if (isStopped) break
            if (libraryUrls.none { pluginManager.matches(plugin, it) }) continue
            val result = runCatching { checker.check(plugin, libraryUrls) }.getOrNull() ?: continue
            if (result.status == SourceHealthChecker.Status.BROKEN &&
                before[plugin.id]?.status != SourceHealthChecker.Status.BROKEN
            ) newlyBroken += "${plugin.name} (${result.stage})"
        }
        if (newlyBroken.isNotEmpty()) notifyBroken(newlyBroken)
        return Result.success()
    }

    private fun notifyBroken(sites: List<String>) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Site health", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "A site your novels come from stopped working" }
            )
        }
        val intent = Intent(applicationContext, com.example.MainActivity::class.java)
            .putExtra("open_route", "plugins")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            applicationContext, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = sites.joinToString(", ")
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (sites.size == 1) "A site stopped working" else "${sites.size} sites stopped working")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nTap to see the details or update the site plugins."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val WORK_NAME = "site_health_weekly"
        private const val CHANNEL = "site_health"
        private const val NOTIFICATION_ID = 4343

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SiteHealthWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
