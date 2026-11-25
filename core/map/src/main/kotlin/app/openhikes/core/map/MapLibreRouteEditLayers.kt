package app.openhikes.core.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.hypot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

private val CutOverlayColor = Color(0xFF8E8E93)
private val StretchAccentColor = Color(0xFFF57C00)
private const val CUT_OVERLAY_OPACITY = 0.85f
private const val CUT_WIDTH_BONUS_DP = 1.5f
private const val STRETCH_LINE_WIDTH_DP = 7.0f
private const val PULL_BAND_WIDTH_DP = 5.0f
private const val DASH_ON_RATIO = 1.2
private const val DASH_OFF_RATIO = 1.8
private val HandleTouchTargetSize = 48.dp
private val HandleKnobDiameter = 30.dp
private val HandleRingWidth = 4.dp
private val HandleShadowElevation = 3.dp
private const val PULL_PRESS_TIMEOUT_MS = 350L
private const val PULL_REACH_DP = 70.0
private const val LINE_TAP_TOLERANCE_DP = 24f

@Composable
@MaplibreComposable
internal fun RouteEditOverlayLayers(routeEditOverlayState: StateFlow<MapRouteEditOverlay?>) {
    val overlay by routeEditOverlayState.collectAsStateWithLifecycle()
    val current = overlay ?: return

    val cutSegments = remember(current.cutLeadingSegments, current.cutTrailingSegments) {
        current.cutLeadingSegments + current.cutTrailingSegments
    }
    if (cutSegments.isNotEmpty()) {
        RouteEditLineLayer(
            id = "route-edit-cut-ends",
            segments = cutSegments,
            color = CutOverlayColor,
            widthDp = current.widthDp + CUT_WIDTH_BONUS_DP,
            opacity = CUT_OVERLAY_OPACITY,
            cap = LineCap.Butt
        )
    }

    if (current.keptWalkedSegments.isNotEmpty() || current.keptPausedSegments.isNotEmpty()) {
        val keptRoute = remember(current) {
            MapRouteOverlay(
                hikeId = current.hikeId,
                walkedSegments = current.keptWalkedSegments,
                pausedSegments = current.keptPausedSegments,
                tintHex = current.tintHex,
                borderHex = current.borderHex,
                widthDp = current.widthDp,
                linePattern = current.linePattern,
                isSelected = true
            )
        }
        RouteOverlayLayers(routes = listOf(keptRoute))
    }

    if (current.pullCoverSegments.isNotEmpty()) {
        RouteEditLineLayer(
            id = "route-edit-pull-cover",
            segments = current.pullCoverSegments,
            color = CutOverlayColor,
            widthDp = current.widthDp + CUT_WIDTH_BONUS_DP * 2f,
            opacity = 1f,
            cap = LineCap.Butt
        )
    }

    if (current.pickedStretchSegments.isNotEmpty()) {
        RouteEditLineLayer(
            id = "route-edit-picked-stretch",
            segments = current.pickedStretchSegments,
            color = StretchAccentColor,
            widthDp = STRETCH_LINE_WIDTH_DP,
            dashed = true
        )
    }

    if (current.pullBandCoordinates.size >= 2) {
        RouteEditLineLayer(
            id = "route-edit-pull-band",
            segments = listOf(current.pullBandCoordinates),
            color = StretchAccentColor,
            widthDp = PULL_BAND_WIDTH_DP,
            dashed = true
        )
    }
}

@Composable
@MaplibreComposable
private fun RouteEditLineLayer(
    id: String,
    segments: List<List<GeoCoordinate>>,
    color: Color,
    widthDp: Float,
    opacity: Float = 1f,
    cap: LineCap = LineCap.Round,
    dashed: Boolean = false
) {
    val data = remember(segments) { buildMultiLineFeatureCollection(segments) }
    val source = rememberGeoJsonSource(data = data)
    if (dashed) {
        LineLayer(
            id = id,
            source = source,
            color = const(color),
            width = const(widthDp.dp),
            opacity = const(opacity),
            cap = const(cap),
            join = const(LineJoin.Round),
            dasharray = const(listOf<Number>(DASH_ON_RATIO, DASH_OFF_RATIO))
        )
    } else {
        LineLayer(
            id = id,
            source = source,
            color = const(color),
            width = const(widthDp.dp),
            opacity = const(opacity),
            cap = const(cap),
            join = const(LineJoin.Round)
        )
    }
}

