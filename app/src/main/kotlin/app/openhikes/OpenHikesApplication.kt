package app.openhikes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.openhikes.core.map.MapRuntimeConfigurator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point bootstrapping Hilt dependency injection, MapLibre request hooks,
 * and Hilt-backed WorkManager.
 */
@HiltAndroidApp
class OpenHikesApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var mapRuntimeConfigurator: MapRuntimeConfigurator

    override fun onCreate() {
        super.onCreate()
        mapRuntimeConfigurator.ensureConfigured()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
