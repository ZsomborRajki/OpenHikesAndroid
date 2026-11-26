package app.openhikes.feature.photos

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import app.openhikes.core.ui.navigation.PhotoDiscoveryRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers `:feature:photos` destinations into the app-owned Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object PhotosNavigationModule {
    @Provides
    @IntoSet
    fun providePhotosEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<HikePhotoViewerRoute> { route ->
            HikePhotoViewerRoute(
                hikeId = route.hikeId,
                startPhotoId = route.startPhotoId,
                onBack = { navigator.pop() },
                onRevealMap = { navigator.revealMap() },
                onExpandFullHeight = { navigator.expandFullHeight() }
            )
        }
        entry<PhotoDiscoveryRoute> { route ->
            PhotoDiscoveryRoute(
                hikeId = route.hikeId,
                onDone = { navigator.pop() }
            )
        }
    }
}
