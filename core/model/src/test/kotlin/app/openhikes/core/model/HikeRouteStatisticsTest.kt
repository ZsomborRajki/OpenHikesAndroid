package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikeRouteStatisticsTest {
    @Test
    fun computesDurationsSpeedsAndSkipsPauseLegAndStationaryIntervals() {
        val baseMillis = 1_700_000_000_000L
        val route = listOf(
            RouteCoordinate(47.7180, 12.8300, elevationMeters = 500.0, timestampEpochMillis = baseMillis),
            RouteCoordinate(47.7190, 12.8300, elevationMeters = 515.0, timestampEpochMillis = baseMillis + 60_000L),
            RouteCoordinate(
                latitude = 47.719001,
                longitude = 12.830001,
                elevationMeters = 515.0,
                timestampEpochMillis = baseMillis + 180_000L
            ),
            RouteCoordinate(
                latitude = 47.7500,
                longitude = 12.8600,
                elevationMeters = 600.0,
                timestampEpochMillis = baseMillis + 300_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(
                latitude = 47.7510,
                longitude = 12.8600,
                elevationMeters = 610.0,
                timestampEpochMillis = baseMillis + 360_000L
            )
        )
        val distance = RouteGeometry.routeLengthMeters(route)
        val stats = HikeRouteStatistics.from(distance, route)

        assertThat(stats.pointCount).isEqualTo(5)
        assertThat(stats.durationSeconds).isWithin(1e-6).of(360.0)
        assertThat(stats.movingDurationSeconds!!).isLessThan(stats.durationSeconds!!)
        assertThat(stats.elevationGainMeters).isWithin(1e-6).of(25.0)
        assertThat(stats.maxSpeedMps!!).isLessThan(MovementThresholds.MAXIMUM_ON_FOOT_SPEED_MPS)
        assertThat(stats.averageSpeedMps!!).isGreaterThan(0.0)
    }
}
