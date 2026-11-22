package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate

/**
 * One-shot camera instruction consumed by [TrailMapRenderer] and acknowledged by [id].
 */
@Immutable
sealed interface MapCameraCommand {
    val id: Long

    data class MoveTo(
        override val id: Long,
        val center: GeoCoordinate,
        val zoom: Double? = null,
        val bearing: Double? = null,
        val pitch: Double? = null
    ) : MapCameraCommand

    data class ZoomIn(override val id: Long, val delta: Double = MapCameraState.DEFAULT_ZOOM_STEP) : MapCameraCommand

    data class ZoomOut(override val id: Long, val delta: Double = MapCameraState.DEFAULT_ZOOM_STEP) : MapCameraCommand

    data class ResetNorth(override val id: Long) : MapCameraCommand
}
