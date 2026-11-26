package app.openhikes.feature.hikes

import app.openhikes.core.ui.navigation.AddHikePlaceRoute
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import app.openhikes.core.ui.navigation.HikePlaceDetailRoute
import app.openhikes.core.ui.navigation.PhotoDiscoveryRoute
import app.openhikes.core.ui.navigation.PlacesAroundRoute
import app.openhikes.core.ui.navigation.RouteEditRoute
import app.openhikes.core.ui.navigation.RouteStyleRoute
import app.openhikes.core.ui.navigation.WalkSummaryRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes hike detail and route style destinations into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object HikesNavigationModule {
    @Provides
    @IntoSet
    fun provideHikesEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<HikeDetailRoute> { route ->
            HikeDetailRoute(
                hikeId = route.hikeId,
                onBack = { navigator.pop() },
                onOpenRouteStyle = { hikeId -> navigator.navigate(RouteStyleRoute(hikeId)) },
                onOpenRouteEdit = { hikeId -> navigator.navigate(RouteEditRoute(hikeId)) },
                onOpenPlace = { hikeId, placeId -> navigator.navigate(HikePlaceDetailRoute(hikeId, placeId)) },
                onOpenPlacesAround = { hikeId -> navigator.navigate(PlacesAroundRoute(hikeId)) },
                onOpenPhoto = { hikeId, photoId -> navigator.navigate(HikePhotoViewerRoute(hikeId, photoId)) },
                onOpenPhotoDiscovery = { hikeId -> navigator.navigate(PhotoDiscoveryRoute(hikeId)) },
                onAddPlaceAt = { hikeId, coordinate ->
                    navigator.navigate(
                        AddHikePlaceRoute(
                            hikeId = hikeId,
                            initialLatitude = coordinate.latitude,
                            initialLongitude = coordinate.longitude
                        )
                    )
                },
                onOpenWalkSummary = { hikeId, walkId -> navigator.navigate(WalkSummaryRoute(hikeId, walkId)) },
                onHikeDeleted = { hikeId -> navigator.removeRoutesForHike(hikeId) },
                onCollapseSheet = { navigator.collapseSheet() }
            )
        }
        entry<RouteStyleRoute> { route ->
            RouteStyleRoute(
                hikeId = route.hikeId,
                onBack = { navigator.pop() }
            )
        }
    }
}
