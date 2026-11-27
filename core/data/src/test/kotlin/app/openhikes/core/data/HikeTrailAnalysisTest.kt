package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeTrailAnalysis
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteShadeBlend
import app.openhikes.core.model.RouteShadeStretch
import app.openhikes.core.model.TrailDifficulty
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphEdge
import app.openhikes.core.model.TrailGraphEdgeId
import app.openhikes.core.model.TrailGraphNode
import app.openhikes.core.model.TrailGraphRegion
import app.openhikes.core.model.TrailGraphSquareGrid
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailSurface
import app.openhikes.core.network.RoutingGraphSquareDataSource
import app.openhikes.core.network.RoutingSquareReadResult
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HikeTrailAnalysisTest {
    @Test
    fun analyzesThumseeLoopFixtureWithHighSurveyedCoverageAndZeroUnmapped() = runTest {
        val parsed = GpxParser.parseSingle(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val route = parsed.value.route
        val provider = FakeTrailGraphProvider.fromThumseeLoopFixture()

        val breakdowns = HikeTrailAnalysis.breakdowns(route, provider)

        assertThat(breakdowns.isEmpty).isFalse()
        assertThat(provider.assembledGraphCalls).isEqualTo(1)
        val surface = requireNotNull(breakdowns.surface)
        val difficulty = requireNotNull(breakdowns.difficulty)
        assertThat(surface.surveyedFraction).isAtLeast(0.95)
        assertThat(surface.metersFor(TrailSurface.UNMAPPED)).isEqualTo(0.0)
        assertThat(surface.shares.filter { it.category.isSurveyed }.map { it.category })
            .containsAtLeast(TrailSurface.GRAVEL, TrailSurface.GROUND, TrailSurface.PAVED)

        assertThat(difficulty.surveyedFraction).isAtLeast(0.95)
        assertThat(difficulty.metersFor(TrailDifficulty.UNMAPPED)).isEqualTo(0.0)
        assertThat(difficulty.shares.filter { it.category.isSurveyed }.map { it.category })
            .containsAtLeast(TrailDifficulty.HIKING, TrailDifficulty.MOUNTAIN_HIKING)

        // Surveyed difficulty runs sum to the same meters as the difficulty breakdown.
        val runs = HikeTrailAnalysis.difficultyRuns(route, provider, allowNetwork = true)
        assertThat(runs).isNotEmpty()
        assertThat(runs.all { it.category.isSurveyed }).isTrue()
    }

    @Test
    fun degenerateAndIncompleteOrOutageGraphsReturnEmptyWhileCompleteUncoveredReturnsUnmapped() = runTest {
        val provider = FakeTrailGraphProvider.fromThumseeLoopFixture()
        val singlePoint = listOf(RouteCoordinate(47.718, 12.831))
        assertThat(HikeTrailAnalysis.breakdowns(singlePoint, provider).isEmpty).isTrue()
        assertThat(provider.assembledGraphCalls).isEqualTo(0)

        val farRoute = listOf(
            RouteCoordinate(46.000, 10.000),
            RouteCoordinate(46.005, 10.000)
        )
        val uncovered = HikeTrailAnalysis.breakdowns(farRoute, provider)
        assertThat(uncovered.surface?.surveyedFraction).isEqualTo(0.0)
        assertThat(uncovered.surface?.metersFor(TrailSurface.UNMAPPED)).isEqualTo(uncovered.surface?.totalMeters)
        assertThat(uncovered.difficulty?.surveyedFraction).isEqualTo(0.0)
        assertThat(uncovered.difficulty?.metersFor(TrailDifficulty.UNMAPPED))
            .isEqualTo(uncovered.difficulty?.totalMeters)

        val incompleteProvider = FakeTrailGraphProvider(
            graph = FakeTrailGraphProvider.fromThumseeLoopFixture().assembledGraph(emptyList()).graph,
            isComplete = false
        )
        assertThat(HikeTrailAnalysis.breakdowns(farRoute, incompleteProvider).isEmpty).isTrue()

        val outageProvider = FakeTrailGraphProvider(
            graph = null,
            outage = TrailLegOutage.OFFLINE
        )
        assertThat(HikeTrailAnalysis.breakdowns(farRoute, outageProvider).isEmpty).isTrue()
    }

    @Test
    fun cacheOnlyDifficultyRunsReadsCachedGraphWithoutAssemblingNetworkGraph() = runTest {
        val parsed = GpxParser.parseSingle(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val route = parsed.value.route
        val provider = FakeTrailGraphProvider.fromThumseeLoopFixture()

        val runs = HikeTrailAnalysis.difficultyRuns(route, provider, allowNetwork = false)

        assertThat(runs).isNotEmpty()
        assertThat(provider.cachedGraphOnlyCalls).isEqualTo(1)
        assertThat(provider.assembledGraphCalls).isEqualTo(0)
    }

    @Test
    fun archiveProviderCachedGraphOnlyNeverCallsReadRegion() = runTest {
        val n1 = TrailGraphNode(1L, 47.718, 12.831)
        val n2 = TrailGraphNode(2L, 47.719, 12.831)
        val graph = TrailGraph(
            nodes = listOf(n1, n2),
            edges = listOf(
                TrailGraphEdge(
                    id = TrailGraphEdgeId(10L, 0),
                    fromNodeId = 1L,
                    toNodeId = 2L,
                    lengthMeters = 111.0,
                    highway = "path",
                    sacScale = "hiking"
                )
            )
        )
        var networkCalls = 0
        var cacheCalls = 0
        val squareSource = object : RoutingGraphSquareDataSource {
            override suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult {
                networkCalls += 1
                return RoutingSquareReadResult.Failed(TrailLegOutage.OFFLINE)
            }

            override suspend fun readCachedRegion(region: TrailGraphRegion): TrailGraph? {
                cacheCalls += 1
                return graph
            }
        }
        val provider = ArchiveTrailGraphProvider(
            routingSquareDataSource = squareSource,
            bundledFixtureSource = object : BundledTrailGraphFixtureSource {
                override suspend fun loadFixture(fixtureName: String): TrailGraph? = null
            },
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
        )

        val cached = provider.cachedGraphOnly(listOf(GeoCoordinate(47.718, 12.831), GeoCoordinate(47.719, 12.831)))

        assertThat(cached?.edges).hasSize(1)
        assertThat(cacheCalls).isAtLeast(1)
        assertThat(networkCalls).isEqualTo(0)
    }

    @Test
    fun excludesPauseGapsFromGraphCoverageInNetworkAndCacheOnlyPaths() = runTest {
        // Two ~111 m walked segments separated by > 24 z12 tiles (~2.6 degrees longitude) across a pause.
        val firstStart = GeoCoordinate(47.7100, 10.0100)
        val firstEnd = GeoCoordinate(47.7110, 10.0100)
        val secondStart = GeoCoordinate(47.7100, 12.6100)
        val secondEnd = GeoCoordinate(47.7110, 12.6100)
        val route = listOf(
            RouteCoordinate(firstStart.latitude, firstStart.longitude),
            RouteCoordinate(firstEnd.latitude, firstEnd.longitude),
            RouteCoordinate(secondStart.latitude, secondStart.longitude, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(secondEnd.latitude, secondEnd.longitude)
        )
        val firstTile = TrailGraphSquareGrid.regionContaining(firstStart)
        val secondTile = TrailGraphSquareGrid.regionContaining(secondStart)
        assertThat(secondTile.x - firstTile.x).isGreaterThan(24)

        val graphsByTile = mapOf(
            firstTile to singleWayGraph(1L, firstStart, firstEnd, surface = "gravel", sacScale = "hiking"),
            secondTile to singleWayGraph(2L, secondStart, secondEnd, surface = "rock", sacScale = "alpine_hiking")
        )
        val networkRequested = mutableListOf<TrailGraphRegion>()
        val cacheRequested = mutableListOf<TrailGraphRegion>()
        val provider = archiveProvider(
            graphsByTile = graphsByTile,
            networkRequested = networkRequested,
            cacheRequested = cacheRequested
        )

        val breakdowns = HikeTrailAnalysis.breakdowns(route, provider)
        assertThat(networkRequested).containsExactly(firstTile, secondTile)
        assertThat(breakdowns.surface?.surveyedFraction).isWithin(1e-6).of(1.0)
        assertThat(breakdowns.difficulty?.surveyedFraction).isWithin(1e-6).of(1.0)

        val cachedRuns = HikeTrailAnalysis.difficultyRuns(route, provider, allowNetwork = false)
        assertThat(cacheRequested).containsExactly(firstTile, secondTile)
        assertThat(cachedRuns.map { it.category }).containsExactly(
            TrailDifficulty.HIKING,
            TrailDifficulty.ALPINE_HIKING
        ).inOrder()
        assertThat(cachedRuns[1].joinsPrevious).isFalse()
    }

    @Test
    fun includesNeighboringTilesWithinMatchingToleranceForCoverageAndCompleteness() = runTest {
        val centerTile = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7100, 12.8300))
        val neighborTile = TrailGraphRegion(centerTile.zoom, centerTile.x + 1, centerTile.y)
        val box = centerTile.toGeoBoundingBox()
        val lat = (box.southLat + box.northLat) / 2.0
        val metersPerDegreeLon = RouteGeometry.METERS_PER_DEGREE_LATITUDE * cos(lat * PI / 180.0)
        val offsetDeg = 7.5 / metersPerDegreeLon

        // Route lies 7.5 m west of the tile edge; graded OSM way lies 7.5 m east (15 m apart, inside 25 m).
        val routeStart = GeoCoordinate(lat, box.eastLon - offsetDeg)
        val routeEnd = GeoCoordinate(lat + 0.001, box.eastLon - offsetDeg)
        val wayStart = GeoCoordinate(lat, box.eastLon + offsetDeg)
        val wayEnd = GeoCoordinate(lat + 0.001, box.eastLon + offsetDeg)
        val route = listOf(
            RouteCoordinate(routeStart.latitude, routeStart.longitude),
            RouteCoordinate(routeEnd.latitude, routeEnd.longitude)
        )
        val neighborGraph = singleWayGraph(99L, wayStart, wayEnd, surface = "gravel", sacScale = "mountain_hiking")

        // When both centerline and neighbor tiles load, route matches the neighbor's graded way 100%.
        val requested = mutableListOf<TrailGraphRegion>()
        val cacheRequested = mutableListOf<TrailGraphRegion>()
        val completeProvider = archiveProvider(
            graphsByTile = mapOf(centerTile to TrailGraph.Empty, neighborTile to neighborGraph),
            networkRequested = requested,
            cacheRequested = cacheRequested
        )
        val breakdowns = HikeTrailAnalysis.breakdowns(route, completeProvider)
        assertThat(requested).containsAtLeast(centerTile, neighborTile)
        assertThat(breakdowns.surface?.surveyedFraction).isWithin(1e-6).of(1.0)
        assertThat(breakdowns.difficulty?.surveyedFraction).isWithin(1e-6).of(1.0)
        assertThat(breakdowns.difficulty?.dominant?.category).isEqualTo(TrailDifficulty.MOUNTAIN_HIKING)

        // Cache-only shading also inspects the neighbor tile within matching tolerance.
        val cachedRuns = HikeTrailAnalysis.difficultyRuns(route, completeProvider, allowNetwork = false)
        assertThat(cacheRequested).containsAtLeast(centerTile, neighborTile)
        assertThat(cachedRuns.map { it.category }).containsExactly(TrailDifficulty.MOUNTAIN_HIKING)

        // If the neighbor tile fails to download, coverage is incomplete and never persists a false 0%.
        val partialProvider = archiveProvider(
            graphsByTile = mapOf(centerTile to TrailGraph.Empty),
            networkRequested = mutableListOf(),
            cacheRequested = mutableListOf()
        )
        assertThat(HikeTrailAnalysis.breakdowns(route, partialProvider).isEmpty).isTrue()
    }

    @Test
    fun filteringOutUnsurveyedLoopClearsContinuityBetweenAdjacentSurveyedRuns() = runTest {
        val a = GeoCoordinate(47.700, 12.830)
        val b = GeoCoordinate(47.702, 12.830)
        val detour = GeoCoordinate(47.702, 12.832)
        val c = GeoCoordinate(47.704, 12.830)
        val graph = TrailGraph(
            nodes = listOf(
                TrailGraphNode(1L, a.latitude, a.longitude),
                TrailGraphNode(2L, b.latitude, b.longitude),
                TrailGraphNode(3L, detour.latitude, detour.longitude),
                TrailGraphNode(4L, c.latitude, c.longitude)
            ),
            edges = listOf(
                TrailGraphEdge(
                    id = TrailGraphEdgeId(1L, 0),
                    fromNodeId = 1L,
                    toNodeId = 2L,
                    lengthMeters = RouteGeometry.distanceMeters(a, b),
                    highway = "path",
                    sacScale = "hiking"
                ),
                TrailGraphEdge(
                    id = TrailGraphEdgeId(2L, 0),
                    fromNodeId = 2L,
                    toNodeId = 3L,
                    lengthMeters = RouteGeometry.distanceMeters(b, detour),
                    highway = "path",
                    sacScale = null
                ),
                TrailGraphEdge(
                    id = TrailGraphEdgeId(3L, 0),
                    fromNodeId = 2L,
                    toNodeId = 4L,
                    lengthMeters = RouteGeometry.distanceMeters(b, c),
                    highway = "path",
                    sacScale = "alpine_hiking"
                )
            )
        )
        val route = listOf(a, b, detour, b, c).map { RouteCoordinate(it.latitude, it.longitude) }
        val runs = HikeTrailAnalysis.difficultyRuns(route, FakeTrailGraphProvider(graph = graph))
        assertThat(runs.map { it.category }).containsExactly(
            TrailDifficulty.HIKING,
            TrailDifficulty.ALPINE_HIKING
        ).inOrder()
        assertThat(runs[1].joinsPrevious).isFalse()

        val blended = RouteShadeBlend.blend(
            runs.map { RouteShadeStretch(requireNotNull(it.category.shade), it.coordinates, it.joinsPrevious) }
        )
        assertThat(blended).hasSize(2)
    }

    private fun archiveProvider(
        graphsByTile: Map<TrailGraphRegion, TrailGraph>,
        networkRequested: MutableList<TrailGraphRegion>,
        cacheRequested: MutableList<TrailGraphRegion>
    ): ArchiveTrailGraphProvider = ArchiveTrailGraphProvider(
        routingSquareDataSource = object : RoutingGraphSquareDataSource {
            override suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult {
                networkRequested += region
                return graphsByTile[region]?.let { RoutingSquareReadResult.Graph(it) }
                    ?: RoutingSquareReadResult.Failed(TrailLegOutage.OFFLINE)
            }

            override suspend fun readCachedRegion(region: TrailGraphRegion): TrailGraph? {
                cacheRequested += region
                return graphsByTile[region]
            }
        },
        bundledFixtureSource = object : BundledTrailGraphFixtureSource {
            override suspend fun loadFixture(fixtureName: String): TrailGraph? = null
        },
        launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
    )

    private fun singleWayGraph(
        wayId: Long,
        start: GeoCoordinate,
        end: GeoCoordinate,
        surface: String,
        sacScale: String
    ): TrailGraph = TrailGraph(
        nodes = listOf(
            TrailGraphNode(wayId * 10L + 1L, start.latitude, start.longitude),
            TrailGraphNode(wayId * 10L + 2L, end.latitude, end.longitude)
        ),
        edges = listOf(
            TrailGraphEdge(
                id = TrailGraphEdgeId(wayId, 0),
                fromNodeId = wayId * 10L + 1L,
                toNodeId = wayId * 10L + 2L,
                lengthMeters = RouteGeometry.distanceMeters(start, end),
                highway = "path",
                surface = surface,
                sacScale = sacScale
            )
        )
    )
}
