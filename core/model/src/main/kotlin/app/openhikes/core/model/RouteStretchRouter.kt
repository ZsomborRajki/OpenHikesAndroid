package app.openhikes.core.model

/**
 * Result of routing a stretch between two spots (optionally through a pulled midpoint).
 */
data class RoutedStretchWay(val coordinates: List<GeoCoordinate>, val isMapped: Boolean)

/**
 * Seam for trail-aware stretch routing used by Edit Route (*Follow Trails*, *Close Loop*, and map pulls).
 *
 * Returns `null` if cancelled or unavailable; returns [RoutedStretchWay.isMapped] = `false` when no
 * mapped trail connects the requested endpoints.
 */
interface RouteStretchRouter {
    suspend fun routeWay(from: GeoCoordinate, to: GeoCoordinate, through: GeoCoordinate? = null): RoutedStretchWay?
}
