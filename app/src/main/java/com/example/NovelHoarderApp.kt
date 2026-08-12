/**
 * NovelHoarderApp Application class and AppContainer dependency holder.
 *
 * Provides application-level singletons for offline database, repository, settings,
 * scraping, and TTS playback. Centralizing TtsPlaybackManager in AppContainer guarantees
 * that TtsPlaybackService and MainViewModel share the exact same playback engine instance.
 */
package com.example

import android.app.Application
import androidx.work.Configuration
import com.example.data.ai.AiProviderRegistry
import com.example.data.ai.ModelManager
import com.example.data.ai.PiperModelManager
import com.example.data.local.AppDatabase
import com.example.data.plugin.PluginManager
import com.example.data.repository.NovelRepository
import com.example.data.scraper.SourceManager
import com.example.viewmodel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    init {
        SourceManager.pluginManagerProvider = { pluginManager }
    }
}

class NovelHoarderApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
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
