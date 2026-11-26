package app.openhikes.core.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.hypot

internal const val TRAIL_DRAFT_DROP_PRESS_TIMEOUT_MS = 350L
internal const val TRAIL_DRAFT_DRAG_HOLD_TIMEOUT_MS = 220L
internal const val TRAIL_DRAFT_LEG_HIT_TOLERANCE_DP = 28.0
internal val TrailDraftPinTouchTargetSize = 48.dp
internal val TrailDraftPinVisualDiameter = 28.dp
internal val TrailDraftPinBorderWidth = 2.5.dp
internal val TrailDraftPinShadowElevation = 3.dp
internal val TrailDraftAccentColor = Color(0xFF1B6E43)
internal val TrailDraftBorderColor = Color.White

internal enum class DraftHoldOutcome {
    HELD,
    TAPPED,
    CANCELLED
}

@Composable
internal fun TrailDraftLongPressDropSurface(
    screenToCoordinate: (Offset) -> GeoCoordinate?,
    metersPerDpAtLatitude: (Double) -> Double,
    legsProvider: () -> List<MapTrailDraftLeg>,
    handlerProvider: () -> TrailDraftMapInteractionHandler?,
    modifier: Modifier = Modifier
) {
    val currentScreenToCoord by rememberUpdatedState(screenToCoordinate)
    val currentMetersPerDp by rememberUpdatedState(metersPerDpAtLatitude)
    val currentLegsProvider by rememberUpdatedState(legsProvider)
    val currentHandlerProvider by rememberUpdatedState(handlerProvider)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val outcome = awaitDraftHoldOutcome(
                        pointerId = down.id,
                        origin = down.position,
                        touchSlop = touchSlop,
                        timeoutMs = TRAIL_DRAFT_DROP_PRESS_TIMEOUT_MS
                    )
                    if (outcome != DraftHoldOutcome.HELD) return@awaitEachGesture
                    val coord = currentScreenToCoord(down.position) ?: return@awaitEachGesture
                    val metersPerDp = currentMetersPerDp(coord.latitude)
                    val hitLeg = findNearestLegWithin(
                        coordinate = coord,
                        legs = currentLegsProvider(),
                        toleranceMeters = TRAIL_DRAFT_LEG_HIT_TOLERANCE_DP * metersPerDp
                    )
                    currentHandlerProvider()?.onDropPin(coord, hitLeg)
                }
            }
    )
}

@Composable
internal fun DraftStopPinSurface(
    pin: MapTrailDraftPin,
    screenToCoordinate: (Offset) -> GeoCoordinate?,
    handlerProvider: () -> TrailDraftMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var topLeftInParent by remember { mutableStateOf(Offset.Zero) }
    val currentPin by rememberUpdatedState(pin)
    val currentScreenToCoord by rememberUpdatedState(screenToCoordinate)
    val currentHandlerProvider by rememberUpdatedState(handlerProvider)
    val currentOnPanLockChanged by rememberUpdatedState(onPanLockChanged)
    val openDetailsLabel = stringResource(R.string.trail_draft_stop_pin_open_action)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TrailDraftPinTouchTargetSize)
            .onGloballyPositioned { coords -> topLeftInParent = coords.positionInParent() }
            .focusable()
            .onKeyEvent { event -> handlePinKeyEvent(event, currentPin.id, currentHandlerProvider) }
            .pointerInput(pin.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    val outcome = awaitDraftHoldOutcome(
                        pointerId = down.id,
                        origin = down.position,
                        touchSlop = touchSlop,
                        timeoutMs = TRAIL_DRAFT_DRAG_HOLD_TIMEOUT_MS
                    )
                    val handler = currentHandlerProvider() ?: return@awaitEachGesture
                    when (outcome) {
                        DraftHoldOutcome.TAPPED -> handler.onTapStopPin(currentPin.id)
                        DraftHoldOutcome.HELD -> if (handler.onBeginPinDrag(currentPin.index)) {
                            currentOnPanLockChanged(true)
                            try {
                                trackPinDrag(down.id, { topLeftInParent }, currentScreenToCoord, handler)
                            } finally {
                                currentOnPanLockChanged(false)
                            }
                        }
                        DraftHoldOutcome.CANCELLED -> Unit
                    }
                }
            }
            .semantics {
                contentDescription = pin.label
                role = Role.Button
                onClick(label = openDetailsLabel) {
                    currentHandlerProvider()?.onTapStopPin(currentPin.id)
                    true
                }
            }
            .testTag(OpenHikesTestTags.trailDraftPin(pin.index + 1))
    ) {
        DraftStopPinBadge(badgeText = pin.badgeText)
    }
}

