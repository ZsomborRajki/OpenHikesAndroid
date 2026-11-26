package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailPlaceSymbol
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One place pin drawn on the map (either saved on the hike or a pale search candidate).
 */
@Immutable
data class MapPlacePin(
    val id: String,
    val coordinate: GeoCoordinate,
    val symbol: TrailPlaceSymbol?,
    val title: String,
    val isCandidate: Boolean = false
)

/**
 * Map place pin and center-placement overlay state published by the deepest place-aware route.
 */
@Immutable
data class MapTrailPlacesOverlay(
    val pins: List<MapPlacePin> = emptyList(),
    val candidates: List<MapPlacePin> = emptyList(),
    val showCenterPlacementPin: Boolean = false,
    val placementSymbol: TrailPlaceSymbol? = null,
    val allowLongPressDrop: Boolean = false
) {
    val hasVisibleContent: Boolean
        get() = pins.isNotEmpty() || candidates.isNotEmpty() || showCenterPlacementPin || allowLongPressDrop
}

/**
 * Floating *Search This Area* pill state surfaced over the map while *Places Around Trail* or
 * *Places Nearby* owns the map.
 */
@Immutable
data class MapSearchAreaPillState(
    val isVisible: Boolean = false,
    val isSearching: Boolean = false,
    val isEnabled: Boolean = true,
    val onSearchArea: () -> Unit = {}
)

/**
 * Callbacks delivering pin taps and long-press map drops back to the owning feature ViewModel.
 */
interface TrailPlacesMapInteractionHandler {
    fun onTapPlacePin(placeId: String) = Unit

    fun onTapCandidatePin(placeId: String) = Unit

    fun onLongPressMap(coordinate: GeoCoordinate) = Unit

    /**
     * Delivered whenever the geographic point directly under the unobstructed center placement
     * reticle changes (due to panning, zooming, resizing, or sheet detent transitions).
     */
    fun onPlacementCoordinateChanged(coordinate: GeoCoordinate) = Unit
}

/**
 * Per-route registration stored inside [TrailPlacesOverlayState].
 */
data class TrailPlacesClaim(
    val routeKey: Any,
    val overlay: MapTrailPlacesOverlay,
    val handler: TrailPlacesMapInteractionHandler? = null,
    val searchAreaPill: MapSearchAreaPillState? = null
)

/**
 * Isolated state holder keyed by Nav3 `routeKey` so place pins, pale candidates, center-placement
 * pins, and *Search This Area* controls always belong to the deepest active map owner.
 */
@Singleton
class TrailPlacesOverlayState @Inject constructor() {
    private val lock = Any()
    private val claimsByRoute = LinkedHashMap<Any, TrailPlacesClaim>()
    private val claimsFlow = MutableStateFlow<Map<Any, TrailPlacesClaim>>(emptyMap())

    val claims: StateFlow<Map<Any, TrailPlacesClaim>> = claimsFlow.asStateFlow()

    fun setClaim(claim: TrailPlacesClaim) {
        synchronized(lock) {
            claimsByRoute[claim.routeKey] = claim
            claimsFlow.value = HashMap(claimsByRoute)
        }
    }

    fun clearClaim(routeKey: Any) {
        synchronized(lock) {
            if (claimsByRoute.remove(routeKey) != null) {
                claimsFlow.value = HashMap(claimsByRoute)
            }
        }
    }

    fun claimFor(routeKey: Any?): TrailPlacesClaim? = if (routeKey == null) {
        null
    } else {
        synchronized(lock) { claimsByRoute[routeKey] }
    }
}
