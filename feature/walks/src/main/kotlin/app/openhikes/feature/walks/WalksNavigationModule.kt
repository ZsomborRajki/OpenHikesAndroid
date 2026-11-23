package app.openhikes.feature.walks

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.WalkSummaryRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the finished hike summary destination into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object WalksNavigationModule {
    @Provides
    @IntoSet
    fun provideWalksEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<WalkSummaryRoute> { route ->
            WalkSummaryRoute(
                hikeId = route.hikeId,
                walkId = route.walkId,
                onBack = { navigator.pop() },
                onCollapseSheet = { navigator.collapseSheet() }
            )
        }
    }
}
