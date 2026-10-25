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
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.NovelHoarderApp

class TtsPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: TtsPlaybackService? = null

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

        fun updateWakeLock(isPlaying: Boolean) {
            instance?.let { s ->
                if (isPlaying) {
                    s.acquireWakeLock()
                } else {
                    s.releaseWakeLock()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovelHoarder:TtsPlaybackService")?.apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.let {
            if (!it.isHeld) {
                try {
                    it.acquire(10 * 60 * 60 * 1000L) // Safety timeout: 10 hours max
                } catch (e: Exception) {
                    android.util.Log.w("TtsPlaybackService", "Failed to acquire wake lock: ${e.message}")
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
            if (tts.ttsIsPlaying) {
                acquireWakeLock()
            } else {
                releaseWakeLock()
            }
        } catch (e: Exception) {
            android.util.Log.w("TtsPlaybackService", "startForeground failed gracefully: ${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app away from Recents keeps the narration going, like other audio apps;
        // the notification's Stop ends it. Only an idle player is cleaned up.
        try {
            val tts = NovelHoarderApp.getContainer(application).tts
            if (!tts.ttsIsPlaying) {
                tts.dismissTtsNotification()
                stopSelf()
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
