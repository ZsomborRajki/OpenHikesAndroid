package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalkTimeLeftTest {
    @Test
    fun din33466CombinesHorizontalAndVerticalTimes() {
        // 4 km flat = 1 h; 300 m ascent = 1 h -> max(1, 1) + min(1, 1)/2 = 1.5 h = 5400 s.
        val seconds = WalkingTimeEstimate.seconds(
            distanceMeters = 4_000.0,
            ascentMeters = 300.0,
            descentMeters = 0.0
        )
        assertThat(seconds).isWithin(0.01).of(5_400.0)
    }

    @Test
    fun din33466SanitizesNegativeAndNonFiniteInputs() {
        val seconds = WalkingTimeEstimate.seconds(
            distanceMeters = Double.NaN,
            ascentMeters = -100.0,
            descentMeters = 500.0
        )
        assertThat(seconds).isWithin(0.01).of(3_600.0)
    }

    @Test
    fun walkTimeLeftRequiresElevationOrCalibratedPace() {
        val flatProfile = buildLinearProfile(pointCount = 21, stepLat = 0.001, elevationStart = null)
        val shortCoverage = TrailWalkCoverage()
            .withRecordedMatch(0.0)
            .withRecordedMatch(200.0)

        // Flat with < 1 km walked -> nil.
        val uncalibrated = WalkTimeLeft.seconds(
            profile = flatProfile,
            positionMeters = 200.0,
            coverage = shortCoverage,
            activeSeconds = 180.0
        )
        assertThat(uncalibrated).isNull()

        // Once calibrated (>= 1 km and >= 15 min), returns paced estimate even without heights.
        val longCoverage = TrailWalkCoverage()
            .withRecordedMatch(0.0)
            .withRecordedMatch(400.0)
            .withRecordedMatch(800.0)
            .withRecordedMatch(1_200.0)
        val calibrated = WalkTimeLeft.seconds(
            profile = flatProfile,
            positionMeters = 1_200.0,
            coverage = longCoverage,
            activeSeconds = 1_200.0
        )
        assertThat(calibrated).isNotNull()
        assertThat(calibrated!!).isGreaterThan(0.0)
    }

    @Test
    fun walkTimeLeftUsesElevationAndRespectsDirectionReversal() {
        val uphillProfile =
            buildLinearProfile(pointCount = 21, stepLat = 0.001, elevationStart = 500.0, elevStep = 20.0)
        val total = uphillProfile.totalDistanceMeters
        val half = total / 2.0

        val forwardCoverage = TrailWalkCoverage()
            .withRecordedMatch(0.0)
            .withRecordedMatch(300.0)
            .withRecordedMatch(600.0)
            .withRecordedMatch(half)
        val forwardLeft = WalkTimeLeft.seconds(
            profile = uphillProfile,
            positionMeters = half,
            coverage = forwardCoverage,
            activeSeconds = 300.0
        )

        val reverseCoverage = TrailWalkCoverage()
            .withRecordedMatch(total)
            .withRecordedMatch(total - 300.0)
            .withRecordedMatch(total - 600.0)
            .withRecordedMatch(half)
        val reverseLeft = WalkTimeLeft.seconds(
            profile = uphillProfile,
            positionMeters = half,
            coverage = reverseCoverage,
            activeSeconds = 300.0
        )

        assertThat(forwardLeft).isNotNull()
        assertThat(reverseLeft).isNotNull()
        // Going uphill (300 m/h) takes longer than descending the same height (500 m/h).
        assertThat(forwardLeft!!).isGreaterThan(reverseLeft!!)
        assertThat(WalkTimeLeft.midwayAhead(half, forwardCoverage, total)).isGreaterThan(half)
        assertThat(WalkTimeLeft.midwayAhead(half, reverseCoverage, total)).isLessThan(half)
    }

    @Test
    fun coveredPolylineSegmentsSplitAcrossPauseBoundaries() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000, elevationMeters = 500.0),
            RouteCoordinate(47.7010, 12.8000, elevationMeters = 510.0),
            RouteCoordinate(47.7050, 12.8000, elevationMeters = 550.0, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7060, 12.8000, elevationMeters = 560.0)
        )
        val profile = RouteProfile.from(route)
        val segments = profile.coveredPolylineSegments(listOf(0.0..profile.totalDistanceMeters))
        assertThat(segments).hasSize(2)
        assertThat(segments[0]).hasSize(2)
        assertThat(segments[1]).hasSize(2)
    }

    private fun buildLinearProfile(
        pointCount: Int,
        stepLat: Double,
        elevationStart: Double?,
        elevStep: Double = 0.0
    ): RouteProfile {
        val route = (0 until pointCount).map { index ->
            RouteCoordinate(
                latitude = 47.70 + index * stepLat,
                longitude = 12.80,
                elevationMeters = elevationStart?.plus(index * elevStep)
            )
        }
        return RouteProfile.from(route)
    }
}
