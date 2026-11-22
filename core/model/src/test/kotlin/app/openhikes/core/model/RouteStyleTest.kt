package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteStyleTest {
    @Test
    fun paletteWrapsDeterministicallyAndStableForKeyProducesValidHex() {
        assertThat(RouteTint.forIndex(0)).isEqualTo(RouteTint.DEFAULT_HEX)
        assertThat(RouteTint.forIndex(-1)).isEqualTo(RouteTint.palette.last())
        assertThat(RouteTint.forIndex(RouteTint.palette.size)).isEqualTo(RouteTint.palette.first())
        assertThat(RouteTint.stableForKey("Thumsee")).matches("^#[0-9A-F]{6}$")
        assertThat(RouteTint.stableForKey("Thumsee")).isEqualTo(RouteTint.stableForKey("Thumsee"))
    }

    @Test
    fun borderVisibilityAndLinePatternDashLengthsMatchContract() {
        assertThat(RouteBorder.isVisible(RouteBorder.NONE_HEX)).isTrue()
        assertThat(RouteBorder.isVisible(RouteBorder.TRANSPARENT_HEX)).isFalse()
        assertThat(RouteBorder.isVisible(RouteBorder.WHITE_HEX)).isTrue()
        assertThat(RouteBorder.resolvedBorderHex(RouteBorder.NONE_HEX)).isEqualTo(RouteBorder.DEFAULT_HEX)

        assertThat(RouteLinePattern.SOLID.dashLengths(3.0)).isEmpty()
        assertThat(RouteLinePattern.DASHED.dashLengths(4.0)).containsExactly(12.0, 10.0).inOrder()
        assertThat(RouteLinePattern.DOTTED.dashLengths(4.0)).containsExactly(0.1, 8.0).inOrder()
        assertThat(RouteLinePattern.ARROWHEADS.drawsLine).isFalse()
        assertThat(RouteLinePattern.ARROWHEADS.chevronsUseRouteTint).isTrue()
        assertThat(RouteLinePattern.fromStoredId("unknown")).isEqualTo(RouteLinePattern.Default)

        val update = RouteStyleUpdate(RouteTint.DEFAULT_HEX, RouteBorder.NONE_HEX, 99.0, RouteLinePattern.SOLID)
        assertThat(update.clampedWidthDp).isEqualTo(RouteStyleUpdate.MAX_ROUTE_WIDTH_DP)
    }
}
