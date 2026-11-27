package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Test

class RouteElevationFillTest {
    @Test
    fun sampleIndexesPinsPauseEndsWhenWithinHalfBudgetAndFallsBackWhenTooManyPauses() {
        val route = List(300) { i ->
            RouteCoordinate(
                latitude = 47.70 + i * 0.0001,
                longitude = 12.80,
                boundary = if (i == 77 || i == 191) RouteBoundary.PAUSED else null
            )
        }
        val sampled = RouteElevationRequest.sampleIndexes(route, limit = 40)
        assertThat(sampled).hasSize(40)
        assertThat(sampled.first()).isEqualTo(0)
        assertThat(sampled.last()).isEqualTo(299)
        assertThat(sampled).containsAtLeast(76, 77, 190, 191)

        // When more than half the budget would be consumed by pause ends, fall back to even spread.
        val heavilyPaused = List(300) { i ->
            RouteCoordinate(
                latitude = 47.70 + i * 0.0001,
                longitude = 12.80,
                boundary = if (i > 0 && i % 5 == 0) RouteBoundary.PAUSED else null
            )
        }
        val fallback = RouteElevationRequest.sampleIndexes(heavilyPaused, limit = 20)
        assertThat(fallback).isEqualTo(RouteElevationRequest.sampleIndexes(count = 300, limit = 20))
    }

    @Test
    fun interpolatingWeightsByDistanceNeverBridgesAcrossPausesAndGuardsRouteChanges() {
        // Uneven horizontal spacing: point 1 is 25% of the ground distance from 0 to 2.
        val uneven = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7040, 12.8000)
        )
        val samples = RouteHeightSamples(
            routePointCount = 3,
            indexes = listOf(0, 2),
            coordinates = listOf(uneven[0], uneven[2]),
            heights = listOf(500.0, 900.0)
        )
        assertThat(samples.describes(uneven)).isTrue()
        val filled = samples.interpolating(uneven)
        assertThat(filled[0].elevationMeters).isWithin(1e-6).of(500.0)
        assertThat(filled[1].elevationMeters).isWithin(0.5).of(600.0)
        assertThat(filled[2].elevationMeters).isWithin(1e-6).of(900.0)

        // Moving a sampled coordinate makes describes() false and leaves route untouched.
        val moved = listOf(uneven[0], uneven[1], uneven[2].copy(latitude = 47.7050))
        assertThat(samples.describes(moved)).isFalse()
        assertThat(samples.interpolating(moved)).isEqualTo(moved)

        // Pause inside a span holds start height up to pause and end height after pause without bridging.
        val paused = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7020, 12.8000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7030, 12.8000),
            RouteCoordinate(47.7040, 12.8000)
        )
        val pausedSamples = RouteHeightSamples(
            routePointCount = paused.size,
            indexes = listOf(0, 4),
            coordinates = listOf(paused[0], paused[4]),
            heights = listOf(400.0, 1_200.0)
        )
        val pausedFilled = pausedSamples.interpolating(paused)
        assertThat(pausedFilled.map { it.elevationMeters }).containsExactly(400.0, 400.0, 1_200.0, 1_200.0, 1_200.0)
            .inOrder()
    }

    @Test
    fun summaryRespectsPauseBoundariesAndHikeElevationFillGuardsMeasuredAndRecordingHikes() = runSuspend {
        val paused = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7020, 12.8000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7030, 12.8000)
        )
        val samples = RouteHeightSamples(
            routePointCount = 4,
            indexes = listOf(0, 1, 2, 3),
            coordinates = paused,
            heights = listOf(500.0, 600.0, 1_000.0, 950.0)
        )
        val summary = samples.summary
        assertThat(summary.gainMeters).isWithin(1e-6).of(100.0)
        assertThat(summary.lossMeters).isWithin(1e-6).of(50.0)
        assertThat(summary.highMeters).isWithin(1e-6).of(1_000.0)
        assertThat(summary.lowMeters).isWithin(1e-6).of(500.0)

        val flatHike = Hike(
            id = HikeId("flat"),
            title = "Flat",
            distanceMeters = 500.0,
            dateEpochMillis = 1_000L,
            route = paused
        )
        assertThat(HikeElevationFill.canFill(flatHike)).isTrue()
        assertThat(HikeElevationFill.canFill(flatHike.copy(isRecording = true))).isFalse()

        val singleHeight = paused.mapIndexed { idx, pt -> if (idx == 2) pt.copy(elevationMeters = 700.0) else pt }
        assertThat(HikeElevationFill.needsHeights(singleHeight)).isFalse()

        // Source returning a NaN reading causes samplesOf() to reject the whole response.
        val withheldSource = object : RouteElevationSource {
            override suspend fun heights(coordinates: List<GeoCoordinate>): ElevationFetchResult =
                ElevationFetchResult.Heights(listOf(500.0, Double.NaN, 600.0, 650.0))
        }
        assertThat(withheldSource.samplesOf(paused)).isNull()
        assertThat(withheldSource.fillRoute(paused)).isEqualTo(paused)
    }

    private fun runSuspend(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) {
                    outcome = result
                }
            }
        )
        requireNotNull(outcome).getOrThrow()
    }
}
