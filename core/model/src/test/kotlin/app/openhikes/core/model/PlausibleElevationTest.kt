package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlausibleElevationTest {
    @Test
    fun validatedAcceptsPlausibleBoundsAndDropsOutliers() {
        assertThat(PlausibleElevation.validated(535.0)).isEqualTo(535.0)
        assertThat(PlausibleElevation.validated(-500.0)).isEqualTo(-500.0)
        assertThat(PlausibleElevation.validated(9000.0)).isEqualTo(9000.0)
        assertThat(PlausibleElevation.validated(-501.0)).isNull()
        assertThat(PlausibleElevation.validated(9001.0)).isNull()
        assertThat(PlausibleElevation.validated(Double.NaN)).isNull()
    }

    @Test
    fun despikedClearsIsolatedImpossibleSpikeAndRespectsPauseBoundaries() {
        val route = listOf(
            RouteCoordinate(47.7180, 12.8300, elevationMeters = 530.0),
            RouteCoordinate(47.7182, 12.8302, elevationMeters = 1200.0),
            RouteCoordinate(47.7184, 12.8304, elevationMeters = 534.0),
            RouteCoordinate(47.7300, 12.8500, elevationMeters = 900.0, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7303, 12.8503, elevationMeters = 905.0)
        )
        val cleaned = PlausibleElevation.despiked(route)

        assertThat(cleaned[0].elevationMeters).isEqualTo(530.0)
        assertThat(cleaned[1].elevationMeters).isNull()
        assertThat(cleaned[2].elevationMeters).isEqualTo(534.0)
        assertThat(cleaned[3].elevationMeters).isEqualTo(900.0)
        assertThat(cleaned[4].elevationMeters).isEqualTo(905.0)
    }
}
