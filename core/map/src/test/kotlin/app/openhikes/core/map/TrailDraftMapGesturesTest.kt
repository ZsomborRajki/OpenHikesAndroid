package app.openhikes.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class TrailDraftMapGesturesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressDropsPinWithNearestLegHitWhileShortTapAndPanDoNot() {
        val dropped = mutableListOf<Pair<GeoCoordinate, TrailLegEnds?>>()
        val legEnds = TrailLegEnds(GeoCoordinate(47.7200, 12.8200), GeoCoordinate(47.7200, 12.8300))
        val leg = MapTrailDraftLeg(
            id = "leg-0",
            ends = legEnds,
            coordinates = listOf(legEnds.start, legEnds.end),
            snap = TrailLegSnap.Snapped
        )
        val handler = RecordingDraftHandler(onDrop = { coord, hit -> dropped.add(coord to hit) })

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                Box(modifier = Modifier.size(300.dp)) {
                    TrailDraftLongPressDropSurface(
                        screenToCoordinate = { o ->
                            GeoCoordinate(47.7200 + (o.y - 150f) * 1e-6, 12.8200 + o.x * 3e-5)
                        },
                        metersPerDpAtLatitude = { 2.0 },
                        legsProvider = { listOf(leg) },
                        handlerProvider = { handler },
                        modifier = Modifier.testTag("drop-surface")
                    )
                }
            }
        }

        val surface = composeRule.onNodeWithTag("drop-surface")
        surface.performTouchInput {
            down(Offset(120f, 150f))
            advanceEventTime(100L)
            up()
        }
        surface.performTouchInput {
            down(Offset(120f, 150f))
            advanceEventTime(50L)
            moveBy(Offset(80f, 0f))
            advanceEventTime(400L)
            up()
        }
        composeRule.waitForIdle()
        assertThat(dropped).isEmpty()

        surface.performTouchInput {
            down(Offset(120f, 150f))
            advanceEventTime(TRAIL_DRAFT_DROP_PRESS_TIMEOUT_MS + 50L)
            up()
        }
        composeRule.waitForIdle()
        assertThat(dropped).hasSize(1)
        assertThat(dropped.single().second).isEqualTo(legEnds)
    }

    @Test
    fun stopPinHandlesTouchTapAndLongHoldDrag() {
        val tappedIds = mutableListOf<String>()
        val dragStarts = mutableListOf<Int>()
        val dragMoves = mutableListOf<GeoCoordinate>()
        var dragEnds = 0
        val panLocks = mutableListOf<Boolean>()
        val handler = RecordingDraftHandler(
            onTap = { tappedIds.add(it) },
            onBeginDrag = { index ->
                dragStarts.add(index)
                true
            },
            onMoveDrag = { dragMoves.add(it) },
            onEndDrag = { dragEnds += 1 }
        )
        setStopPinContent(handler = handler, onPanLockChanged = { panLocks.add(it) })

        val pinNode = composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPin(1))
        pinNode.performTouchInput {
            down(center)
            advanceEventTime(50L)
            up()
        }
        composeRule.waitForIdle()
        assertThat(tappedIds).containsExactly("wp-1")
        assertThat(dragStarts).isEmpty()

        pinNode.performTouchInput {
            down(center)
            advanceEventTime(TRAIL_DRAFT_DRAG_HOLD_TIMEOUT_MS + 50L)
            moveBy(Offset(40f, 20f))
            advanceEventTime(30L)
            up()
        }
        composeRule.waitForIdle()
        assertThat(dragStarts).containsExactly(0)
        assertThat(dragMoves).isNotEmpty()
        assertThat(dragEnds).isEqualTo(1)
        assertThat(panLocks).containsExactly(true, false).inOrder()
        assertThat(tappedIds).hasSize(1)
    }

    @Test
    fun stopPinSupportsSemanticClickAndKeyboardActivation() {
        val tappedIds = mutableListOf<String>()
        setStopPinContent(handler = RecordingDraftHandler(onTap = { tappedIds.add(it) }), onPanLockChanged = {})

        val pinNode = composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPin(1))
        pinNode.assertHasClickAction()

        pinNode.performClick()
        pinNode.requestFocus()
        pinNode.performKeyInput {
            pressKey(Key.Enter)
            pressKey(Key.Spacebar)
        }
        composeRule.waitForIdle()
        assertThat(tappedIds).containsExactly("wp-1", "wp-1", "wp-1")
    }

    private fun setStopPinContent(handler: TrailDraftMapInteractionHandler, onPanLockChanged: (Boolean) -> Unit) {
        val pin = MapTrailDraftPin(
            id = "wp-1",
            index = 0,
            coordinate = GeoCoordinate(47.7200, 12.8200),
            role = TrailStopRole.Start,
            label = "Start: Thumsee",
            badgeText = "1"
        )
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                Box(modifier = Modifier.size(300.dp)) {
                    DraftStopPinSurface(
                        pin = pin,
                        screenToCoordinate = { o -> GeoCoordinate(47.7200 + o.y * 1e-4, 12.8200 + o.x * 1e-4) },
                        handlerProvider = { handler },
                        onPanLockChanged = onPanLockChanged,
                        modifier = Modifier.offset(x = 60.dp, y = 80.dp)
                    )
                }
            }
        }
    }

    private class RecordingDraftHandler(
        private val onDrop: (GeoCoordinate, TrailLegEnds?) -> Unit = { _, _ -> },
        private val onTap: (String) -> Unit = {},
        private val onBeginDrag: (Int) -> Boolean = { false },
        private val onMoveDrag: (GeoCoordinate) -> Unit = {},
        private val onEndDrag: () -> Unit = {}
    ) : TrailDraftMapInteractionHandler {
        override fun onDropPin(coordinate: GeoCoordinate, legEnds: TrailLegEnds?) = onDrop(coordinate, legEnds)

        override fun onTapStopPin(waypointId: String) = onTap(waypointId)

        override fun onTapDroppedPin() = Unit

        override fun onChooseAlternative(legIndex: Int, alternativeIndex: Int) = Unit

        override fun onBeginPinDrag(index: Int): Boolean = onBeginDrag(index)

        override fun onMovePinDrag(coordinate: GeoCoordinate) = onMoveDrag(coordinate)

        override fun onEndPinDrag() = onEndDrag()

        override fun onCancelPinDrag() = Unit
    }
}
