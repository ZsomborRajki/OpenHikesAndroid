package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.cos
import org.junit.Test

class TrailBreakdownAnalyzerTest {
    @Test
    fun measuresSplitWaysTracktypeFallbackUnknownAndUnmappedGround() {
        // 400m north: 0..100m paved, 100..200m tracktype=grade2 (gravel),
        // 200..300m untagged (unknown), 300..400m beyond way end (first ~20m stays within 25m tolerance).
        val n0 = TrailGraphNode(1L, latForMeters(0.0), BASE_LON)
        val n1 = TrailGraphNode(2L, latForMeters(100.0), BASE_LON)
        val n2 = TrailGraphNode(3L, latForMeters(200.0), BASE_LON)
        val n3 = TrailGraphNode(4L, latForMeters(300.0), BASE_LON)
        val graph = TrailGraph(
            nodes = listOf(n0, n1, n2, n3),
            edges = listOf(
                edge(10L, 0, n0, n1, surface = "asphalt", sacScale = "hiking"),
                edge(11L, 0, n1, n2, tracktype = "grade2", sacScale = "mountain_hiking"),
                edge(12L, 0, n2, n3, surface = null, tracktype = null, sacScale = null)
            )
        )
        // Sparse 2-point 400m route verifies 20m midpoint sub-sampling catches every mid-segment transition.
        val route = listOf(
            RouteCoordinate(latForMeters(0.0), BASE_LON),
            RouteCoordinate(latForMeters(400.0), BASE_LON)
        )

        val surface = TrailBreakdownAnalyzer.surfaceBreakdown(route, graph)
        assertThat(surface.totalMeters).isWithin(2.0).of(400.0)
        assertThat(surface.metersFor(TrailSurface.PAVED)).isWithin(12.0).of(100.0)
        assertThat(surface.metersFor(TrailSurface.GRAVEL)).isWithin(12.0).of(100.0)
        assertThat(surface.metersFor(TrailSurface.UNKNOWN)).isWithin(12.0).of(120.0)
        assertThat(surface.metersFor(TrailSurface.UNMAPPED)).isWithin(12.0).of(80.0)
        assertThat(surface.surveyedFraction).isWithin(0.05).of(0.5)

        val difficulty = TrailBreakdownAnalyzer.difficultyBreakdown(route, graph)
        assertThat(difficulty.metersFor(TrailDifficulty.HIKING)).isWithin(12.0).of(100.0)
        assertThat(difficulty.metersFor(TrailDifficulty.MOUNTAIN_HIKING)).isWithin(12.0).of(100.0)
        assertThat(difficulty.metersFor(TrailDifficulty.UNKNOWN)).isWithin(12.0).of(120.0)
        assertThat(difficulty.metersFor(TrailDifficulty.UNMAPPED)).isWithin(12.0).of(80.0)
    }

    @Test
    fun wayStickinessHoldsCurrentWayAgainstSlightlyCloserParallelRoad() {
        // Gravel path at lonOffset 0m, parallel paved road 12m east.
        val g0 = TrailGraphNode(1L, latForMeters(0.0), BASE_LON)
        val g1 = TrailGraphNode(2L, latForMeters(200.0), BASE_LON)
        val p0 = TrailGraphNode(3L, latForMeters(0.0), lonForMeters(12.0))
        val p1 = TrailGraphNode(4L, latForMeters(200.0), lonForMeters(12.0))
        val graph = TrailGraph(
            nodes = listOf(g0, g1, p0, p1),
            edges = listOf(
                edge(100L, 0, g0, g1, surface = "gravel"),
                edge(200L, 0, p0, p1, surface = "asphalt")
            )
        )
        // Route starts on gravel (0m), then drifts 7m east (5m from road, 7m from gravel -> within 5m stickiness).
        val route = listOf(
            RouteCoordinate(latForMeters(0.0), BASE_LON),
            RouteCoordinate(latForMeters(40.0), BASE_LON),
            RouteCoordinate(latForMeters(41.0), lonForMeters(7.0)),
            RouteCoordinate(latForMeters(200.0), lonForMeters(7.0))
        )

        val surface = TrailBreakdownAnalyzer.surfaceBreakdown(route, graph)
        assertThat(surface.dominant?.category).isEqualTo(TrailSurface.GRAVEL)
        assertThat(surface.metersFor(TrailSurface.PAVED)).isEqualTo(0.0)
    }

    @Test
    fun excludesPausedTransferFromTotalsAndSplitsDifficultyRunsAtPause() {
        val a0 = TrailGraphNode(1L, latForMeters(0.0), BASE_LON)
        val a1 = TrailGraphNode(2L, latForMeters(100.0), BASE_LON)
        val b0 = TrailGraphNode(3L, latForMeters(600.0), BASE_LON)
        val b1 = TrailGraphNode(4L, latForMeters(700.0), BASE_LON)
        val graph = TrailGraph(
            nodes = listOf(a0, a1, b0, b1),
            edges = listOf(
                edge(10L, 0, a0, a1, surface = "gravel", sacScale = "hiking"),
                edge(11L, 0, b0, b1, surface = "gravel", sacScale = "hiking")
            )
        )
        val route = listOf(
            RouteCoordinate(latForMeters(0.0), BASE_LON),
            RouteCoordinate(latForMeters(100.0), BASE_LON),
            RouteCoordinate(latForMeters(600.0), BASE_LON, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(latForMeters(700.0), BASE_LON)
        )

        val surface = TrailBreakdownAnalyzer.surfaceBreakdown(route, graph)
        assertThat(surface.totalMeters).isWithin(2.0).of(200.0)
        assertThat(surface.metersFor(TrailSurface.UNMAPPED)).isEqualTo(0.0)

        val runs = TrailBreakdownAnalyzer.difficultyRuns(route, graph)
        assertThat(runs).hasSize(2)
        assertThat(runs[0].category).isEqualTo(TrailDifficulty.HIKING)
        assertThat(runs[1].category).isEqualTo(TrailDifficulty.HIKING)
        assertThat(runs[0].coordinates.last()).isNotEqualTo(runs[1].coordinates.first())
    }

    @Test
    fun emptyRouteProducesEmptyBreakdownAndNoRuns() {
        val empty = TrailBreakdownAnalyzer.surfaceBreakdown(emptyList(), TrailGraph.Empty)
        assertThat(empty.isEmpty).isTrue()
        assertThat(TrailBreakdownAnalyzer.difficultyRuns(listOf(RouteCoordinate(47.7, 12.8)), TrailGraph.Empty))
            .isEmpty()
    }

    private fun edge(
        wayId: Long,
        seg: Int,
        from: TrailGraphNode,
        to: TrailGraphNode,
        surface: String? = null,
        tracktype: String? = null,
        sacScale: String? = null
    ): TrailGraphEdge = TrailGraphEdge(
        id = TrailGraphEdgeId(wayId, seg),
        fromNodeId = from.id,
        toNodeId = to.id,
        lengthMeters = RouteGeometry.distanceMeters(from.coordinate, to.coordinate),
        highway = "path",
        surface = surface,
        tracktype = tracktype,
        sacScale = sacScale
    )

    private fun latForMeters(meters: Double): Double = BASE_LAT + meters / METERS_PER_DEGREE_LAT

    private fun lonForMeters(meters: Double): Double =
        BASE_LON + meters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(BASE_LAT)))

    private companion object {
        const val BASE_LAT = 47.70
        const val BASE_LON = 12.83
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}