@Composable
internal fun MapOverlayScope.RouteEditInteractiveOverlay(
    mapState: MapState,
    routeEditOverlayState: StateFlow<MapRouteEditOverlay?>,
    interactionHandlerProvider: () -> RouteEditMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit
) {
    val overlay by routeEditOverlayState.collectAsStateWithLifecycle()
    val current = overlay ?: return
    val currentHandlerProvider by rememberUpdatedState(interactionHandlerProvider)
    val currentOnPanLockChanged by rememberUpdatedState(onPanLockChanged)
    val density = LocalDensity.current

    if (!current.isSelectingStretch) {
        RoutePullGestureLayer(
            mapState = mapState,
            density = density,
            hikeId = current.hikeId.value,
            handlerProvider = { currentHandlerProvider() },
            onPanLockChanged = { currentOnPanLockChanged(it) }
        )
    }

    val ringColor = if (current.isSelectingStretch) {
        StretchAccentColor
    } else {
        parseHexColorOrDefault(current.tintHex, parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green))
    }

    // Render END after START so when both stand on the same spot (a loop), END captures drags first.
    RouteEditMapHandle(
        handle = RouteTrimHandle.START,
        coordinate = current.startHandle,
        ringColor = ringColor,
        isSelectingStretch = current.isSelectingStretch,
        mapState = mapState,
        density = density,
        handlerProvider = { currentHandlerProvider() },
        onPanLockChanged = { currentOnPanLockChanged(it) }
    )
    RouteEditMapHandle(
        handle = RouteTrimHandle.END,
        coordinate = current.endHandle,
        ringColor = ringColor,
        isSelectingStretch = current.isSelectingStretch,
        mapState = mapState,
        density = density,
        handlerProvider = { currentHandlerProvider() },
        onPanLockChanged = { currentOnPanLockChanged(it) }
    )
}

@Composable
private fun RoutePullGestureLayer(
    mapState: MapState,
    density: Density,
    hikeId: String,
    handlerProvider: () -> RouteEditMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(hikeId) {
                val touchSlop = viewConfiguration.touchSlop
                val moveTolerancePx = with(density) { LINE_TAP_TOLERANCE_DP.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val stillDown = awaitLongHoldUnmoved(down.id, down.position, touchSlop)
                    if (!stillDown) return@awaitEachGesture
                    val handler = handlerProvider() ?: return@awaitEachGesture
                    val startCoord = screenOffsetToCoordinate(mapState, density, down.position)
                        ?: return@awaitEachGesture
                    val metersPerDp = mapState.metersPerDpAtLatitude(startCoord.latitude) ?: 1.0
                    val accepted = handler.onBeginPull(startCoord, reachMeters = PULL_REACH_DP * metersPerDp)
                    if (!accepted) return@awaitEachGesture
                    onPanLockChanged(true)
                    try {
                        trackRoutePull(
                            pointerId = down.id,
                            origin = down.position,
                            moveTolerancePx = moveTolerancePx,
                            mapState = mapState,
                            density = density,
                            handler = handler
                        )
                    } finally {
                        onPanLockChanged(false)
                    }
                }
            }
    )
}

private suspend fun AwaitPointerEventScope.awaitLongHoldUnmoved(
    pointerId: PointerId,
    origin: Offset,
    touchSlop: Float
): Boolean = try {
    withTimeout(PULL_PRESS_TIMEOUT_MS) {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return@withTimeout false
            if (!change.pressed || change.isConsumed) return@withTimeout false
            val moved = hypot(change.position.x - origin.x, change.position.y - origin.y)
            if (moved > touchSlop) return@withTimeout false
        }
    }
    false
} catch (_: TimeoutCancellationException) {
    true
}

