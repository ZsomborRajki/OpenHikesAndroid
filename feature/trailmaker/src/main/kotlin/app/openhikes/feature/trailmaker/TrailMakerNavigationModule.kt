package app.openhikes.feature.trailmaker

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.TrailMakerRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Trail Maker destination into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object TrailMakerNavigationModule {
    @Provides
    @IntoSet
    fun provideTrailMakerEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<TrailMakerRoute> {
            TrailMakerRoute(
                onBack = { navigator.pop() },
                onSaved = { hikeId -> navigator.replaceTop(HikeDetailRoute(hikeId)) }
            )
        }
    }
}
