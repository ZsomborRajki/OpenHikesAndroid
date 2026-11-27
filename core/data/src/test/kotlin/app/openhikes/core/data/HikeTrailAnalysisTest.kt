package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeTrailAnalysis
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailDifficulty
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphEdge
import app.openhikes.core.model.TrailGraphEdgeId
import app.openhikes.core.model.TrailGraphNode
import app.openhikes.core.model.TrailGraphRegion
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailSurface
import app.openhikes.core.network.RoutingGraphSquareDataSource
import app.openhikes.core.network.RoutingSquareReadResult
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
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
}