private suspend fun AwaitPointerEventScope.trackRoutePull(
    pointerId: PointerId,
    origin: Offset,
    moveTolerancePx: Float,
    mapState: MapState,
    density: Density,
    handler: RouteEditMapInteractionHandler
) {
    var latestPosition = origin
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            handler.onCancelPull()
            return
        }
        change.consume()
        latestPosition = change.position
        val coord = screenOffsetToCoordinate(mapState, density, latestPosition)
        if (!change.pressed) {
            val moved = hypot(latestPosition.x - origin.x, latestPosition.y - origin.y)
            if (coord != null) {
                handler.onEndPull(coord, movedEnough = moved > moveTolerancePx)
            } else {
                handler.onCancelPull()
            }
            return
        }
        if (coord != null) {
            handler.onMovePull(coord)
        }
    }
}

@Composable
private fun MapOverlayScope.RouteEditMapHandle(
    handle: RouteTrimHandle,
    coordinate: GeoCoordinate,
    ringColor: Color,
    isSelectingStretch: Boolean,
    mapState: MapState,
    density: Density,
    handlerProvider: () -> RouteEditMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit
) {
    var topLeftInParent by remember { mutableStateOf(Offset.Zero) }
    val description = routeEditHandleDescription(handle, isSelectingStretch)
    val forwardLabel = stringResource(R.string.route_edit_handle_move_forward)
    val backwardLabel = stringResource(R.string.route_edit_handle_move_backward)
    val tag = if (handle == RouteTrimHandle.START) {
        OpenHikesTestTags.ROUTE_TRIM_START_HANDLE
    } else {
        OpenHikesTestTags.ROUTE_TRIM_END_HANDLE
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .placedAt(Position(longitude = coordinate.longitude, latitude = coordinate.latitude), Alignment.Center)
            .size(HandleTouchTargetSize)
            .onGloballyPositioned { coords -> topLeftInParent = coords.positionInParent() }
            .pointerInput(handle) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    onPanLockChanged(true)
                    try {
                        trackHandleDrag(down.id, handle, { topLeftInParent }, mapState, density, handlerProvider)
                    } finally {
                        onPanLockChanged(false)
                    }
                }
            }
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(forwardLabel) {
                        handlerProvider()?.onAdjustHandle(handle, 1)
                        true
                    },
                    CustomAccessibilityAction(backwardLabel) {
                        handlerProvider()?.onAdjustHandle(handle, -1)
                        true
                    }
                )
            }
            .testTag(tag)
    ) {
        Surface(
            color = Color.White,
            shape = CircleShape,
            border = BorderStroke(HandleRingWidth, ringColor),
            shadowElevation = HandleShadowElevation,
            modifier = Modifier.size(HandleKnobDiameter)
        ) {}
    }
}

@Composable
private fun routeEditHandleDescription(handle: RouteTrimHandle, isSelectingStretch: Boolean): String = when {
    handle == RouteTrimHandle.START && isSelectingStretch ->
        stringResource(R.string.route_edit_stretch_start_handle_description)
    handle == RouteTrimHandle.END && isSelectingStretch ->
        stringResource(R.string.route_edit_stretch_end_handle_description)
    handle == RouteTrimHandle.START -> stringResource(R.string.route_edit_start_handle_description)
    else -> stringResource(R.string.route_edit_end_handle_description)
}

private suspend fun AwaitPointerEventScope.trackHandleDrag(
    pointerId: PointerId,
    handle: RouteTrimHandle,
    topLeftProvider: () -> Offset,
    mapState: MapState,
    density: Density,
    handlerProvider: () -> RouteEditMapInteractionHandler?
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return
        change.consume()
        if (!change.pressed) return
        val parentOffset = topLeftProvider() + change.position
        val coord = screenOffsetToCoordinate(mapState, density, parentOffset) ?: continue
        handlerProvider()?.onDragHandle(handle, coord)
    }
}

private fun screenOffsetToCoordinate(mapState: MapState, density: Density, offset: Offset): GeoCoordinate? {
    val dpOffset = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
    return mapState.positionFromScreenLocation(dpOffset)
        ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        ?.let { GeoCoordinate(latitude = it.latitude, longitude = it.longitude) }
}
