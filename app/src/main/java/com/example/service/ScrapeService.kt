package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

class ScrapeService : Service() {

    private lateinit var notificationManager: NotificationManager

    companion object {
        const val ACTION_START = "com.example.action.START_SCRAPING"
        const val ACTION_UPDATE = "com.example.action.UPDATE_SCRAPING"
        const val ACTION_STOP = "com.example.action.STOP_SCRAPING"

        const val EXTRA_BOOK_NAME = "book_name"
        const val EXTRA_TOTAL_CHAPTERS = "total_chapters"
        const val EXTRA_CURRENT_CHAPTER = "current_chapter"
        const val EXTRA_STATUS = "status"

        const val CHANNEL_ID = "scrape_service_channel"
        const val NOTIFICATION_ID = 1003

        fun start(context: Context, bookName: String, totalChapters: Int) {
            val intent = Intent(context, ScrapeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BOOK_NAME, bookName)
                putExtra(EXTRA_TOTAL_CHAPTERS, totalChapters)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, currentChapter: Int, status: String) {
            val intent = Intent(context, ScrapeService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_CURRENT_CHAPTER, currentChapter)
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScrapeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Novel Scraper Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of background novel scraping"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private var bookName: String = "Novel"
    private var totalChapters: Int = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                bookName = intent.getStringExtra(EXTRA_BOOK_NAME) ?: "Novel"
                totalChapters = intent.getIntExtra(EXTRA_TOTAL_CHAPTERS, 0)
                
                val notification = buildNotification(0, "Starting...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_UPDATE -> {
                val currentChapter = intent.getIntExtra(EXTRA_CURRENT_CHAPTER, 0)
                val status = intent.getStringExtra(EXTRA_STATUS) ?: ""
                val notification = buildNotification(currentChapter, status)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(currentChapter: Int, status: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Scraping: $bookName"
        val contentText = if (totalChapters > 0) {
            "Chapter $currentChapter of $totalChapters ($status)"
        } else {
            status
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (totalChapters > 0) {
            builder.setProgress(totalChapters, currentChapter, false)
        } else {
            builder.setProgress(100, 0, true)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
