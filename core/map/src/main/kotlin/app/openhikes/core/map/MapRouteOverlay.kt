package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.toTrailRuns

/**
 * Rendered polyline state for a single hike route on [TrailMapHost].
 */
@Immutable
data class MapRouteOverlay(
    val hikeId: HikeId,
    val walkedSegments: List<List<GeoCoordinate>>,
    val pausedSegments: List<List<GeoCoordinate>>,
    val tintHex: String,
    val borderHex: String,
    val widthDp: Float,
    val linePattern: RouteLinePattern,
    val isSelected: Boolean
)

/**
 * Live chart scrub or follow highlight dot drawn over the active route on the map.
 */
@Immutable
data class MapRouteHighlight(val hikeId: HikeId, val coordinate: GeoCoordinate, val tintHex: String)

/**
 * Converts a domain [HikeMapRoute] into a [MapRouteOverlay] respecting pause boundaries.
 */
fun HikeMapRoute.toMapRouteOverlay(isSelected: Boolean): MapRouteOverlay {
    val runs = route.toTrailRuns()
    val walked = runs.walkedRuns.map { range ->
        route.subList(range.first, range.last + 1).map { it.coordinate }
    }
    val paused = runs.pausedStretches.map { range ->
        route.subList(range.first, range.last + 1).map { it.coordinate }
    }
    return MapRouteOverlay(
        hikeId = hikeId,
        walkedSegments = walked,
        pausedSegments = paused,
        tintHex = tintHex,
        borderHex = routeBorderHex,
        widthDp = routeWidthDp.toFloat(),
        linePattern = linePattern,
        isSelected = isSelected
    )
}
