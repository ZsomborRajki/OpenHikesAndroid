package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteTrimTest {
    private val baseMillis = 1_780_000_000_000L

    private fun straightRoute(pointCount: Int = 5): List<RouteCoordinate> = List(pointCount) { index ->
        RouteCoordinate(
            latitude = 47.71842 + index * 0.001,
            longitude = 12.83177,
            elevationMeters = 500.0 + index * 20.0,
            timestampEpochMillis = baseMillis + index * 60_000L
        )
    }

    @Test
    fun trimMidLegInterpolatesCoordinateElevationAndTimestamp() {
        val route = straightRoute(3)
        val distances = RouteTrim.distances(route)
        val midFirst = distances[1] / 2.0
        val midSecond = (distances[1] + distances[2]) / 2.0

        val trimmed = RouteTrim.trimmed(route, startMeters = midFirst, endMeters = midSecond, distances = distances)

        assertThat(trimmed).hasSize(3)
        assertThat(trimmed.first().elevationMeters!!).isWithin(0.1).of(510.0)
        assertThat(trimmed.first().timestampEpochMillis).isEqualTo(baseMillis + 30_000L)
        assertThat(trimmed.last().elevationMeters!!).isWithin(0.1).of(530.0)
        assertThat(trimmed.last().timestampEpochMillis).isEqualTo(baseMillis + 90_000L)
        assertThat(trimmed.first().boundary).isNull()
        assertThat(trimmed.first().provenance).isNull()
    }

    @Test
    fun cutWithinSnapMetersTakesOriginalVertex() {
        val route = straightRoute(4)
        val distances = RouteTrim.distances(route)

        val trimmed = RouteTrim.trimmed(
            route = route,
            startMeters = distances[1] + 0.3,
            endMeters = distances[2] - 0.3,
            distances = distances
        )

        assertThat(trimmed).containsExactly(route[1], route[2]).inOrder()
    }

    @Test
    fun cutAcrossPauseStartsAfterPauseAndEndsBeforePause() {
        val route = listOf(
            RouteCoordinate(47.71842, 12.83177, 500.0, baseMillis),
            RouteCoordinate(47.71942, 12.83177, 510.0, baseMillis + 60_000L),
            RouteCoordinate(47.72500, 12.83177, 600.0, baseMillis + 300_000L, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.72600, 12.83177, 610.0, baseMillis + 360_000L)
        )
        val distances = RouteTrim.distances(route)
        val pauseAt = distances[1]

        val afterPause = RouteTrim.trimmed(
            route,
            startMeters = pauseAt,
            endMeters = distances.last(),
            distances = distances
        )
        assertThat(afterPause).hasSize(2)
        assertThat(afterPause.first().latitude).isWithin(1e-6).of(47.72500)
        assertThat(afterPause.first().boundary).isNull()

        val beforePause = RouteTrim.trimmed(route, startMeters = 0.0, endMeters = pauseAt, distances = distances)
        assertThat(beforePause).hasSize(2)
        assertThat(beforePause.last().latitude).isWithin(1e-6).of(47.71942)
    }

    @Test
    fun endPausesOptionStripsTrailingPauseAtRouteEnd() {
        val route = listOf(
            RouteCoordinate(47.71842, 12.83177, 500.0, baseMillis),
            RouteCoordinate(47.71942, 12.83177, 510.0, baseMillis + 60_000L),
            RouteCoordinate(47.74000, 12.83177, 520.0, baseMillis + 600_000L, boundary = RouteBoundary.PAUSED)
        )
        val distances = RouteTrim.distances(route)

        val keptWithPause = RouteTrim.trimmed(route, 0.0, distances.last(), distances)
        assertThat(keptWithPause).hasSize(3)

        val stripped = RouteTrim.trimmed(
            route = route,
            startMeters = 0.0,
            endMeters = distances.last(),
            distances = distances,
            takingOff = RouteTrim.EndPauses.END
        )
        assertThat(stripped).hasSize(2)
        assertThat(stripped.last().latitude).isWithin(1e-6).of(47.71942)
    }

    @Test
    fun recordedSecondsSkipsPausedGapAndPreservesUntimedInferredCrossing() {
        val route = listOf(
            RouteCoordinate(47.71842, 12.83177, timestampEpochMillis = baseMillis),
            RouteCoordinate(47.71942, 12.83177, timestampEpochMillis = null, provenance = RouteProvenance.INFERRED),
            RouteCoordinate(47.72042, 12.83177, timestampEpochMillis = baseMillis + 120_000L),
            RouteCoordinate(
                47.72500,
                12.83177,
                timestampEpochMillis = baseMillis + 600_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(47.72600, 12.83177, timestampEpochMillis = baseMillis + 660_000L)
        )

        assertThat(RouteTrim.recordedSeconds(route)).isWithin(0.01).of(180.0)
    }
}
