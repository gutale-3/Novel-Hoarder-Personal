/**
 * Keeps the process alive while text-to-speech audio is playing.
 * The notification itself is built by TtsPlaybackManager so the two never disagree.
 */
package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.NovelHoarderApp

class TtsPlaybackService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TtsPlaybackService::class.java)
                )
            } catch (e: Exception) {
                // Background start restrictions — playback still works, just without the FGS.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TtsPlaybackService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tts = NovelHoarderApp.getContainer(application).tts
        val notification = tts.buildTtsNotification()

        if (notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
        } catch (e: Exception) {
            android.util.Log.w("TtsPlaybackService", "startForeground failed gracefully: ${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
