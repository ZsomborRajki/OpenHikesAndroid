package app.openhikes.core.ui.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.openhikes.core.model.HikeId
import kotlinx.serialization.Serializable

/**
 * Marker interface for all Navigation 3 route keys in OpenHikes.
 */
interface OpenHikesRoute : NavKey

/**
 * Marker interface for routes tied to a specific [HikeId] so deleting that hike strips
 * all of its destinations atomically from the shared back stack.
 */
interface HikeScopedRoute : OpenHikesRoute {
    val hikeId: HikeId
}

/**
 * Root home screen hosted inside the adaptive bottom sheet / docked side panel.
 */
@Serializable
data object MapHomeRoute : OpenHikesRoute

/**
 * Hike detail screen pushed onto the shared adaptive back stack.
 */
@Serializable
data class HikeDetailRoute(override val hikeId: HikeId) : HikeScopedRoute

/**
 * Route line appearance editor pushed from [HikeDetailRoute].
 */
@Serializable
data class RouteStyleRoute(override val hikeId: HikeId) : HikeScopedRoute

/**
 * Interactive route trimmer and step editor pushed from [HikeDetailRoute].
 */
@Serializable
data class RouteEditRoute(override val hikeId: HikeId) : HikeScopedRoute

/**
 * Finished walk summary destination pushed from [HikeDetailRoute] or walk completion.
 */
@Serializable
data class WalkSummaryRoute(override val hikeId: HikeId, val walkId: String) : HikeScopedRoute

/**
 * Saved or recording hike place detail destination pushed from a hike's Places list or map pin.
 */
@Serializable
data class HikePlaceDetailRoute(override val hikeId: HikeId, val placeId: String) : HikeScopedRoute

/**
 * Center-map placement form for adding a custom place to a hike.
 */
@Serializable
data class AddHikePlaceRoute(override val hikeId: HikeId, val initialLatitude: Double, val initialLongitude: Double) :
    HikeScopedRoute

/**
 * OpenStreetMap corridor and area search around a saved hike's route.
 */
@Serializable
data class PlacesAroundRoute(override val hikeId: HikeId) : HikeScopedRoute

/**
 * OpenStreetMap nearby places browser around an active recording.
 */
@Serializable
data class PlacesNearbyRoute(
    override val hikeId: HikeId,
    val initialLatitude: Double? = null,
    val initialLongitude: Double? = null
) : HikeScopedRoute

/**
 * Quick place picker opened while recording: offers *Places Nearby*, nearby OSM matches,
 * and *Add Your Own Place*.
 */
@Serializable
data class RecordingAddPlaceRoute(
    override val hikeId: HikeId,
    val latitude: Double? = null,
    val longitude: Double? = null
) : HikeScopedRoute

/**
 * Full-screen swipeable photograph viewer pushed over a hike or active recording.
 */
@Serializable
data class HikePhotoViewerRoute(override val hikeId: HikeId, val startPhotoId: String) : HikeScopedRoute

/**
 * Device photo library discovery review destination for matching photos taken during a hike.
 */
@Serializable
data class PhotoDiscoveryRoute(override val hikeId: HikeId) : HikeScopedRoute

/**
 * Application settings destination pushed onto the shared adaptive back stack.
 */
@Serializable
data object SettingsRoute : OpenHikesRoute

/**
 * Live GPS trail recording destination pushed onto the shared adaptive back stack.
 */
@Serializable
data object RecordingRoute : OpenHikesRoute

/**
 * Trail Maker destination pushed from the home map toolbar to draw a new trail on the live map.
 */
@Serializable
data object TrailMakerRoute : OpenHikesRoute

/**
 * Map Modes destination pushed from the floating map controls to pick base map style, toggle
 * the hiking routes overlay, and jump to Offline Maps while keeping the live map visible.
 */
@Serializable
data object MapModesRoute : OpenHikesRoute

/**
 * Offline countries catalog & download manager destination.
 *
 * When [overMap] is `true`, draws country polygon outlines on the live map and supports tapping
 * countries on the map or framing the camera on row selection.
 */
@Serializable
data class OfflineMapsRoute(val overMap: Boolean = true) : OpenHikesRoute

/**
 * Multibound installer registering a feature module's destinations on the shared [EntryProviderScope].
 */
typealias EntryProviderInstaller = EntryProviderScope<NavKey>.(OpenHikesNavigator) -> Unit
