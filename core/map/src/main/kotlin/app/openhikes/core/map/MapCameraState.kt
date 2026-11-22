package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate

/**
 * Pure Kotlin camera snapshot exposed across `:core:map` without leaking MapLibre SDK types.
 */
@Immutable
data class MapCameraState(
    val center: GeoCoordinate = DEFAULT_CENTER,
    val zoom: Double = DEFAULT_ZOOM,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0
) {
    fun zoomedIn(delta: Double = DEFAULT_ZOOM_STEP): MapCameraState =
        copy(zoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM))

    fun zoomedOut(delta: Double = DEFAULT_ZOOM_STEP): MapCameraState =
        copy(zoom = (zoom - delta).coerceIn(MIN_ZOOM, MAX_ZOOM))

    fun resetNorth(): MapCameraState = copy(bearing = 0.0, pitch = 0.0)

    companion object {
        private const val DEFAULT_LATITUDE = 47.7215
        private const val DEFAULT_LONGITUDE = 12.8250
        const val DEFAULT_ZOOM = 11.0
        const val DEFAULT_ZOOM_STEP = 1.0
        const val MIN_ZOOM = 1.0
        const val MAX_ZOOM = 20.0

        val DEFAULT_CENTER = GeoCoordinate(
            latitude = DEFAULT_LATITUDE,
            longitude = DEFAULT_LONGITUDE
        )
    }
}
