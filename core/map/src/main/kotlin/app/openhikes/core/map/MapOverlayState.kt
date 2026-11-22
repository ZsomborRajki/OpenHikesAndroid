package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapStyleId

/**
 * Coarse map style and overlay configuration rendered by [TrailMapHost].
 */
@Immutable
data class MapOverlayState(
    val mapStyleId: String = MapStyleId.OPENHIKES,
    val showsHikingRoutes: Boolean = true,
    val attribution: MapAttribution = MapAttribution.forOpenHikes(showsHikingRoutes)
)
