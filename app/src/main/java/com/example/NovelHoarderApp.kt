/**
 * NovelHoarderApp Application class and AppContainer dependency holder.
 *
 * Provides application-level singletons for offline database, repository, settings,
 * scraping, and TTS playback. Centralizing TtsPlaybackManager in AppContainer guarantees
 * that TtsPlaybackService and MainViewModel share the exact same playback engine instance.
 */
package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.repository.NovelRepository
import com.example.viewmodel.SettingsManager
import com.example.viewmodel.ScrapingManager
import com.example.viewmodel.TtsPlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(val application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val database by lazy { AppDatabase.getDatabase(application) }
    val repository by lazy { NovelRepository(database.bookDao()) }
    val settings by lazy { SettingsManager(application) }
    val scraping by lazy {
        ScrapingManager(
            application = application,
            repository = repository,
            settings = settings,
            coroutineScope = applicationScope
        )
    }
    val tts by lazy {
        TtsPlaybackManager(
            application = application,
            repository = repository,
            settings = settings,
            scraping = scraping,
            coroutineScope = applicationScope
        )
    }
}

class NovelHoarderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun getContainer(application: Application): AppContainer {
            return (application as? NovelHoarderApp)?.container
                ?: (application.applicationContext as NovelHoarderApp).container
        }
    }
}
