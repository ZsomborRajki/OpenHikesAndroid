package app.openhikes.feature.offlinemaps

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.OfflineMapsRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Offline Maps destination into the shared Navigation 3 entry provider graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object OfflineMapsNavigationModule {
    @Provides
    @IntoSet
    fun provideOfflineMapsEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<OfflineMapsRoute> { route ->
            OfflineMapsRoute(
                overMap = route.overMap,
                onBack = { navigator.pop() }
            )
        }
    }
}
