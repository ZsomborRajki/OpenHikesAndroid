package app.openhikes.core.map

import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapRouteOverlayTest {
    @Test
    fun splitsWalkedAndPausedSegmentsAndCarriesStyleAndSelectionFlag() {
        val mapRoute = HikeMapRoute(
            hikeId = HikeId("h1"),
            route = listOf(
                RouteCoordinate(47.71, 12.83),
                RouteCoordinate(47.72, 12.84),
                RouteCoordinate(47.75, 12.87, boundary = RouteBoundary.PAUSED),
                RouteCoordinate(47.76, 12.88)
            ),
            tintHex = "#D85A17",
            routeBorderHex = RouteBorder.WHITE_HEX,
            routeWidthDp = 5.0,
            linePattern = RouteLinePattern.DASHED
        )

        val overlay = mapRoute.toMapRouteOverlay(isSelected = true)

        assertThat(overlay.isSelected).isTrue()
        assertThat(overlay.walkedSegments).hasSize(2)
        assertThat(overlay.pausedSegments).hasSize(1)
        assertThat(overlay.pausedSegments.first()).hasSize(2)
        assertThat(overlay.widthDp).isEqualTo(5.0f)
        assertThat(overlay.linePattern).isEqualTo(RouteLinePattern.DASHED)
    }
}
