package app.openhikes.core.map

import app.openhikes.core.model.PlaceSearchArea
import app.openhikes.core.model.TrailPointQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Isolated holder tracking the live map camera position and zoom so feature destinations
 * (such as Add Place center-pin placement and Search This Area) can observe camera updates
 * without hoisting high-frequency camera ticks into root shell chrome.
 */
@Singleton
class MapCameraTracker @Inject constructor() {
    private val cameraStateFlow = MutableStateFlow(MapCameraState())

    val cameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()

    /**
     * Emits only when zoom/latitude crosses the [TrailPointQuery.MAXIMUM_RADIUS_METERS] threshold,
     * keeping *Search This Area* controls reactive without recomposing on every pan tick.
     */
    val canSearchVisibleArea: Flow<Boolean> = cameraStateFlow
        .map { searchableAreaFor(it) != null }
        .distinctUntilChanged()

    fun update(state: MapCameraState) {
        cameraStateFlow.value = state
    }

    /**
     * Estimates the visible map search area around [MapCameraState.center] from the current zoom
     * level, returning `null` when zoomed out past [TrailPointQuery.MAXIMUM_RADIUS_METERS].
     */
    fun currentSearchableArea(): PlaceSearchArea? = searchableAreaFor(cameraStateFlow.value)

    companion object {
        private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
        private const val APPROX_HALF_VIEWPORT_TILES = 0.75
        private const val HALF_TURN_DEGREES = 180.0
        private const val MIN_COS_LAT = 0.01

        fun searchableAreaFor(camera: MapCameraState): PlaceSearchArea? {
            val cosLat = cos(camera.center.latitude * PI / HALF_TURN_DEGREES).coerceAtLeast(MIN_COS_LAT)
            val tileMeters = EARTH_CIRCUMFERENCE_METERS * cosLat / 2.0.pow(camera.zoom)
            val radiusMeters = tileMeters * APPROX_HALF_VIEWPORT_TILES
            return if (radiusMeters in 1.0..TrailPointQuery.MAXIMUM_RADIUS_METERS) {
                PlaceSearchArea(center = camera.center, radiusMeters = radiusMeters)
            } else {
                null
            }
        }
    }
}