@Composable
private fun DraftStopPinBadge(badgeText: String) {
    Surface(
        color = TrailDraftAccentColor,
        shape = CircleShape,
        border = BorderStroke(TrailDraftPinBorderWidth, TrailDraftBorderColor),
        shadowElevation = TrailDraftPinShadowElevation,
        modifier = Modifier.size(TrailDraftPinVisualDiameter)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (badgeText.isNotEmpty()) {
                Text(
                    text = badgeText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun handlePinKeyEvent(
    event: KeyEvent,
    pinId: String,
    handlerProvider: () -> TrailDraftMapInteractionHandler?
): Boolean {
    val isActivationKey = event.key == Key.Enter ||
        event.key == Key.NumPadEnter ||
        event.key == Key.Spacebar ||
        event.key == Key.DirectionCenter
    return when {
        !isActivationKey -> false
        event.type == KeyEventType.KeyDown -> true
        event.type == KeyEventType.KeyUp -> {
            handlerProvider()?.onTapStopPin(pinId)
            true
        }
        else -> false
    }
}

internal suspend fun AwaitPointerEventScope.awaitDraftHoldOutcome(
    pointerId: PointerId,
    origin: Offset,
    touchSlop: Float,
    timeoutMs: Long
): DraftHoldOutcome {
    var earlyOutcome: DraftHoldOutcome? = null
    val endedBeforeTimeout = withTimeoutOrNull(timeoutMs) {
        while (earlyOutcome == null) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
            earlyOutcome = when {
                change == null || change.isConsumed -> DraftHoldOutcome.CANCELLED
                !change.pressed -> DraftHoldOutcome.TAPPED
                hypot(change.position.x - origin.x, change.position.y - origin.y) > touchSlop ->
                    DraftHoldOutcome.CANCELLED
                else -> null
            }
        }
    }
    return if (endedBeforeTimeout == null) DraftHoldOutcome.HELD else earlyOutcome ?: DraftHoldOutcome.CANCELLED
}

private suspend fun AwaitPointerEventScope.trackPinDrag(
    pointerId: PointerId,
    topLeftProvider: () -> Offset,
    screenToCoordinate: (Offset) -> GeoCoordinate?,
    handler: TrailDraftMapInteractionHandler
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            handler.onCancelPinDrag()
            return
        }
        change.consume()
        if (!change.pressed) {
            handler.onEndPinDrag()
            return
        }
        val parentOffset = topLeftProvider() + change.position
        screenToCoordinate(parentOffset)?.let(handler::onMovePinDrag)
    }
}

internal fun findNearestLegWithin(
    coordinate: GeoCoordinate,
    legs: List<MapTrailDraftLeg>,
    toleranceMeters: Double
): TrailLegEnds? {
    var best: TrailLegEnds? = null
    var bestDistance = toleranceMeters
    for (leg in legs) {
        val shape = if (leg.coordinates.size >= 2) leg.coordinates else leg.ends.straightCoordinates
        for (i in 0 until shape.lastIndex) {
            val off = RouteGeometry.project(coordinate, shape[i], shape[i + 1]).offRouteMeters
            if (off <= bestDistance) {
                bestDistance = off
                best = leg.ends
            }
        }
    }
    return best
}
