package app.openhikes.feature.map

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.MapModesRoute
import app.openhikes.core.ui.navigation.OfflineMapsRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Map Modes destination into the shared Navigation 3 entry provider graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object MapModesNavigationModule {
    @Provides
    @IntoSet
    fun provideMapModesEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<MapModesRoute> {
            MapModesRoute(
                onClose = { navigator.pop() },
                onOpenOfflineMaps = { navigator.replaceTop(OfflineMapsRoute(overMap = true)) }
            )
        }
    }
}
