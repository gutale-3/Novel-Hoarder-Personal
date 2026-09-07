/**
 * NovelHoarderApp Application class and AppContainer dependency holder.
 *
 * Provides application-level singletons for offline database, repository, settings,
 * scraping, and TTS playback. Centralizing TtsPlaybackManager in AppContainer guarantees
 * that TtsPlaybackService and MainViewModel share the exact same playback engine instance.
 */
package com.example

import android.app.Application
import android.content.Intent
import android.util.Log
import com.example.data.ai.AiProviderRegistry
import com.example.data.ai.ModelManager
import com.example.data.ai.PiperModelManager
import com.example.data.local.AppDatabase
import com.example.data.plugin.PluginManager
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.ui.CrashActivity
import com.example.viewmodel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(val application: Application) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { AppDatabase.getDatabase(application) }
    val repository by lazy { NovelRepository(database.bookDao()) }
    val settings by lazy { SettingsManager(application) }

    val pluginManager by lazy { PluginManager(application) }
    val aiRegistry by lazy { AiProviderRegistry(application) }
    val aiModelManager by lazy { ModelManager(application) }
    val piperModelManager by lazy { PiperModelManager(application) }

    val manualCapture by lazy {
        ManualCaptureManager(application, repository, appScope)
    }

    val scraping by lazy {
        ScrapingManager(
            application = application,
            repository = repository,
            settings = settings,
            coroutineScope = appScope,
            pluginManager = pluginManager,
            manualCapture = manualCapture
        )
    }

    val aiFeatures by lazy {
        AiFeaturesManager(repository, aiRegistry, settings)
    }

    val tts by lazy {
        TtsPlaybackManager(application, repository, settings, scraping, appScope)
    }

    val library by lazy {
        LibraryManager(application, repository, scraping)
    }

    val progress by lazy {
        ReadingProgressManager(application, repository, tts, appScope)
    }

    val stats by lazy {
        ReadingStatsManager(application, repository, appScope)
    }

    val textRules by lazy {
        TextReplacementManager(repository, appScope)
    }

    val sourceMigration by lazy {
        SourceMigrationManager(application, repository, appScope)
    }

    init {
        SourceManager.pluginManagerProvider = { pluginManager }
    }
}

class NovelHoarderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupCrashHandler()
        container = AppContainer(this)
        try {
            com.example.background.ChapterUpdateWorker.schedulePeriodicUpdates(this)
        } catch (t: Throwable) {
            Log.e("NovelHoarderApp", "Failed to schedule background chapter updates", t)
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("NovelHoarderCrash", "Uncaught exception on thread: ${thread.name}", throwable)
                val stackTrace = throwable.stackTraceToString()
                try {
                    val crashFile = File(filesDir, "last_crash.txt")
                    crashFile.writeText(stackTrace)
                } catch (_: Throwable) {}

                val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("crash_stack_trace", stackTrace)
                    putExtra("crash_error_message", throwable.message ?: throwable.javaClass.simpleName)
                    putExtra("crash_thread", thread.name)
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        @Volatile private var instance: NovelHoarderApp? = null
        @Volatile private var fallbackContainer: AppContainer? = null

        fun getContainer(app: Application): AppContainer {
            (app as? NovelHoarderApp)?.let { return it.container }
            (app.applicationContext as? NovelHoarderApp)?.let { return it.container }
            instance?.let { return it.container }
            return fallbackContainer ?: synchronized(this) {
                fallbackContainer ?: AppContainer(app).also { fallbackContainer = it }
            }
        }
    }
}
