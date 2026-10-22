package com.example.viewmodel

/** The "download needs you" heads-up notification (split out of ScrapingManager). */
internal object ScrapeNotifications {
    private const val CHECK_CHANNEL = "download_needs_you"
    private const val CHECK_NOTIFICATION_ID = 4242

    /** Heads-up notification so a check is seen even when the phone is in a pocket or another app is open. */
    fun checkNeeded(application: android.app.Application, reason: String) {
        if (!com.example.background.NotificationPrefs.get(application).downloadNeedsYou) return
        val nm = application.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHECK_CHANNEL, "Download needs you", android.app.NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A download is paused until you pass a check or look at a page" }
            )
        }
        val intent = android.content.Intent(application, com.example.MainActivity::class.java)
            .putExtra("open_route", "scrape")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = android.app.PendingIntent.getActivity(
            application, CHECK_NOTIFICATION_ID, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = androidx.core.app.NotificationCompat.Builder(application, CHECK_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Download paused — tap to continue")
            .setContentText(reason)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(CHECK_NOTIFICATION_ID, n) }
    }

    fun cancelCheckNeeded(application: android.app.Application) {
        androidx.core.app.NotificationManagerCompat.from(application).cancel(CHECK_NOTIFICATION_ID)
    }
}
