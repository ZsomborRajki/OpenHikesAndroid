package app.openhikes.core.testing

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteStretchRouter
import app.openhikes.core.model.RoutedStretchWay

/**
 * Deterministic pure-JVM [RouteStretchRouter] fake for route-editing tests.
 */
class FakeRouteStretchRouter(
    var handler: suspend (from: GeoCoordinate, to: GeoCoordinate, through: GeoCoordinate?) -> RoutedStretchWay? =
        { from, to, through ->
            val points = buildList {
                add(from)
                if (through != null) add(through)
                add(to)
            }
            RoutedStretchWay(coordinates = points, isMapped = false)
        }
) : RouteStretchRouter {
    val requests: MutableList<Triple<GeoCoordinate, GeoCoordinate, GeoCoordinate?>> = mutableListOf()

    override suspend fun routeWay(from: GeoCoordinate, to: GeoCoordinate, through: GeoCoordinate?): RoutedStretchWay? {
        requests.add(Triple(from, to, through))
        return handler(from, to, through)
    }
}
