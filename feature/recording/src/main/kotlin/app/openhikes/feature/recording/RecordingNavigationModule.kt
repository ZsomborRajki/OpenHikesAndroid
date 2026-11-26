package app.openhikes.feature.recording

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import app.openhikes.core.ui.navigation.HikePlaceDetailRoute
import app.openhikes.core.ui.navigation.RecordingRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes [RecordingRoute] into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecordingNavigationModule {
    @Provides
    @IntoSet
    fun provideRecordingEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<RecordingRoute> {
            RecordingRoute(
                onBack = { navigator.pop() },
                onSaved = { hikeId -> navigator.replaceTop(HikeDetailRoute(hikeId)) },
                onDiscarded = { discardedHikeId ->
                    discardedHikeId?.let { navigator.removeRoutesForHike(it) }
                    navigator.pop()
                },
                onOpenPlace = { hikeId, placeId -> navigator.navigate(HikePlaceDetailRoute(hikeId, placeId)) },
                onOpenPhoto = { hikeId, photoId -> navigator.navigate(HikePhotoViewerRoute(hikeId, photoId)) }
            )
        }
    }
}
