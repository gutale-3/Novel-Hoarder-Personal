package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.ai.PiperModelManager
import com.example.data.ai.PiperVoiceCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val ACTION_START = "com.example.action.START_VOICE_DOWNLOAD"
        const val EXTRA_VOICE_ID = "voice_id"
        const val ACTION_PROGRESS = "com.example.action.VOICE_DOWNLOAD_PROGRESS"
        const val ACTION_SUCCESS = "com.example.action.VOICE_DOWNLOAD_SUCCESS"
        const val ACTION_FAILURE = "com.example.action.VOICE_DOWNLOAD_FAILURE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR = "error"

        const val CHANNEL_ID = "voice_download_channel"
        const val NOTIFICATION_ID = 1002

        fun start(context: Context, voiceId: String) {
            val intent = Intent(context, VoiceDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VOICE_ID, voiceId)
            }
            ContextCompat.startForegroundService(context, intent)
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
                "Voice Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of downloading premium high-quality voice models"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val voiceId = intent.getStringExtra(EXTRA_VOICE_ID)
            if (voiceId != null) {
                try {
                    val voice = PiperVoiceCatalog.getVoiceById(voiceId)
                    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(com.example.R.mipmap.ic_launcher)
                        .setContentTitle("Downloading Voice")
                        .setContentText("Downloading ${voice.name}...")
                        .setProgress(100, 0, true)
                        .setOngoing(true)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            builder.build(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, builder.build())
                    }

                    serviceScope.launch {
                        val modelManager = PiperModelManager(applicationContext)
                        val result = modelManager.downloadAndExtractVoice(voice) { progress ->
                            builder.setProgress(100, progress, false)
                            builder.setContentText("Downloading ${voice.name}... $progress%")
                            notificationManager.notify(NOTIFICATION_ID, builder.build())

                            val progressIntent = Intent(ACTION_PROGRESS).apply {
                                putExtra(EXTRA_VOICE_ID, voiceId)
                                putExtra(EXTRA_PROGRESS, progress)
                                `package` = packageName
                            }
                            sendBroadcast(progressIntent)
                        }

                        if (result.isSuccess) {
                            val successIntent = Intent(ACTION_SUCCESS).apply {
                                putExtra(EXTRA_VOICE_ID, voiceId)
                                `package` = packageName
                            }
                            sendBroadcast(successIntent)

                            builder.setContentText("Download complete: ${voice.name}")
                                .setProgress(0, 0, false)
                                .setOngoing(false)
                            notificationManager.notify(NOTIFICATION_ID, builder.build())

                            delay(1000)
                            stopForeground(true)
                            stopSelf()
                        } else {
                            val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                            val failureIntent = Intent(ACTION_FAILURE).apply {
                                putExtra(EXTRA_VOICE_ID, voiceId)
                                putExtra(EXTRA_ERROR, errorMsg)
                                `package` = packageName
                            }
                            sendBroadcast(failureIntent)

                            builder.setContentText("Download failed: $errorMsg")
                                .setProgress(0, 0, false)
                                .setOngoing(false)
                            notificationManager.notify(NOTIFICATION_ID, builder.build())

                            delay(2000)
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                } catch (e: Exception) {
                    val failureIntent = Intent(ACTION_FAILURE).apply {
                        putExtra(EXTRA_VOICE_ID, voiceId)
                        putExtra(EXTRA_ERROR, e.message ?: "Voice not found")
                        `package` = packageName
                    }
                    sendBroadcast(failureIntent)
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
