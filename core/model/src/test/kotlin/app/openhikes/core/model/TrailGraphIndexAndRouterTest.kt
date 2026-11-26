package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailGraphIndexAndRouterTest {
    @Test
    fun snapsEndpointsWithinRadiusAndRoutesAlongConnectedTrail() {
        val graph = linearTrailGraph()
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val ends = TrailLegEnds(
            start = GeoCoordinate(47.71836, 12.83172),
            end = GeoCoordinate(47.71950, 12.83172)
        )

        val route = TrailGraphLegRouter.route(ends, index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(route.coordinates.first()).isEqualTo(ends.start)
        assertThat(route.coordinates.last()).isEqualTo(ends.end)
        assertThat(route.coordinates.size).isGreaterThan(2)
        assertThat(route.distanceMeters).isGreaterThan(0.0)
    }

    @Test
    fun rejectsEndpointsOutsideSnapRadiusAsNoPathBetween() {
        val graph = linearTrailGraph()
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val ends = TrailLegEnds(
            start = GeoCoordinate(47.71836, 12.83172),
            end = GeoCoordinate(47.75000, 12.88000)
        )

        val route = TrailGraphLegRouter.route(ends, index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))
        assertThat(route.coordinates).containsExactly(ends.start, ends.end).inOrder()
    }

    @Test
    fun rejectsLegsLongerThanMaximumLegMeters() {
        val graph = linearTrailGraph()
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val ends = TrailLegEnds(
            start = GeoCoordinate(47.71836, 12.83172),
            end = GeoCoordinate(48.00000, 12.83172)
        )

        val route = TrailGraphLegRouter.route(ends, index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Unmapped(TrailLegGap.TOO_FAR_APART))
    }

    @Test
    fun walkingNetworkPrefersTrailOverSlightlyShorterMainRoad() {
        val n1 = TrailGraphNode(1, 47.7180, 12.8300)
        val n2 = TrailGraphNode(2, 47.7190, 12.8300)
        val nTrailMid = TrailGraphNode(3, 47.7185, 12.8306)
        val roadDist = RouteGeometry.distanceMeters(n1.coordinate, n2.coordinate)
        val trail1 = RouteGeometry.distanceMeters(n1.coordinate, nTrailMid.coordinate)
        val trail2 = RouteGeometry.distanceMeters(nTrailMid.coordinate, n2.coordinate)
        val graph = TrailGraph(
            nodes = listOf(n1, n2, nTrailMid),
            edges = listOf(
                TrailGraphEdge(TrailGraphEdgeId(1, 0), 1, 2, roadDist, highway = "primary"),
                TrailGraphEdge(TrailGraphEdgeId(2, 0), 1, 3, trail1, highway = "path"),
                TrailGraphEdge(TrailGraphEdgeId(2, 1), 3, 2, trail2, highway = "path")
            )
        )
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val ends = TrailLegEnds(n1.coordinate, n2.coordinate)

        val route = TrailGraphLegRouter.route(ends, index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(route.coordinates).contains(nTrailMid.coordinate)
    }

    @Test
    fun findsDistinctAlternativePathAcrossParallelValley() {
        val start = TrailGraphNode(1, 47.7180, 12.8300)
        val end = TrailGraphNode(2, 47.7200, 12.8300)
        val west = TrailGraphNode(3, 47.7190, 12.8294)
        val east = TrailGraphNode(4, 47.7190, 12.8310)
        val graph = TrailGraph(
            nodes = listOf(start, end, west, east),
            edges = listOf(
                edge(10, 0, start, west),
                edge(10, 1, west, end),
                edge(20, 0, start, east),
                edge(20, 1, east, end)
            )
        )
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val route = TrailGraphLegRouter.route(TrailLegEnds(start.coordinate, end.coordinate), index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(route.coordinates).contains(west.coordinate)
        assertThat(route.alternatives).hasSize(1)
        assertThat(route.alternatives.first().coordinates).contains(east.coordinate)
    }

    @Test
    fun handlesBothEndsSnappingOntoSameEdge() {
        val n1 = TrailGraphNode(1, 47.7180, 12.8317)
        val n2 = TrailGraphNode(2, 47.7190, 12.8317)
        val graph = TrailGraph(nodes = listOf(n1, n2), edges = listOf(edge(10, 0, n1, n2)))
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val ends = TrailLegEnds(GeoCoordinate(47.7182, 12.8317), GeoCoordinate(47.7188, 12.8317))

        val route = TrailGraphLegRouter.route(ends, index)

        assertThat(route.snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(route.alternatives).isEmpty()
        assertThat(route.distanceMeters).isWithin(1.0).of(ends.straightDistanceMeters)
    }

    @Test
    fun squareGridRoundTripsRoutingSquareNamesAndAntimeridianTurn() {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7184, 12.8317))
        val name = TrailGraphSquareGrid.squareName(region)
        assertThat(TrailGraphSquareGrid.regionFromSquareName(name)).isEqualTo(region)
        assertThat(TrailGraphSquareGrid.regionFromSquareName("invalid_name")).isNull()
        assertThat(TrailGraphSquareGrid.turn(179.9, -179.9)).isEqualTo(360.0)
        assertThat(TrailGraphSquareGrid.turn(-179.9, 179.9)).isEqualTo(-360.0)
        assertThat(TrailGraphSquareGrid.turn(12.0, 13.0)).isEqualTo(0.0)
    }

    private fun linearTrailGraph(): TrailGraph {
        val n1 = TrailGraphNode(1, 47.71835, 12.8317)
        val n2 = TrailGraphNode(2, 47.71890, 12.8319)
        val n3 = TrailGraphNode(3, 47.71950, 12.8317)
        return TrailGraph(
            nodes = listOf(n1, n2, n3),
            edges = listOf(edge(10, 0, n1, n2), edge(10, 1, n2, n3))
        )
    }

    private fun edge(wayId: Long, seg: Int, from: TrailGraphNode, to: TrailGraphNode): TrailGraphEdge = TrailGraphEdge(
        id = TrailGraphEdgeId(wayId, seg),
        fromNodeId = from.id,
        toNodeId = to.id,
        lengthMeters = RouteGeometry.distanceMeters(from.coordinate, to.coordinate),
        highway = "path"
    )
}
