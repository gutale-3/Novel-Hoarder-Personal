package com.example.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Posts the one grouped "new chapters" notification, silent unless the user asked for sound. */
object ChapterAlerts {
    private const val QUIET_CHANNEL = "chapter_updates_quiet"
    private const val SOUND_CHANNEL = "chapter_updates_sound"
    private const val OLD_CHANNEL = "chapter_updates_channel"
    const val NOTIFICATION_ID = 2000
    private const val DIGEST_WORK = "chapter_digest_daily"

    fun post(context: Context, lines: List<Pair<String, Int>>) {
        if (lines.isEmpty()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = NotificationPrefs.get(context).chapterSound
        val channel = if (sound) SOUND_CHANNEL else QUIET_CHANNEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) } // the old one made a sound
            nm.createNotificationChannel(
                NotificationChannel(
                    channel,
                    if (sound) "New chapters (with sound)" else "New chapters",
                    if (sound) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
                ).apply { description = "One summary of novels with new chapters" }
            )
        }
        val intent = Intent(context, com.example.MainActivity::class.java)
            .putExtra("open_route", "updates")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val texts = lines.map { (title, n) -> "$title — $n new" }
        val style = NotificationCompat.InboxStyle()
        texts.take(6).forEach { style.addLine(it) }
        if (texts.size > 6) style.setSummaryText("+${texts.size - 6} more")
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(if (lines.size == 1) "New chapters: ${lines[0].first}" else "New chapters in ${lines.size} novels")
            .setContentText(texts.joinToString(" · "))
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setSilent(!sound)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** (Re)schedules the daily summary at the chosen hour, or cancels it. */
    fun scheduleDigest(context: Context) {
        val wm = WorkManager.getInstance(context)
        val hour = NotificationPrefs.get(context).digestHour
        if (hour !in 0..23) { wm.cancelUniqueWork(DIGEST_WORK); return }
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = PeriodicWorkRequestBuilder<ChapterDigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(DIGEST_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

/** The once-a-day summary of everything waiting in the Updates tab. */
class ChapterDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = NotificationPrefs.get(applicationContext)
        if (!prefs.newChapters || prefs.digestHour < 0) return Result.success()
        val repo = com.example.NovelHoarderApp.getContainer(applicationContext as android.app.Application).repository
        val lines = ChapterUpdatesStore.get(applicationContext).entries.value.values
            .filter { it.newCount > 0 && !prefs.isMuted(it.bookId) }
            .mapNotNull { e -> repo.getBook(e.bookId)?.let { it.title to e.newCount } }
        ChapterAlerts.post(applicationContext, lines)
        return Result.success()
    }
}
