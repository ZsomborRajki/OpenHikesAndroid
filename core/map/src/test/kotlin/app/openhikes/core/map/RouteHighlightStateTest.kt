package app.openhikes.core.map

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteHighlightStateTest {
    @Test
    fun moveClearAndClearIfHikeUpdateHighlightOnlyForMatchingHike() {
        val state = RouteHighlightState()
        val highlight = MapRouteHighlight(
            hikeId = HikeId("h1"),
            coordinate = GeoCoordinate(47.71, 12.83),
            tintHex = "#1B7F3B"
        )

        state.move(highlight)
        assertThat(state.highlight.value).isEqualTo(highlight)

        state.clearIfHike(HikeId("h2"))
        assertThat(state.highlight.value).isEqualTo(highlight)

        state.clearIfHike(HikeId("h1"))
        assertThat(state.highlight.value).isNull()

        state.move(highlight)
        state.clear()
        assertThat(state.highlight.value).isNull()
    }
}
