package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteGeometryTest {
    @Test
    fun distanceBetweenSamePointIsZeroAndLegSkipsPauseBoundary() {
        val start = GeoCoordinate(47.718420, 12.831774)
        val next = GeoCoordinate(47.719219, 12.830877)
        val distant = GeoCoordinate(47.730000, 12.850000)

        assertThat(RouteGeometry.distanceMeters(start, start)).isEqualTo(0.0)
        val leg = RouteGeometry.distanceMeters(start, next)
        assertThat(leg).isWithin(5.0).of(111.0)

        val pausedRoute = listOf(
            RouteCoordinate(start.latitude, start.longitude),
            RouteCoordinate(next.latitude, next.longitude),
            RouteCoordinate(distant.latitude, distant.longitude, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.730800, 12.850900)
        )
        val expectedLength = RouteGeometry.distanceMeters(start, next) +
            RouteGeometry.distanceMeters(distant, GeoCoordinate(47.730800, 12.850900))
        assertThat(RouteGeometry.routeLengthMeters(pausedRoute)).isWithin(1e-6).of(expectedLength)
    }

    @Test
    fun projectAndInterpolateClampToSegmentAndNormalizeAntimeridian() {
        val start = GeoCoordinate(0.0, 179.9)
        val end = GeoCoordinate(0.0, -179.9)
        val midpoint = RouteGeometry.interpolate(start, end, 0.5)
        assertThat(midpoint.longitude).isWithin(1e-6).of(180.0)

        val projection = RouteGeometry.project(
            coordinate = GeoCoordinate(0.001, 180.0),
            start = start,
            end = end
        )
        assertThat(projection.fraction).isWithin(1e-2).of(0.5)
        assertThat(projection.offRouteMeters).isWithin(5.0).of(111.2)
    }

    @Test
    fun bearingDifferenceWrapsCircularAnglesCorrectly() {
        assertThat(RouteGeometry.bearingDifference(350.0, 10.0)).isWithin(1e-6).of(20.0)
        assertThat(RouteGeometry.bearingDifference(180.0, 0.0)).isWithin(1e-6).of(180.0)
    }
}
