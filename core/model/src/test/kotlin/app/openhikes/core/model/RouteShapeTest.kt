package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class RouteShapeTest {
    @Test
    fun circleOfRadius300MetersIsClassifiedAsLoop() {
        val centerLat = 47.71842
        val centerLon = 12.83177
        val radiusLat = 300.0 / 111_000.0
        val radiusLon = 300.0 / (111_000.0 * cos(centerLat * PI / 180.0))
        val points = (0..36).map { step ->
            val angle = step * 2.0 * PI / 36.0
            RouteCoordinate(
                latitude = centerLat + sin(angle) * radiusLat,
                longitude = centerLon + cos(angle) * radiusLon
            )
        }

        assertThat(RouteShape.of(points)).isEqualTo(RouteShape.LOOP)
    }

    @Test
    fun retracedPathIsClassifiedAsOutAndBack() {
        val outward = (0..20).map { step ->
            RouteCoordinate(latitude = 47.71842 + step * 0.00045, longitude = 12.83177)
        }
        val route = outward + outward.dropLast(1).reversed()

        assertThat(RouteShape.of(route)).isEqualTo(RouteShape.OUT_AND_BACK)
    }

    @Test
    fun openLineEndingFarFromStartIsPointToPoint() {
        val route = (0..10).map { step ->
            RouteCoordinate(latitude = 47.71842 + step * 0.001, longitude = 12.83177)
        }

        assertThat(RouteShape.of(route)).isEqualTo(RouteShape.POINT_TO_POINT)
    }

    @Test
    fun pauseJumpExceedingClosingToleranceLeavesNoShape() {
        val route = listOf(
            RouteCoordinate(latitude = 47.71842, longitude = 12.83177),
            RouteCoordinate(latitude = 47.72100, longitude = 12.83177),
            RouteCoordinate(latitude = 47.71842, longitude = 12.83177, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(latitude = 47.71850, longitude = 12.83177)
        )

        assertThat(RouteShape.of(route)).isNull()
    }

    @Test
    fun tinyClusterWithinClosingToleranceLeavesNoShape() {
        val route = listOf(
            RouteCoordinate(latitude = 47.71842, longitude = 12.83177),
            RouteCoordinate(latitude = 47.71852, longitude = 12.83177),
            RouteCoordinate(latitude = 47.71842, longitude = 12.83177)
        )

        assertThat(RouteShape.of(route)).isNull()
    }
}
