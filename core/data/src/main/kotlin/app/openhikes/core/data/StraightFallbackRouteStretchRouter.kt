package app.openhikes.core.data

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteStretchRouter
import app.openhikes.core.model.RoutedStretchWay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [RouteStretchRouter] when offline trail-graph routing is not configured.
 *
 * Returns a straight unmapped way so map pulls and *Close Loop* work offline immediately, while
 * *Follow Trails* cleanly informs the hiker that no mapped path was found.
 */
@Singleton
internal class StraightFallbackRouteStretchRouter @Inject constructor() : RouteStretchRouter {
    override suspend fun routeWay(from: GeoCoordinate, to: GeoCoordinate, through: GeoCoordinate?): RoutedStretchWay {
        val points = buildList {
            add(from)
            if (through != null) add(through)
            add(to)
        }
        return RoutedStretchWay(coordinates = points, isMapped = false)
    }
}
