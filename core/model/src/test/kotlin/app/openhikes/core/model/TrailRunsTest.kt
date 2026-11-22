package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailRunsTest {
    @Test
    fun splitsPiecesWalkedRunsPausedStretchesAndLoneFixes() {
        val route = listOf(
            RouteCoordinate(47.0, 12.0),
            RouteCoordinate(47.001, 12.001),
            RouteCoordinate(47.01, 12.01, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.02, 12.02, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.021, 12.021)
        )
        val runs = route.toTrailRuns()

        assertThat(runs.isPaused).isTrue()
        assertThat(runs.pausedLegs).containsExactly(1, 2).inOrder()
        assertThat(runs.pieces).containsExactly(0..1, 2..2, 3..4).inOrder()
        assertThat(runs.walkedRuns).containsExactly(0..1, 3..4).inOrder()
        assertThat(runs.pausedStretches).containsExactly(1..3).inOrder()
        assertThat(runs.loneFixes).containsExactly(2)
    }
}
