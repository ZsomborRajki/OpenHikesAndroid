package app.openhikes.core.map

import androidx.compose.ui.graphics.Color
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

    @Test
    fun directionalAndArrowheadsRenderChevronsWithDistinctLineVisibilityAndTintContrast() {
        val directionalPlan = buildWalkedRouteRenderPlan(
            sampleOverlay(
                pattern = RouteLinePattern.DIRECTIONAL,
                tintHex = "#1B7F3B",
                borderHex = RouteBorder.WHITE_HEX
            )
        )
        assertThat(directionalPlan.drawsLine).isTrue()
        assertThat(directionalPlan.lineDashArray).isNull()
        assertThat(directionalPlan.chevronSpec).isNotNull()
        assertThat(directionalPlan.chevronSpec!!.borderColor).isNull()
        assertThat(directionalPlan.chevronSpec.color.red).isWithin(0.01f).of(1.0f)

        val brightDirectional = buildWalkedRouteRenderPlan(
            sampleOverlay(pattern = RouteLinePattern.DIRECTIONAL, tintHex = "#FFFFFF", borderHex = RouteBorder.NONE_HEX)
        )
        assertThat(brightDirectional.chevronSpec!!.color.red).isWithin(0.01f).of(0.15f)

        val arrowheadsPlan = buildWalkedRouteRenderPlan(
            sampleOverlay(pattern = RouteLinePattern.ARROWHEADS, tintHex = "#D85A17", borderHex = RouteBorder.WHITE_HEX)
        )
        assertThat(arrowheadsPlan.drawsLine).isFalse()
        assertThat(arrowheadsPlan.borderWidthDp).isNull()
        assertThat(arrowheadsPlan.lineDashArray).isNull()
        assertThat(arrowheadsPlan.borderDashArray).isNull()
        assertThat(arrowheadsPlan.chevronSpec).isNotNull()
        assertThat(arrowheadsPlan.chevronSpec!!.color).isEqualTo(Color(0xFFD85A17))
        assertThat(arrowheadsPlan.chevronSpec.borderColor).isEqualTo(Color.White)

        val solidPlan = buildWalkedRouteRenderPlan(
            sampleOverlay(pattern = RouteLinePattern.SOLID, tintHex = "#1B7F3B", borderHex = RouteBorder.NONE_HEX)
        )
        assertThat(solidPlan.drawsLine).isTrue()
        assertThat(solidPlan.lineDashArray).isNull()
        assertThat(solidPlan.chevronSpec).isNull()
    }

    @Test
    fun scalesDashArraysByEachLayersRenderedWidthAndKeepsBorderInPhase() {
        val dashedPlan = buildWalkedRouteRenderPlan(
            sampleOverlay(
                pattern = RouteLinePattern.DASHED,
                widthDp = 12f,
                borderHex = RouteBorder.WHITE_HEX
            )
        )
        assertThat(dashedPlan.chevronSpec).isNull()
        assertThat(dashedPlan.lineWidthDp).isEqualTo(12f)
        assertThat(dashedPlan.borderWidthDp).isEqualTo(20f)
        // MapLibre multiplies dasharray entries by layer width: 3.0 * 12 = 36 dp, 2.5 * 12 = 30 dp.
        assertThat(dashedPlan.lineDashArray!!.map { it.toDouble() }).containsExactly(3.0, 2.5).inOrder()
        // Border is 20 dp wide: 1.8 * 20 = 36 dp, 1.5 * 20 = 30 dp -> same physical pattern & phase.
        assertThat(dashedPlan.borderDashArray!!.map { it.toDouble() }).containsExactly(1.8, 1.5).inOrder()

        val dottedPlan = buildWalkedRouteRenderPlan(
            sampleOverlay(
                pattern = RouteLinePattern.DOTTED,
                widthDp = 6f,
                borderHex = RouteBorder.WHITE_HEX
            )
        )
        val fgScaledDp = dottedPlan.lineDashArray!!.map { it.toDouble() * dottedPlan.lineWidthDp }
        val borderScaledDp = dottedPlan.borderDashArray!!.map { it.toDouble() * dottedPlan.borderWidthDp!! }
        assertThat(fgScaledDp[0]).isWithin(1e-6).of(0.1)
        assertThat(fgScaledDp[1]).isWithin(1e-6).of(12.0)
        assertThat(borderScaledDp[0]).isWithin(1e-6).of(0.1)
        assertThat(borderScaledDp[1]).isWithin(1e-6).of(12.0)
    }

    private fun sampleOverlay(
        pattern: RouteLinePattern,
        tintHex: String = "#1B7F3B",
        borderHex: String = RouteBorder.NONE_HEX,
        widthDp: Float = 4f
    ): MapRouteOverlay = MapRouteOverlay(
        hikeId = HikeId("h1"),
        walkedSegments = listOf(
            listOf(
                app.openhikes.core.model.GeoCoordinate(47.7, 12.8),
                app.openhikes.core.model.GeoCoordinate(47.71, 12.81)
            )
        ),
        pausedSegments = emptyList(),
        tintHex = tintHex,
        borderHex = borderHex,
        widthDp = widthDp,
        linePattern = pattern,
        isSelected = false
    )
}
