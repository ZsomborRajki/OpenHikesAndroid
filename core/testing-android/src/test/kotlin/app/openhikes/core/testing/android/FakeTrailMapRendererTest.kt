package app.openhikes.core.testing.android

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.TrailMapHost
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FakeTrailMapRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesTrailMapSemanticTagAndAppliesCameraCommands() {
        val renderer = FakeTrailMapRenderer()
        val handledIds = mutableListOf<Long>()
        val liveOverlay = MutableStateFlow(
            MapRouteOverlay(
                hikeId = HikeId("rec-1"),
                walkedSegments = listOf(
                    listOf(
                        GeoCoordinate(latitude = 47.718420, longitude = 12.831774),
                        GeoCoordinate(latitude = 47.719219, longitude = 12.830877)
                    )
                ),
                pausedSegments = emptyList(),
                tintHex = RouteTint.DEFAULT_HEX,
                borderHex = RouteBorder.DEFAULT_HEX,
                widthDp = 4f,
                linePattern = RouteLinePattern.SOLID,
                isSelected = true
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                TrailMapHost(
                    renderer = renderer,
                    cameraState = MapCameraState(zoom = 11.0, bearing = 35.0),
                    cameraCommand = MapCameraCommand.ZoomIn(id = 42L, delta = 2.0),
                    overlayState = MapOverlayState(showsHikingRoutes = false),
                    viewportPadding = PaddingValues(16.dp),
                    interactions = MapInteractionEvents(),
                    liveRecordingOverlayState = liveOverlay,
                    onCameraCommandHandled = { handledIds.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAP).assertIsDisplayed()
        assertThat(handledIds).containsExactly(42L)
        assertThat(renderer.lastCameraState.value.zoom).isEqualTo(13.0)
        assertThat(renderer.lastOverlayState.value?.showsHikingRoutes).isFalse()
        assertThat(renderer.lastLiveRecordingOverlay.value?.hikeId).isEqualTo(HikeId("rec-1"))
    }
}
