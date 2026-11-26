package app.openhikes.feature.places

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.openhikes.core.ui.navigation.AddHikePlaceRoute
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikePlaceDetailRoute
import app.openhikes.core.ui.navigation.OpenHikesNavigator
import app.openhikes.core.ui.navigation.PlacesAroundRoute
import app.openhikes.core.ui.navigation.PlacesNearbyRoute
import app.openhikes.core.ui.navigation.RecordingAddPlaceRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes trail-place detail, placement adder, Places Around Trail, Places Nearby,
 * and recording place-picker destinations into the shared Navigation 3 graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlacesNavigationModule {
    @Provides
    @IntoSet
    fun providePlacesEntryProvider(): EntryProviderInstaller = { navigator ->
        registerPlaceDetailEntries(navigator)
        registerPlaceDiscoveryEntries(navigator)
    }
}

private fun EntryProviderScope<NavKey>.registerPlaceDetailEntries(navigator: OpenHikesNavigator) {
    entry<HikePlaceDetailRoute> { route ->
        HikePlaceDetailRoute(
            hikeId = route.hikeId,
            placeId = route.placeId,
            onBack = { navigator.pop() },
            onOpenPlace = { nextPlaceId ->
                navigator.replaceTop(HikePlaceDetailRoute(route.hikeId, nextPlaceId))
            },
            onCollapseSheet = { navigator.collapseSheet() }
        )
    }
    entry<AddHikePlaceRoute> { route ->
        AddHikePlaceRoute(
            hikeId = route.hikeId,
            initialLatitude = route.initialLatitude,
            initialLongitude = route.initialLongitude,
            onAdded = { newPlaceId -> navigator.replaceTop(HikePlaceDetailRoute(route.hikeId, newPlaceId)) },
            onCancel = { navigator.pop() }
        )
    }
}

private fun EntryProviderScope<NavKey>.registerPlaceDiscoveryEntries(navigator: OpenHikesNavigator) {
    entry<PlacesAroundRoute> { route ->
        PlacesAroundRoute(
            hikeId = route.hikeId,
            onBack = { navigator.pop() },
            onAddOwnPlace = { coordinate ->
                navigator.navigate(AddHikePlaceRoute(route.hikeId, coordinate.latitude, coordinate.longitude))
            }
        )
    }
    entry<PlacesNearbyRoute> { route ->
        PlacesNearbyRoute(
            hikeId = route.hikeId,
            initialLatitude = route.initialLatitude,
            initialLongitude = route.initialLongitude,
            onBack = { navigator.pop() },
            onAddOwnPlace = { coordinate ->
                navigator.navigate(AddHikePlaceRoute(route.hikeId, coordinate.latitude, coordinate.longitude))
            }
        )
    }
    entry<RecordingAddPlaceRoute> { route ->
        RecordingAddPlaceRoute(
            hikeId = route.hikeId,
            latitude = route.latitude,
            longitude = route.longitude,
            onBack = { navigator.pop() },
            onAddedPlace = { newPlaceId -> navigator.replaceTop(HikePlaceDetailRoute(route.hikeId, newPlaceId)) },
            onAddOwnPlace = { coordinate ->
                navigator.replaceTop(AddHikePlaceRoute(route.hikeId, coordinate.latitude, coordinate.longitude))
            },
            onOpenPlacesNearby = { coordinate ->
                navigator.replaceTop(
                    PlacesNearbyRoute(route.hikeId, coordinate?.latitude, coordinate?.longitude)
                )
            }
        )
    }
}
