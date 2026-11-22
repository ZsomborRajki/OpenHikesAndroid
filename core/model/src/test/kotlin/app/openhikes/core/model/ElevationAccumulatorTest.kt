package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElevationAccumulatorTest {
    @Test
    fun deadbandIgnoresSmallJitterAndCommitsLargeClimbsAndDescents() {
        val acc = ElevationAccumulator()
        listOf(500.0, 502.0, 500.5, 512.0, 507.0, 520.0).forEach { acc.record(it) }

        assertThat(acc.count).isEqualTo(6)
        assertThat(acc.minimumMeters).isEqualTo(500.0)
        assertThat(acc.maximumMeters).isEqualTo(520.0)
        assertThat(acc.gainMeters).isWithin(1e-6).of(25.0)
        assertThat(acc.lossMeters).isWithin(1e-6).of(5.0)
    }

    @Test
    fun pauseBoundaryResetsRunWithoutCountingElevationGap() {
        val acc = ElevationAccumulator()
        acc.record(RouteCoordinate(47.71, 12.83, elevationMeters = 500.0))
        acc.record(RouteCoordinate(47.711, 12.831, elevationMeters = 520.0))
        acc.record(RouteCoordinate(47.75, 12.88, elevationMeters = 900.0, boundary = RouteBoundary.PAUSED))
        acc.record(RouteCoordinate(47.751, 12.881, elevationMeters = 910.0))

        assertThat(acc.gainMeters).isWithin(1e-6).of(30.0)
        assertThat(acc.lossMeters).isWithin(1e-6).of(0.0)
    }
}
