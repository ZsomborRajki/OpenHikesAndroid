package app.openhikes.feature.routeedit

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.RouteEditRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Edit Route destination into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object RouteEditNavigationModule {
    @Provides
    @IntoSet
    fun provideRouteEditEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<RouteEditRoute> { route ->
            RouteEditRoute(
                hikeId = route.hikeId,
                onBack = { navigator.pop() },
                onDone = { navigator.pop() }
            )
        }
    }
}
