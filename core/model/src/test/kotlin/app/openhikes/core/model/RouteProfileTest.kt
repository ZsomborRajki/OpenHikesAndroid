package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteProfileTest {
    @Test
    fun buildsMonotonicDistancesPauseMarkersInterpolationAndProjection() {
        val route = listOf(
            RouteCoordinate(47.7180, 12.8300, elevationMeters = 500.0),
            RouteCoordinate(47.7190, 12.8300, elevationMeters = 520.0),
            RouteCoordinate(47.7300, 12.8500, elevationMeters = 600.0, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7310, 12.8500, elevationMeters = 620.0)
        )
        val profile = RouteProfile.from(route)

        assertThat(profile.totalDistanceMeters).isWithin(5.0).of(222.4)
        assertThat(profile.pauseDistances).hasSize(1)
        assertThat(profile.pauseDistances.first()).isWithin(2.0).of(111.2)
        assertThat(profile.segments).hasSize(2)
        assertThat(profile.elevation.gainMeters).isWithin(1e-6).of(40.0)

        val halfwayCoord = profile.coordinateAt(profile.totalDistanceMeters / 2.0)!!
        assertThat(halfwayCoord.latitude).isWithin(0.02).of(47.725)

        val sample = profile.sampleAt(profile.totalDistanceMeters)!!
        assertThat(sample.elevationMeters).isEqualTo(620.0)

        val projection = profile.nearestPoint(GeoCoordinate(47.7185, 12.8300))!!
        assertThat(projection.offRouteMeters).isLessThan(1.0)
    }

    @Test
    fun downsamplesLargeRouteWithinBudgetAndPreservesPauseFlag() {
        val points = (0 until 1200).map { i ->
            RouteCoordinate(
                latitude = 47.7 + i * 0.0001,
                longitude = 12.8,
                elevationMeters = 500.0 + (i % 25) * 4.0,
                boundary = if (i == 600) RouteBoundary.PAUSED else null
            )
        }
        val profile = RouteProfile.from(points)

        assertThat(profile.samples.size).isAtMost(RouteProfile.PLOTTED_SAMPLE_BUDGET)
        assertThat(profile.samples.any { it.resumesAfterPause }).isTrue()
        assertThat(profile.samples.first().distanceMeters).isEqualTo(0.0)
        assertThat(profile.samples.last().distanceMeters).isWithin(1e-6).of(profile.totalDistanceMeters)
    }

    @Test
    fun continuityTieBreakStaysInsideMatchRadiusWhenCloserCandidateMatches() {
        // Straight north-south 3-point route (~222 m total), split around 111 m.
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7020, 12.8000)
        )
        val profile = RouteProfile.from(route)
        // Fix sits 69 m east of the second segment (46 m north of vertex 1 -> ~83 m from segment 0's end).
        val latOffsetMeters = 46.0
        val eastOffsetMeters = 69.0
        val lat = 47.7010 + latOffsetMeters / 111_111.0
        val lon = 12.8000 + eastOffsetMeters / (111_111.0 * kotlin.math.cos(Math.toRadians(47.7010)))

        val projection = profile.nearestPoint(
            coordinate = GeoCoordinate(lat, lon),
            nearDistanceMeters = 0.0
        )!!

        assertThat(projection.offRouteMeters).isAtMost(RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS)
        assertThat(projection.offRouteMeters).isWithin(2.0).of(69.0)
    }
}
