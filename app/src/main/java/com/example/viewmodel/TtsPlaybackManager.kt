/**
 * Manages TTS audio playback state, foreground service lifecycle, audio focus,
 * notification media controls, system TTS chunking/sliding window, sleep timer with volume fade,
 * and pre-warming/priming of neural voice models.
 */
package com.example.viewmodel

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.data.ai.PiperVoice
import com.example.data.ai.PiperVoiceCatalog
import com.example.data.ai.SherpaOnnxTtsEngine
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.repository.NovelRepository
import com.example.service.TtsPlaybackService
import com.example.util.AudioFocusHelper
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

class TtsPlaybackManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val settings: SettingsManager,
    private val scraping: ScrapingManager,
    private val coroutineScope: CoroutineScope
) {
    private val prefs = application.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)

    var onSpeakStarted: (() -> Unit)? = null

    var focusModeEnabled by mutableStateOf(false)
    var ttsAutoScrollEnabled by mutableStateOf(true)
    var ttsTotalParagraphs by mutableStateOf(0)

    // --- Resumable session state & progress persistence ---
    var hasResumableSession by mutableStateOf(false)
    var resumeBookId by mutableStateOf("")
    var resumeChapterId by mutableStateOf("")
    var resumeParagraph by mutableStateOf(0)
    var resumeBookName by mutableStateOf("")
    var resumeChapterTitle by mutableStateOf("")

    // Text To Speech (TTS) System state
    private var tts: TextToSpeech? = null
    var isTtsReady by mutableStateOf(false)
    var ttsVoices by mutableStateOf<List<VoiceOption>>(emptyList())
    var selectedVoiceId by mutableStateOf<String>("")
    var previewingVoiceId by mutableStateOf<String?>(null)

    // TTS Playback state
    var ttsPlayingBook by mutableStateOf<BookEntity?>(null)
    var ttsPlayingChapter by mutableStateOf<ChapterEntity?>(null)
    var ttsIsPlaying by mutableStateOf(false)
    var ttsIsPaused by mutableStateOf(false)

    var ttsPitch by mutableStateOf(1.0f)
    var ttsSpeed by mutableStateOf(1.0f)
    var ttsActiveParagraphIndex by mutableStateOf<Int?>(-1)

    // Premium Piper / Kokoro offline voice properties
    val sherpaOnnxTtsEngine = SherpaOnnxTtsEngine(application)
    var premiumVoiceDownloading by mutableStateOf(false)
        private set
    var premiumVoiceDownloadProgress by mutableStateOf(0)
        private set
    var premiumVoiceDownloadError by mutableStateOf<String?>(null)
        private set

    var isPreparingVoice by mutableStateOf(false)
        private set

    // Sleep Timer state
    var sleepTimerMinutes by mutableStateOf(0) // 0 = Off, -1 = End of Chapter
    var sleepTimerRemainingSeconds by mutableStateOf<Int?>(null)
    private var sleepTimerJob: Job? = null

    // Audio Focus & Noisy Receiver
    private var pausedByFocusLoss = false
    private val audioFocusHelper = AudioFocusHelper(
        context = application,
        onFocusLost = { isTransient ->
            if (ttsIsPlaying) {
                if (isTransient) {
                    pausedByFocusLoss = true
                    pauseTts(abandonFocus = false)
                } else {
                    pausedByFocusLoss = false
                    pauseTts(abandonFocus = true)
                }
            }
        },
        onFocusGained = {
            if (pausedByFocusLoss && ttsIsPaused) {
                pausedByFocusLoss = false
                resumeTts()
            }
        }
    )

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (ttsIsPlaying) {
                    addLog("Headphones unplugged — pausing playback.")
                    pauseTts()
                }
            }
        }
    }

    // TTS Broadcast Receiver & Notification state
    private var isReceiverRegistered = false
    private val ttsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.ACTION_PLAY_PAUSE" -> {
                    if (ttsIsPlaying) pauseTts() else resumeTts()
                }
                "com.example.ACTION_PREV_CHAPTER" -> playPreviousChapterTts()
                "com.example.ACTION_NEXT_CHAPTER" -> playNextChapterTts()
                "com.example.ACTION_STOP_TTS" -> stopTts()
            }
        }
    }

    private val channelId = "tts_player_channel"
    private val notificationId = TtsPlaybackService.NOTIFICATION_ID
    private var mediaSession: MediaSessionCompat? = null

    // Cover bitmap caching for notifications
    private var cachedCoverBookId: String? = null
    private var cachedCoverBitmap: Bitmap? = null

    // System TTS sliding window queue
    private data class SpeechUnit(val paragraphIndex: Int, val text: String)
    private var speechUnits: List<SpeechUnit> = emptyList()
    private var nextUnitToQueue: Int = 0
    private var activeChapterKey: String = ""

    private var lastPersistMs: Long = 0L
    private var lastNotificationMs: Long = 0L

    private var settingsRestartJob: Job? = null

    private companion object {
        const val QUEUE_WINDOW_SIZE = 25
        const val QUEUE_REFILL_THRESHOLD = 8
        const val PERSIST_INTERVAL_MS = 3_000L
        const val NOTIFICATION_INTERVAL_MS = 1_000L
    }

    init {
        // Load preferences
        focusModeEnabled = prefs.getBoolean("focus_mode", false)
        ttsAutoScrollEnabled = prefs.getBoolean("tts_auto_scroll", true)

        registerTtsReceiver()
        cleanUpRemovedVoiceModels()
        warmUpLocalVoice()
    }

    fun unregister() {
        unregisterTtsReceiver()
        sleepTimerJob?.cancel()
        settingsRestartJob?.cancel()
        audioFocusHelper.abandonFocus()
        sherpaOnnxTtsEngine.stop()
        sherpaOnnxTtsEngine.shutdown()
        tts?.stop()
        tts?.shutdown()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        TtsPlaybackService.stop(application)
    }

    private fun isLocalNeuralVoice(voiceId: String): Boolean =
        PiperVoiceCatalog.ALL_VOICES.any { it.id == voiceId }

    fun warmUpLocalVoice() {
        if (!isLocalNeuralVoice(selectedVoiceId)) return
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        if (!isVoiceDownloaded(voice) || isPreparingVoice) return

        isPreparingVoice = true
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                sherpaOnnxTtsEngine.selectedVoiceId = selectedVoiceId
                sherpaOnnxTtsEngine.initOnnx()
            }
            withContext(Dispatchers.Main) { isPreparingVoice = false }
        }
    }

    fun primeChapterOpening(chapterContent: String) {
        if (!isLocalNeuralVoice(selectedVoiceId)) return
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        if (!isVoiceDownloaded(voice)) return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                sherpaOnnxTtsEngine.selectedVoiceId = selectedVoiceId
                sherpaOnnxTtsEngine.selectedSpeakerId = getSpeakerId(selectedVoiceId)
                sherpaOnnxTtsEngine.primeOpening(chapterContent, ttsSpeed)
            }
        }
    }

    fun cleanUpRemovedVoiceModels() {
        try {
            val voicesDir = File(application.filesDir, "piper_voices")
            if (!voicesDir.isDirectory) return
            val keep = PiperVoiceCatalog.ALL_VOICES.map { it.folderName }.toSet()
            voicesDir.listFiles()?.forEach { folder ->
                if (folder.isDirectory && folder.name !in keep) folder.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isVoiceDownloaded(voice: PiperVoice): Boolean {
        val oldId = sherpaOnnxTtsEngine.selectedVoiceId
        sherpaOnnxTtsEngine.selectedVoiceId = voice.id
        val res = sherpaOnnxTtsEngine.isModelDownloaded()
        sherpaOnnxTtsEngine.selectedVoiceId = oldId
        return res
    }

    fun voicesSharingFolder(voice: PiperVoice): List<PiperVoice> =
        sherpaOnnxTtsEngine.modelManager.voicesSharingFolder(voice)

    fun downloadPremiumVoice(voice: PiperVoice) {
        if (premiumVoiceDownloading) return
        sherpaOnnxTtsEngine.selectedVoiceId = voice.id
        premiumVoiceDownloading = true
        premiumVoiceDownloadError = null
        premiumVoiceDownloadProgress = 0
        coroutineScope.launch(Dispatchers.Main) {
            sherpaOnnxTtsEngine.downloadModel(
                onProgress = { progress ->
                    premiumVoiceDownloadProgress = progress
                },
                onSuccess = {
                    premiumVoiceDownloading = false
                    val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
                    setTtsVoice(VoiceOption(voice.id, "$prefix - ${voice.name}", Locale.US))
                    initTts()
                    addLog("$prefix Voice (${voice.name}) downloaded successfully.")
                },
                onFailure = { error ->
                    premiumVoiceDownloadError = error
                    premiumVoiceDownloading = false
                    val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
                    addLog("Error downloading $prefix Voice (${voice.name}): $error")
                }
            )
        }
    }

    fun importPremiumVoice(uri: Uri, voice: PiperVoice) {
        if (premiumVoiceDownloading) return
        sherpaOnnxTtsEngine.selectedVoiceId = voice.id
        premiumVoiceDownloading = true
        premiumVoiceDownloadError = null
        premiumVoiceDownloadProgress = 0
        coroutineScope.launch(Dispatchers.Main) {
            sherpaOnnxTtsEngine.importModel(
                uri = uri,
                onProgress = { progress ->
                    premiumVoiceDownloadProgress = progress
                },
                onSuccess = {
                    premiumVoiceDownloading = false
                    val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
                    setTtsVoice(VoiceOption(voice.id, "$prefix - ${voice.name}", Locale.US))
                    initTts()
                    addLog("$prefix Voice (${voice.name}) imported from archive.")
                },
                onFailure = { error ->
                    premiumVoiceDownloadError = error
                    premiumVoiceDownloading = false
                    val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
                    addLog("Error importing $prefix Voice (${voice.name}): $error")
                }
            )
        }
    }

    fun deletePremiumVoice(voice: PiperVoice) {
        val oldVoiceId = sherpaOnnxTtsEngine.selectedVoiceId
        sherpaOnnxTtsEngine.selectedVoiceId = voice.id
        if (sherpaOnnxTtsEngine.deleteVoicePack(voice)) {
            if (selectedVoiceId == voice.id || voicesSharingFolder(voice).any { it.id == selectedVoiceId }) {
                val defaultVoice = ttsVoices.find { it.id.startsWith("default_") } ?: ttsVoices.firstOrNull()
                defaultVoice?.let { setTtsVoice(it) }
            }
            initTts()
            val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
            addLog("$prefix Voice pack (${voice.name}) deleted.")
        }
        sherpaOnnxTtsEngine.selectedVoiceId = selectedVoiceId
    }

    fun saveSpeakerId(voiceId: String, speakerId: Int) {
        prefs.edit().putInt("tts_speaker_id_$voiceId", speakerId).apply()
        if (selectedVoiceId == voiceId && ttsIsPlaying) {
            val book = ttsPlayingBook
            val chapter = ttsPlayingChapter
            if (book != null && chapter != null) {
                speak(chapter.content, book, chapter, startFromParagraphIndex = ttsActiveParagraphIndex ?: 0)
            }
        }
    }

    fun getSpeakerId(voiceId: String): Int {
        return prefs.getInt("tts_speaker_id_$voiceId", 0)
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerMinutes = minutes
        if (minutes == 0) {
            sleepTimerRemainingSeconds = null
            return
        }
        if (minutes == -1) { // End of Chapter mode
            sleepTimerRemainingSeconds = null
            addLog("Sleep timer set to end of current chapter.")
            return
        }
        
        sleepTimerRemainingSeconds = minutes * 60
        sleepTimerJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive && (sleepTimerRemainingSeconds ?: 0) > 0) {
                delay(1000L)
                val remaining = (sleepTimerRemainingSeconds ?: 1) - 1
                withContext(Dispatchers.Main) {
                    sleepTimerRemainingSeconds = remaining
                }
            }
            if (isActive) {
                withContext(Dispatchers.Main) {
                    addLog("Sleep timer expired. Pausing playback.")
                    pauseTts()
                    sleepTimerMinutes = 0
                    sleepTimerRemainingSeconds = null
                }
            }
        }
    }

    fun initMediaSession() {
        if (mediaSession == null) {
            val context = application.applicationContext
            mediaSession = MediaSessionCompat(context, "NovelHoarderTTS").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { coroutineScope.launch(Dispatchers.Main) { resumeTts() } }
                    override fun onPause() { coroutineScope.launch(Dispatchers.Main) { pauseTts() } }
                    override fun onSkipToNext() { coroutineScope.launch(Dispatchers.Main) { playNextChapterTts() } }
                    override fun onSkipToPrevious() { coroutineScope.launch(Dispatchers.Main) { playPreviousChapterTts() } }
                    override fun onStop() { coroutineScope.launch(Dispatchers.Main) { stopTts() } }
                    override fun onSeekTo(pos: Long) {
                        coroutineScope.launch(Dispatchers.Main) {
                            val paraIndex = (pos / 1000L).toInt()
                            seekToParagraph(paraIndex)
                        }
                    }
                })
                isActive = true
            }
        }
    }

    fun updatePlaybackState() {
        initMediaSession()
        val state = if (ttsIsPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO

        val para = ttsActiveParagraphIndex ?: 0
        val total = ttsTotalParagraphs
        val currentPositionMs = if (para >= 0) para * 1000L else 0L

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, currentPositionMs, 1.0f)

        mediaSession?.setPlaybackState(stateBuilder.build())

        val book = ttsPlayingBook
        val chapter = ttsPlayingChapter

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book?.author ?: "Unknown Author")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, total * 1000L)

        if (total > 0 && para >= 0) {
            val progressSubtitle = "Paragraph ${para + 1} of $total"
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, progressSubtitle)
        }

        val coverBitmap = getDownsampledCover(book?.coverLocalPath, book?.id)
        if (coverBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
        }

        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun getDownsampledCover(coverPath: String?, bookId: String?): Bitmap? {
        if (coverPath.isNullOrEmpty() || bookId.isNullOrEmpty()) return null
        if (cachedCoverBookId == bookId && cachedCoverBitmap != null) {
            return cachedCoverBitmap
        }
        val file = File(coverPath)
        if (!file.exists()) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val reqSize = 256
            var sample = 1
            while (options.outWidth / sample > reqSize || options.outHeight / sample > reqSize) {
                sample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            cachedCoverBookId = bookId
            cachedCoverBitmap = bmp
            bmp
        } catch (e: Exception) {
            null
        }
    }

    fun buildTtsNotification(): Notification? {
        val context = application.applicationContext
        val book = ttsPlayingBook ?: return null
        val chapter = ttsPlayingChapter ?: return null

        updatePlaybackState()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows TTS playback status and buttons"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val playPauseIntent = Intent("com.example.ACTION_PLAY_PAUSE").apply { `package` = context.packageName }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            context, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent("com.example.ACTION_PREV_CHAPTER").apply { `package` = context.packageName }
        val prevPendingIntent = PendingIntent.getBroadcast(
            context, 4, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent("com.example.ACTION_NEXT_CHAPTER").apply { `package` = context.packageName }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, 2, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent("com.example.ACTION_STOP_TTS").apply { `package` = context.packageName }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 3, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_reader_book", book.id)
            putExtra("open_reader_chapter", chapter.id)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (ttsIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (ttsIsPlaying) "Pause" else "Play"

        val para = ttsActiveParagraphIndex ?: 0
        val total = ttsTotalParagraphs
        val subText = if (total > 0 && para >= 0) "Paragraph ${para + 1} of $total" else null

        val coverBmp = getDownsampledCover(book.coverLocalPath, book.id)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.example.R.mipmap.ic_launcher)
            .setContentTitle(chapter.title)
            .setContentText(book.title)
            .setSubText(subText)
            .setOngoing(ttsIsPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)

        if (coverBmp != null) {
            builder.setLargeIcon(coverBmp)
        }

        return builder.build()
    }

    fun showTtsNotification() {
        val notification = buildTtsNotification() ?: return
        val context = application.applicationContext
        if (!TtsPlaybackService.isRunning) {
            TtsPlaybackService.start(context)
            return
        }
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            // A missing POST_NOTIFICATIONS grant must never break playback.
        }
    }

    fun dismissTtsNotification() {
        val context = application.applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        TtsPlaybackService.stop(context)
    }

    fun registerTtsReceiver() {
        if (!isReceiverRegistered) {
            val context = application.applicationContext
            val filter = IntentFilter().apply {
                addAction("com.example.ACTION_PLAY_PAUSE")
                addAction("com.example.ACTION_PREV_CHAPTER")
                addAction("com.example.ACTION_NEXT_CHAPTER")
                addAction("com.example.ACTION_STOP_TTS")
            }
            ContextCompat.registerReceiver(
                context,
                ttsReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                noisyFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            isReceiverRegistered = true
        }
    }

    fun unregisterTtsReceiver() {
        if (isReceiverRegistered) {
            val context = application.applicationContext
            try { context.unregisterReceiver(ttsReceiver) } catch (e: Exception) {}
            try { context.unregisterReceiver(noisyReceiver) } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    fun initTts(onReady: (() -> Unit)? = null) {
        if (tts != null && isTtsReady) {
            onReady?.invoke()
            return
        }
        if (tts != null && !isTtsReady) {
            try { tts?.shutdown() } catch (_: Exception) {}
            tts = null
        }
        addLog("Initializing TextToSpeech engine...")
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                val availableVoices = mutableListOf<VoiceOption>()
                try {
                    val rawVoices = tts?.voices
                    if (rawVoices != null) {
                        val sysLocale = Locale.getDefault()
                        for (v in rawVoices) {
                            if (v.locale.language == "en" || v.locale.language == sysLocale.language) {
                                val region = v.locale.displayCountry.ifEmpty { "Default" }
                                val cleanName = v.name.substringAfterLast(".").substringBefore("#").replace("_", " ").uppercase()
                                val displayName = "${v.locale.displayLanguage} ($region) - Voice $cleanName"
                                availableVoices.add(VoiceOption(v.name, displayName, v.locale))
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("Error querying TTS voices: ${e.message}")
                }

                if (availableVoices.isEmpty()) {
                    availableVoices.add(VoiceOption("default_en_us", "English (United States) - Default", Locale.US))
                    availableVoices.add(VoiceOption("default_en_gb", "English (United Kingdom) - Default", Locale.UK))
                    availableVoices.add(VoiceOption("default_system", "System Default", Locale.getDefault()))
                }

                var finalVoices = availableVoices.distinctBy { it.id }
                val piperVoiceOptions = PiperVoiceCatalog.ALL_VOICES.map { voice ->
                    val prefix = if (voice.isKokoro) "Kokoro" else "Piper"
                    VoiceOption(voice.id, "$prefix - ${voice.name}", Locale.US)
                }
                finalVoices = piperVoiceOptions + finalVoices
                ttsVoices = finalVoices

                var savedVoice = prefs.getString("tts_selected_voice", "") ?: ""
                if (savedVoice == "premium_piper" || savedVoice.isEmpty()) {
                    savedVoice = PiperVoiceCatalog.AMY_LOW.id
                }
                if (savedVoice.isNotEmpty()) {
                    val matched = ttsVoices.find { it.id == savedVoice }
                    if (matched != null) {
                        setTtsVoice(matched)
                    }
                }

                val savedPitch = prefs.getFloat("tts_pitch", 1.0f)
                val savedSpeed = prefs.getFloat("tts_speed", 1.0f)
                ttsPitch = savedPitch
                ttsSpeed = savedSpeed

                addLog("TTS successfully initialized with ${ttsVoices.size} voices.")
                onReady?.invoke()
            } else {
                isTtsReady = false
                try { tts?.shutdown() } catch (_: Exception) {}
                tts = null
                addLog("ERROR: Failed to initialize TextToSpeech engine!")
            }
        }
    }

    fun stopVoicePreview() {
        sherpaOnnxTtsEngine.stop()
        tts?.stop()
        previewingVoiceId = null
    }

    fun playVoicePreview(voiceOption: VoiceOption) {
        val cleanName = voiceOption.name
            .replace(" (Natural)", "").replace("Piper - ", "")
            .replace("Kokoro - ", "").replace("System: ", "")
        val sampleText = "Hello, I am $cleanName."

        val isSherpa = voiceOption.id.startsWith("vits-piper-") || voiceOption.id.startsWith("kokoro-")
        val piperVoice = if (isSherpa) PiperVoiceCatalog.getVoiceById(voiceOption.id) else null
        val isDownloaded = piperVoice != null && isVoiceDownloaded(piperVoice)

        stopVoicePreview()

        if (isSherpa && isDownloaded) {
            coroutineScope.launch(Dispatchers.Main) {
                previewingVoiceId = voiceOption.id
                sherpaOnnxTtsEngine.selectedVoiceId = voiceOption.id
                sherpaOnnxTtsEngine.selectedSpeakerId = getSpeakerId(voiceOption.id)
                withContext(Dispatchers.IO) {
                    sherpaOnnxTtsEngine.initOnnx()
                }
                sherpaOnnxTtsEngine.speak(
                    text = sampleText,
                    speed = ttsSpeed,
                    pitch = ttsPitch,
                    onStart = {
                        coroutineScope.launch(Dispatchers.Main) {
                            previewingVoiceId = voiceOption.id
                        }
                    },
                    onDone = {
                        coroutineScope.launch(Dispatchers.Main) {
                            if (previewingVoiceId == voiceOption.id) {
                                previewingVoiceId = null
                            }
                        }
                    },
                    onError = { _ ->
                        coroutineScope.launch(Dispatchers.Main) {
                            if (previewingVoiceId == voiceOption.id) {
                                previewingVoiceId = null
                            }
                        }
                    }
                )
            }
        } else if (!isSherpa) {
            initTts {
                coroutineScope.launch(Dispatchers.Main) {
                    previewingVoiceId = voiceOption.id
                    val rawVoices = tts?.voices
                    val actualVoice = rawVoices?.find { it.name == voiceOption.id }
                    if (actualVoice != null) {
                        tts?.setVoice(actualVoice)
                    } else {
                        tts?.setLanguage(voiceOption.locale)
                    }
                    tts?.setPitch(ttsPitch)
                    tts?.setSpeechRate(ttsSpeed)

                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            coroutineScope.launch(Dispatchers.Main) {
                                previewingVoiceId = voiceOption.id
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            coroutineScope.launch(Dispatchers.Main) {
                                if (previewingVoiceId == voiceOption.id) {
                                    previewingVoiceId = null
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            coroutineScope.launch(Dispatchers.Main) {
                                if (previewingVoiceId == voiceOption.id) {
                                    previewingVoiceId = null
                                }
                            }
                        }
                    })

                    tts?.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, "preview_${voiceOption.id}")
                }
            }
        }
    }

    fun setTtsVoice(voiceOption: VoiceOption) {
        selectedVoiceId = voiceOption.id
        prefs.edit().putString("tts_selected_voice", voiceOption.id).apply()
        if (voiceOption.id.startsWith("vits-piper-") || voiceOption.id.startsWith("kokoro-")) {
            sherpaOnnxTtsEngine.selectedVoiceId = voiceOption.id
            sherpaOnnxTtsEngine.selectedSpeakerId = getSpeakerId(voiceOption.id)
            warmUpLocalVoice()
        } else if (voiceOption.id.startsWith("default_")) {
            tts?.setLanguage(voiceOption.locale)
        } else {
            try {
                val rawVoices = tts?.voices
                val actualVoice = rawVoices?.find { it.name == voiceOption.id }
                if (actualVoice != null) {
                    tts?.setVoice(actualVoice)
                } else {
                    tts?.setLanguage(voiceOption.locale)
                }
            } catch (e: Exception) {
                tts?.setLanguage(voiceOption.locale)
            }
        }

        if (ttsIsPlaying) {
            val book = ttsPlayingBook
            val chapter = ttsPlayingChapter
            if (book != null && chapter != null) {
                speak(chapter.content, book, chapter, startFromParagraphIndex = ttsActiveParagraphIndex ?: 0)
            }
        }
    }

    fun updateTtsSettings(pitch: Float, speed: Float) {
        ttsPitch = pitch
        ttsSpeed = speed
        prefs.edit()
            .putFloat("tts_pitch", pitch)
            .putFloat("tts_speed", speed)
            .apply()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(speed)

        settingsRestartJob?.cancel()
        settingsRestartJob = coroutineScope.launch(Dispatchers.Main) {
            delay(600)
            if (ttsIsPlaying) {
                val book = ttsPlayingBook
                val chapter = ttsPlayingChapter
                if (book != null && chapter != null) {
                    speak(chapter.content, book, chapter, startFromParagraphIndex = ttsActiveParagraphIndex ?: 0)
                }
            }
        }
    }

    fun toggleFocusMode() {
        focusModeEnabled = !focusModeEnabled
        prefs.edit().putBoolean("focus_mode", focusModeEnabled).apply()
    }

    fun toggleTtsAutoScroll() {
        ttsAutoScrollEnabled = !ttsAutoScrollEnabled
        prefs.edit().putBoolean("tts_auto_scroll", ttsAutoScrollEnabled).apply()
    }

    private fun buildSpeechUnits(paragraphs: List<String>, startParagraph: Int): List<SpeechUnit> {
        val maxLen = try {
            TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(500) - 100
        } catch (t: Throwable) {
            3800
        }

        val units = mutableListOf<SpeechUnit>()
        paragraphs.forEachIndexed { idx, paragraph ->
            if (idx < startParagraph) return@forEachIndexed
            if (paragraph.length <= maxLen) {
                units.add(SpeechUnit(idx, paragraph))
                return@forEachIndexed
            }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                if (remaining.length <= maxLen) {
                    units.add(SpeechUnit(idx, remaining))
                    break
                }
                val sentenceCut = remaining.lastIndexOf('.', maxLen)
                val spaceCut = remaining.lastIndexOf(' ', maxLen)
                val cut = when {
                    sentenceCut > maxLen / 2 -> sentenceCut + 1
                    spaceCut > maxLen / 2 -> spaceCut
                    else -> maxLen
                }
                units.add(SpeechUnit(idx, remaining.substring(0, cut).trim()))
                remaining = remaining.substring(cut).trim()
            }
        }
        return units
    }

    private fun queueNextUnits() {
        val end = minOf(nextUnitToQueue + QUEUE_WINDOW_SIZE, speechUnits.size)
        while (nextUnitToQueue < end) {
            val unit = speechUnits[nextUnitToQueue]
            val id = "unit_${activeChapterKey}#${nextUnitToQueue}#${unit.paragraphIndex}"
            tts?.speak(unit.text, TextToSpeech.QUEUE_ADD, null, id)
            nextUnitToQueue++
        }
    }

    private fun parseUnitIndex(utteranceId: String): Int? {
        if (!utteranceId.startsWith("unit_")) return null
        return utteranceId.split("#").getOrNull(1)?.toIntOrNull()
    }

    private fun parseParagraphIndex(utteranceId: String): Int? {
        if (!utteranceId.startsWith("unit_")) return null
        return utteranceId.split("#").getOrNull(2)?.toIntOrNull()
    }

    fun speak(text: String, book: BookEntity, chapter: ChapterEntity, startFromParagraphIndex: Int = -1) {
        onSpeakStarted?.invoke()

        if (!audioFocusHelper.requestFocus()) {
            addLog("Audio focus unavailable right now — starting playback anyway.")
        }

        // Save chapter progress & mark chapter as read
        coroutineScope.launch(Dispatchers.IO) {
            val updatedBook = book.copy(lastReadChapterId = chapter.id)
            repository.updateBook(updatedBook)
            repository.updateChapterReadStatus(chapter.id, true)
        }

        val isSherpa = selectedVoiceId.startsWith("vits-piper-") || selectedVoiceId.startsWith("kokoro-")
        val piperVoice = if (isSherpa) PiperVoiceCatalog.getVoiceById(selectedVoiceId) else null
        val isDownloaded = piperVoice != null && isVoiceDownloaded(piperVoice)

        if (isSherpa && isDownloaded) {
            coroutineScope.launch(Dispatchers.Main) {
                ttsPlayingBook = book
                ttsPlayingChapter = chapter
                ttsIsPlaying = true
                ttsIsPaused = false

                tts?.stop()

                val glossary = repository.getGlossary(book.id)
                val cleanText = repository.applyGlossary(text, glossary)
                val rawParagraphs = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                ttsTotalParagraphs = rawParagraphs.size

                if (startFromParagraphIndex < 0) {
                    ttsActiveParagraphIndex = -1
                } else {
                    ttsActiveParagraphIndex = startFromParagraphIndex
                }

                val textToSpeak = if (startFromParagraphIndex >= 0) {
                    rawParagraphs.drop(startFromParagraphIndex).joinToString("\n")
                } else {
                    chapter.title + "\n" + cleanText
                }

                sherpaOnnxTtsEngine.selectedVoiceId = selectedVoiceId
                sherpaOnnxTtsEngine.selectedSpeakerId = getSpeakerId(selectedVoiceId)
                withContext(Dispatchers.IO) {
                    sherpaOnnxTtsEngine.initOnnx()
                }
                sherpaOnnxTtsEngine.speak(
                    text = textToSpeak,
                    speed = ttsSpeed,
                    pitch = ttsPitch,
                    onStart = { premiumIdx ->
                        coroutineScope.launch(Dispatchers.Main) {
                            ttsActiveParagraphIndex = if (startFromParagraphIndex >= 0) {
                                startFromParagraphIndex + premiumIdx
                            } else {
                                premiumIdx - 1
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                                lastPersistMs = now
                                saveTtsProgress()
                                loadResumableTtsSession()
                            }
                            if (now - lastNotificationMs >= NOTIFICATION_INTERVAL_MS) {
                                lastNotificationMs = now
                                showTtsNotification()
                            }
                        }
                    },
                    onDone = {
                        coroutineScope.launch(Dispatchers.Main) {
                            if (sleepTimerMinutes == -1) {
                                addLog("End of chapter reached for sleep timer. Stopping playback.")
                                stopTts()
                                sleepTimerMinutes = 0
                            } else {
                                playNextChapterTts()
                            }
                        }
                    },
                    onError = { errorMsg ->
                        coroutineScope.launch(Dispatchers.Main) {
                            addLog("Piper TTS Error: $errorMsg")
                            stopTts()
                        }
                    }
                )

                showTtsNotification()
            }
            return
        }

        ttsPlayingBook = book
        ttsPlayingChapter = chapter
        ttsIsPlaying = true
        ttsIsPaused = false

        initTts {
            coroutineScope.launch(Dispatchers.Main) {
                if (!ttsIsPlaying || ttsPlayingChapter?.id != chapter.id) {
                    return@launch
                }

                tts?.setPitch(ttsPitch)
                tts?.setSpeechRate(ttsSpeed)

                val glossary = repository.getGlossary(book.id)
                val cleanText = repository.applyGlossary(text, glossary)

                val rawParagraphs = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                ttsTotalParagraphs = rawParagraphs.size

                tts?.stop()

                val startIdx = if (startFromParagraphIndex < 0) 0 else startFromParagraphIndex
                speechUnits = buildSpeechUnits(rawParagraphs, startIdx)
                nextUnitToQueue = 0
                activeChapterKey = chapter.id

                if (startFromParagraphIndex < 0) {
                    tts?.speak(chapter.title, TextToSpeech.QUEUE_ADD, null, "title_${chapter.id}")
                    ttsActiveParagraphIndex = -1
                } else {
                    ttsActiveParagraphIndex = startFromParagraphIndex
                }

                queueNextUnits()
                showTtsNotification()

                var consecutiveFailures = 0

                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        consecutiveFailures = 0
                        if (utteranceId != null) {
                            val paraIdx = parseParagraphIndex(utteranceId)
                            if (paraIdx != null) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    ttsActiveParagraphIndex = paraIdx
                                    val now = System.currentTimeMillis()
                                    if (now - lastPersistMs >= PERSIST_INTERVAL_MS) {
                                        lastPersistMs = now
                                        saveTtsProgress()
                                        loadResumableTtsSession()
                                    }
                                    if (now - lastNotificationMs >= NOTIFICATION_INTERVAL_MS) {
                                        lastNotificationMs = now
                                        showTtsNotification()
                                    }
                                }
                            } else if (utteranceId.startsWith("title_")) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    ttsActiveParagraphIndex = -1
                                    saveTtsProgress()
                                    loadResumableTtsSession()
                                }
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        consecutiveFailures = 0
                        if (utteranceId != null) {
                            val unitIdx = parseUnitIndex(utteranceId)
                            if (unitIdx != null) {
                                if (unitIdx >= speechUnits.size - 1) {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        if (sleepTimerMinutes == -1) {
                                            addLog("End of chapter reached for sleep timer. Stopping playback.")
                                            stopTts()
                                            sleepTimerMinutes = 0
                                        } else {
                                            playNextChapterTts()
                                        }
                                    }
                                } else if (unitIdx >= nextUnitToQueue - QUEUE_REFILL_THRESHOLD) {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        queueNextUnits()
                                    }
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            coroutineScope.launch(Dispatchers.Main) {
                                addLog("3 consecutive speech utterance errors — stopping playback.")
                                stopTts()
                            }
                        }
                    }
                })
            }
        }
    }

    fun playNextChapterTts() {
        if (!ttsIsPlaying) return
        val book = ttsPlayingBook ?: return
        val currentChapter = ttsPlayingChapter ?: return

        coroutineScope.launch(Dispatchers.IO) {
            val chapters = repository.getChapters(book.id)
            val currentIdx = chapters.indexOfFirst { it.id == currentChapter.id }
            if (currentIdx != -1 && currentIdx < chapters.size - 1) {
                val nextChapter = chapters[currentIdx + 1]
                withContext(Dispatchers.Main) {
                    speak(nextChapter.content, book, nextChapter)
                }
            } else {
                withContext(Dispatchers.Main) {
                    stopTts()
                }
            }
        }
    }

    fun playPreviousChapterTts() {
        val book = ttsPlayingBook ?: return
        val currentChapter = ttsPlayingChapter ?: return

        coroutineScope.launch(Dispatchers.IO) {
            val chapters = repository.getChapters(book.id)
            val currentIdx = chapters.indexOfFirst { it.id == currentChapter.id }
            if (currentIdx > 0) {
                val prevChapter = chapters[currentIdx - 1]
                withContext(Dispatchers.Main) {
                    speak(prevChapter.content, book, prevChapter)
                }
            } else {
                withContext(Dispatchers.Main) {
                    speak(currentChapter.content, book, currentChapter, startFromParagraphIndex = -1)
                }
            }
        }
    }

    fun pauseTts(abandonFocus: Boolean = true) {
        sherpaOnnxTtsEngine.stop()
        tts?.stop()
        if (abandonFocus) {
            audioFocusHelper.abandonFocus()
        }
        ttsIsPlaying = false
        ttsIsPaused = true
        showTtsNotification()
        saveTtsProgress()
        loadResumableTtsSession()
    }

    fun resumeTts() {
        val book = ttsPlayingBook ?: return
        val chapter = ttsPlayingChapter ?: return
        speak(chapter.content, book, chapter, startFromParagraphIndex = ttsActiveParagraphIndex ?: 0)
    }

    fun stopTts() {
        sherpaOnnxTtsEngine.stop()
        tts?.stop()
        audioFocusHelper.abandonFocus()
        ttsPlayingBook = null
        ttsPlayingChapter = null
        ttsIsPlaying = false
        ttsIsPaused = false
        ttsActiveParagraphIndex = -1
        dismissTtsNotification()
        clearTtsProgress()
        hasResumableSession = false

        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        TtsPlaybackService.stop(application)
    }

    fun saveTtsProgress() {
        val book = ttsPlayingBook ?: return
        val chapter = ttsPlayingChapter ?: return
        val para = ttsActiveParagraphIndex ?: -1
        prefs.edit()
            .putString("tts_resume_book_id", book.id)
            .putString("tts_resume_chapter_id", chapter.id)
            .putInt("tts_resume_para", para)
            .putBoolean("tts_was_playing", ttsIsPlaying)
            .apply()

        prefs.edit()
            .putInt("progress_para_${book.id}_${chapter.id}", if (para < 0) 0 else para)
            .putString("progress_chapter_${book.id}", chapter.id)
            .apply()

        if (book.lastReadChapterId != chapter.id) {
            coroutineScope.launch(Dispatchers.IO) {
                repository.updateBook(book.copy(lastReadChapterId = chapter.id))
            }
        }

        showTtsNotification()
    }

    fun clearTtsProgress() {
        prefs.edit()
            .remove("tts_resume_book_id")
            .remove("tts_resume_chapter_id")
            .remove("tts_resume_para")
            .remove("tts_was_playing")
            .apply()
    }

    fun loadResumableTtsSession() {
        val bookId = prefs.getString("tts_resume_book_id", "") ?: ""
        val chapterId = prefs.getString("tts_resume_chapter_id", "") ?: ""
        val para = prefs.getInt("tts_resume_para", -1)
        if (bookId.isNotEmpty() && chapterId.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val book = repository.getBook(bookId)
                val chapter = repository.getChapter(chapterId)
                if (book != null && chapter != null) {
                    withContext(Dispatchers.Main) {
                        resumeBookId = bookId
                        resumeChapterId = chapterId
                        resumeParagraph = para
                        resumeBookName = book.title
                        resumeChapterTitle = chapter.title
                        hasResumableSession = true
                    }
                }
            }
        } else {
            hasResumableSession = false
        }
    }

    fun resumeLastSession() {
        val bookId = prefs.getString("tts_resume_book_id", "") ?: ""
        val chapterId = prefs.getString("tts_resume_chapter_id", "") ?: ""
        val para = prefs.getInt("tts_resume_para", -1)
        if (bookId.isNotEmpty() && chapterId.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val book = repository.getBook(bookId)
                val chapter = repository.getChapter(chapterId)
                if (book != null && chapter != null) {
                    withContext(Dispatchers.Main) {
                        speak(chapter.content, book, chapter, startFromParagraphIndex = para)
                    }
                }
            }
        }
    }

    fun seekToParagraph(index: Int) {
        val book = ttsPlayingBook ?: return
        val chapter = ttsPlayingChapter ?: return
        val clampedIndex = index.coerceIn(-1, ttsTotalParagraphs - 1)
        speak(chapter.content, book, chapter, startFromParagraphIndex = clampedIndex)
    }

    fun skipParagraph(delta: Int) {
        val current = ttsActiveParagraphIndex ?: -1
        val next = current + delta
        seekToParagraph(next)
    }

    fun addLog(msg: String) {
        scraping.addLog(msg)
    }
}
