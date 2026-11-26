package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapStyleId

/**
 * Coarse map style, overlay, and route collection rendered by [TrailMapHost].
 */
@Immutable
data class MapOverlayState(
    val mapStyleId: String = MapStyleId.OPENHIKES,
    val showsHikingRoutes: Boolean = true,
    val attribution: MapAttribution = MapAttribution.forStyle(mapStyleId, showsHikingRoutes),
    val routes: List<MapRouteOverlay> = emptyList(),
    val libraryLines: List<MapLibraryLineOverlay> = emptyList(),
    val countryOutlines: List<MapCountryOutline> = emptyList()
)
