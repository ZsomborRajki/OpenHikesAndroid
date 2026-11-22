package app.openhikes.core.common.gpx

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxWaypoint
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import com.google.common.truth.Truth.assertThat
import kotlin.time.measureTime
import org.junit.Test

class GpxWaypointSpatialIndexTest {
    @Test
    fun excludesPauseGapsIncludesLoneFixesAndHandlesAntimeridianCrossings() {
        val pausedRoute = listOf(
            RouteCoordinate(47.0000, 12.0000),
            RouteCoordinate(47.0010, 12.0000),
            RouteCoordinate(47.0500, 12.0000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.0510, 12.0000),
            RouteCoordinate(47.0800, 12.0000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.0900, 12.0000, boundary = RouteBoundary.PAUSED)
        )
        val antimeridianRoute = listOf(
            RouteCoordinate(-16.8000, 179.9950),
            RouteCoordinate(-16.8000, -179.9950)
        )

        val places = listOf(
            GpxWaypoint(GeoCoordinate(47.0250, 12.0000), name = "Pause Gap Midpoint"),
            GpxWaypoint(GeoCoordinate(47.0800, 12.0000), name = "Lone Fix Hut"),
            GpxWaypoint(GeoCoordinate(-16.8001, 179.9990), name = "Date Line Peak"),
            GpxWaypoint(GeoCoordinate(47.2000, 12.5000), name = "Far Off-Route")
        )

        val buckets = GpxWaypointSpatialIndex.assignPlaces(
            places = places,
            routes = listOf(pausedRoute, antimeridianRoute),
            maxPlaces = 200
        )

        assertThat(buckets[0].map { it.name }).containsExactly("Lone Fix Hut")
        assertThat(buckets[1].map { it.name }).containsExactly("Date Line Peak")
    }

    @Test
    fun handlesLargeTrackAndManyOffRouteWaypointsWithinInteractiveBudget() {
        val southRoute = List(20_000) { idx ->
            RouteCoordinate(47.0 + idx * 0.00002, 12.0)
        }
        val northRoute = List(20_000) { idx ->
            RouteCoordinate(48.0 + idx * 0.00002, 13.0)
        }
        val places = buildList {
            repeat(4_000) { idx ->
                add(GpxWaypoint(GeoCoordinate(47.0 + idx * 0.0001, 12.05), name = "OffSouth-$idx"))
                add(GpxWaypoint(GeoCoordinate(48.0 + idx * 0.0001, 13.05), name = "OffNorth-$idx"))
            }
            add(GpxWaypoint(GeoCoordinate(47.20001, 12.0001), name = "OnSouth"))
            add(GpxWaypoint(GeoCoordinate(48.20001, 13.0001), name = "OnNorth"))
        }

        lateinit var buckets: List<List<GpxWaypoint>>
        val elapsed = measureTime {
            buckets = GpxWaypointSpatialIndex.assignPlaces(
                places = places,
                routes = listOf(southRoute, northRoute),
                maxPlaces = 200
            )
        }

        assertThat(buckets[0].map { it.name }).containsExactly("OnSouth")
        assertThat(buckets[1].map { it.name }).containsExactly("OnNorth")
        assertThat(elapsed.inWholeMilliseconds).isLessThan(2_000L)
    }
}
