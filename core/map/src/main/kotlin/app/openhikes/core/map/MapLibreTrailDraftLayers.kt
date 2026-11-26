package app.openhikes.core.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailLegEnds
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

private val DraftAccentColor = Color(0xFF1B6E43)
private val DraftBorderColor = Color.White
private val DroppedPinColor = Color(0xFFD32F2F)
private const val ALTERNATIVE_WIDTH_DP = 6.0f
private const val ALTERNATIVE_OPACITY = 0.42f
private const val LEG_BORDER_WIDTH_DP = 6.5f
private const val LEG_STROKE_WIDTH_DP = 4.0f
private const val SHORT_DASH_ON = 0.9
private const val SHORT_DASH_OFF = 2.0
private const val LONG_DASH_ON = 2.5
private const val LONG_DASH_OFF = 1.6
private val PinTouchTargetSize = 48.dp
private val PinVisualDiameter = 28.dp
private val PinBorderWidth = 2.5.dp
private val PinShadowElevation = 3.dp
private val BubbleMinHeight = 36.dp
private val BubblePaddingHorizontal = 10.dp
private val BubblePaddingVertical = 6.dp
private const val DROP_PRESS_TIMEOUT_MS = 350L
private const val DRAG_HOLD_TIMEOUT_MS = 220L
private const val LEG_HIT_TOLERANCE_DP = 28.0

private data class TrailDraftLineBuckets(
    val alternatives: List<List<GeoCoordinate>>,
    val solid: List<List<GeoCoordinate>>,
    val routing: List<List<GeoCoordinate>>,
    val degraded: List<List<GeoCoordinate>>
)

@Composable
@MaplibreComposable
internal fun TrailDraftOverlayLayers(trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?>) {
    val overlay by trailDraftOverlayState.collectAsStateWithLifecycle()
    val current = overlay ?: return
    val buckets = remember(current) { partitionDraftLines(current) }

    if (buckets.alternatives.isNotEmpty() && current.draggedPinIndex == null) {
        DraftLineLayer(
            id = "trail-draft-alternatives",
            segments = buckets.alternatives,
            color = DraftAccentColor,
            widthDp = ALTERNATIVE_WIDTH_DP,
            opacity = ALTERNATIVE_OPACITY
        )
    }
    val allMain = remember(buckets) { buckets.solid + buckets.routing + buckets.degraded }
    if (allMain.isNotEmpty()) {
        DraftLineLayer(
            id = "trail-draft-casing",
            segments = allMain,
            color = DraftBorderColor,
            widthDp = LEG_BORDER_WIDTH_DP,
            opacity = 0.9f
        )
    }
    if (buckets.solid.isNotEmpty()) {
        DraftLineLayer(
            id = "trail-draft-solid",
            segments = buckets.solid,
            color = DraftAccentColor,
            widthDp = LEG_STROKE_WIDTH_DP
        )
    }
    if (buckets.routing.isNotEmpty()) {
        DraftLineLayer(
            id = "trail-draft-routing",
            segments = buckets.routing,
            color = DraftAccentColor,
            widthDp = LEG_STROKE_WIDTH_DP,
            dashPattern = listOf(SHORT_DASH_ON, SHORT_DASH_OFF)
        )
    }
    if (buckets.degraded.isNotEmpty()) {
        DraftLineLayer(
            id = "trail-draft-degraded",
            segments = buckets.degraded,
            color = DraftAccentColor,
            widthDp = LEG_STROKE_WIDTH_DP,
            dashPattern = listOf(LONG_DASH_ON, LONG_DASH_OFF)
        )
    }
}

private fun partitionDraftLines(overlay: MapTrailDraftOverlay): TrailDraftLineBuckets {
    val alts = ArrayList<List<GeoCoordinate>>()
    val solid = ArrayList<List<GeoCoordinate>>()
    val routing = ArrayList<List<GeoCoordinate>>()
    val degraded = ArrayList<List<GeoCoordinate>>()
    val dragIdx = overlay.draggedPinIndex
    val dragCoord = overlay.draggedCoordinate

    overlay.legs.forEachIndexed { legIndex, leg ->
        val isBent = dragIdx != null && dragCoord != null && (legIndex == dragIdx - 1 || legIndex == dragIdx)
        if (isBent) {
            appendBentDraftLeg(overlay, legIndex, dragIdx, dragCoord, routing, solid)
        } else {
            appendSettledDraftLeg(leg, routing, degraded, solid, alts)
        }
    }
    return TrailDraftLineBuckets(alternatives = alts, solid = solid, routing = routing, degraded = degraded)
}

@Suppress("LongParameterList")
private fun appendBentDraftLeg(
    overlay: MapTrailDraftOverlay,
    legIndex: Int,
    dragIdx: Int,
    dragCoord: GeoCoordinate,
    routing: MutableList<List<GeoCoordinate>>,
    solid: MutableList<List<GeoCoordinate>>
) {
    val start = if (dragIdx == legIndex) dragCoord else overlay.pins.getOrNull(legIndex)?.coordinate
    val end = if (dragIdx == legIndex + 1) dragCoord else overlay.pins.getOrNull(legIndex + 1)?.coordinate
    if (start != null && end != null) {
        val band = listOf(start, end)
        if (overlay.snapsToPaths) routing.add(band) else solid.add(band)
    }
}

private fun appendSettledDraftLeg(
    leg: MapTrailDraftLeg,
    routing: MutableList<List<GeoCoordinate>>,
    degraded: MutableList<List<GeoCoordinate>>,
    solid: MutableList<List<GeoCoordinate>>,
    alts: MutableList<List<GeoCoordinate>>
) {
    val coords = if (leg.coordinates.size >= 2) leg.coordinates else leg.ends.straightCoordinates
    when {
        leg.snap.isRouting -> routing.add(coords)
        leg.snap.isDegraded -> degraded.add(coords)
        else -> solid.add(coords)
    }
    for (alt in leg.alternatives) {
        if (alt.coordinates.size >= 2) alts.add(alt.coordinates)
    }
}

@Composable
@MaplibreComposable
private fun DraftLineLayer(
    id: String,
    segments: List<List<GeoCoordinate>>,
    color: Color,
    widthDp: Float,
    opacity: Float = 1f,
    dashPattern: List<Number>? = null
) {
    val data = remember(segments) { buildMultiLineFeatureCollection(segments) }
    val source = rememberGeoJsonSource(data = data)
    if (dashPattern != null) {
        LineLayer(
            id = id,
            source = source,
            color = const(color),
            width = const(widthDp.dp),
            opacity = const(opacity),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
            dasharray = const(dashPattern)
        )
    } else {
        LineLayer(
            id = id,
            source = source,
            color = const(color),
            width = const(widthDp.dp),
            opacity = const(opacity),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round)
        )
    }
}

@Composable
internal fun MapOverlayScope.TrailDraftInteractiveOverlay(
    mapState: MapState,
    trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?>,
    interactionHandlerProvider: () -> TrailDraftMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit
) {
    val overlay by trailDraftOverlayState.collectAsStateWithLifecycle()
    val current = overlay ?: return
    val currentHandlerProvider by rememberUpdatedState(interactionHandlerProvider)
    val currentOnPanLockChanged by rememberUpdatedState(onPanLockChanged)
    val currentOverlay by rememberUpdatedState(current)
    val density = LocalDensity.current

    TrailDraftLongPressDropLayer(
        mapState = mapState,
        density = density,
        overlayProvider = { currentOverlay },
        handlerProvider = { currentHandlerProvider() }
    )

    if (current.draggedPinIndex == null) {
        current.routeTimeBubble?.let { bubble ->
            DraftTimeBubble(
                coordinate = bubble.coordinate,
                label = bubble.label,
                spokenDescription = bubble.spokenDescription,
                isPrimary = true,
                testTag = OpenHikesTestTags.TRAIL_DRAFT_ROUTE_TIME,
                onClick = null
            )
        }
        for (leg in current.legs) {
            for (alt in leg.alternatives) {
                DraftTimeBubble(
                    coordinate = alt.midpoint,
                    label = alt.formattedTime,
                    spokenDescription = alt.spokenDescription,
                    isPrimary = false,
                    testTag = OpenHikesTestTags.TRAIL_DRAFT_ALTERNATIVE_TIME,
                    onClick = { currentHandlerProvider()?.onChooseAlternative(alt.legIndex, alt.alternativeIndex) }
                )
            }
        }
    }

    current.droppedPin?.let { spot ->
        DraftDroppedPinMarker(
            coordinate = spot.coordinate,
            onClick = { currentHandlerProvider()?.onTapDroppedPin() }
        )
    }

    for (pin in current.pins) {
        val displayCoord = if (current.draggedPinIndex == pin.index && current.draggedCoordinate != null) {
            current.draggedCoordinate
        } else {
            pin.coordinate
        }
        DraftStopPinMarker(
            pin = pin,
            coordinate = displayCoord,
            mapState = mapState,
            density = density,
            handlerProvider = { currentHandlerProvider() },
            onPanLockChanged = { currentOnPanLockChanged(it) }
        )
    }
}

@Composable
private fun TrailDraftLongPressDropLayer(
    mapState: MapState,
    density: Density,
    overlayProvider: () -> MapTrailDraftOverlay,
    handlerProvider: () -> TrailDraftMapInteractionHandler?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val held = awaitDraftLongHold(down.id, down.position, touchSlop, DROP_PRESS_TIMEOUT_MS)
                    if (!held) return@awaitEachGesture
                    val coord = draftScreenToCoordinate(mapState, density, down.position) ?: return@awaitEachGesture
                    val metersPerDp = mapState.metersPerDpAtLatitude(coord.latitude) ?: 1.0
                    val hitLeg = findNearestLegWithin(
                        coordinate = coord,
                        legs = overlayProvider().legs,
                        toleranceMeters = LEG_HIT_TOLERANCE_DP * metersPerDp
                    )
                    handlerProvider()?.onDropPin(coord, hitLeg)
                }
            }
    )
}

@Composable
private fun MapOverlayScope.DraftTimeBubble(
    coordinate: GeoCoordinate,
    label: String,
    spokenDescription: String,
    isPrimary: Boolean,
    testTag: String,
    onClick: (() -> Unit)?
) {
    val containerColor = if (isPrimary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val placedModifier = Modifier
        .placedAt(Position(longitude = coordinate.longitude, latitude = coordinate.latitude), Alignment.Center)
        .defaultMinSize(minHeight = BubbleMinHeight)
        .semantics {
            contentDescription = spokenDescription
            if (onClick != null) role = Role.Button
        }
        .testTag(testTag)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            shadowElevation = PinShadowElevation,
            modifier = placedModifier
        ) {
            BubbleLabel(label)
        }
    } else {
        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            shadowElevation = PinShadowElevation,
            modifier = placedModifier
        ) {
            BubbleLabel(label)
        }
    }
}

@Composable
private fun BubbleLabel(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(horizontal = BubblePaddingHorizontal, vertical = BubblePaddingVertical)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun MapOverlayScope.DraftDroppedPinMarker(coordinate: GeoCoordinate, onClick: () -> Unit) {
    val description = stringResource(R.string.trail_draft_dropped_pin_description)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .placedAt(Position(longitude = coordinate.longitude, latitude = coordinate.latitude), Alignment.Center)
            .size(PinTouchTargetSize)
            .semantics { contentDescription = description }
            .testTag(OpenHikesTestTags.TRAIL_DRAFT_DROPPED_PIN)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                color = DroppedPinColor,
                shape = CircleShape,
                border = BorderStroke(PinBorderWidth, DraftBorderColor),
                shadowElevation = PinShadowElevation,
                modifier = Modifier.size(PinVisualDiameter)
            ) {}
        }
    }
}

@Composable
private fun MapOverlayScope.DraftStopPinMarker(
    pin: MapTrailDraftPin,
    coordinate: GeoCoordinate,
    mapState: MapState,
    density: Density,
    handlerProvider: () -> TrailDraftMapInteractionHandler?,
    onPanLockChanged: (Boolean) -> Unit
) {
    var topLeftInParent by remember { mutableStateOf(Offset.Zero) }
    val currentPin by rememberUpdatedState(pin)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .placedAt(Position(longitude = coordinate.longitude, latitude = coordinate.latitude), Alignment.Center)
            .size(PinTouchTargetSize)
            .onGloballyPositioned { coords -> topLeftInParent = coords.positionInParent() }
            .pointerInput(pin.id) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    val isLongHold = awaitDraftLongHold(down.id, down.position, touchSlop, DRAG_HOLD_TIMEOUT_MS)
                    val handler = handlerProvider() ?: return@awaitEachGesture
                    if (!isLongHold) {
                        handler.onTapStopPin(currentPin.id)
                        return@awaitEachGesture
                    }
                    if (!handler.onBeginPinDrag(currentPin.index)) return@awaitEachGesture
                    onPanLockChanged(true)
                    try {
                        trackPinDrag(down.id, { topLeftInParent }, mapState, density, handler)
                    } finally {
                        onPanLockChanged(false)
                    }
                }
            }
            .semantics {
                contentDescription = pin.label
                role = Role.Button
            }
            .testTag(OpenHikesTestTags.trailDraftPin(pin.index + 1))
    ) {
        Surface(
            color = DraftAccentColor,
            shape = CircleShape,
            border = BorderStroke(PinBorderWidth, DraftBorderColor),
            shadowElevation = PinShadowElevation,
            modifier = Modifier.size(PinVisualDiameter)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (pin.badgeText.isNotEmpty()) {
                    Text(
                        text = pin.badgeText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitDraftLongHold(
    pointerId: PointerId,
    origin: Offset,
    touchSlop: Float,
    timeoutMs: Long
): Boolean = try {
    withTimeout(timeoutMs) {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: return@withTimeout false
            if (!change.pressed || change.isConsumed) return@withTimeout false
            if (hypot(change.position.x - origin.x, change.position.y - origin.y) > touchSlop) {
                return@withTimeout false
            }
        }
    }
    false
} catch (_: TimeoutCancellationException) {
    true
}

private suspend fun AwaitPointerEventScope.trackPinDrag(
    pointerId: PointerId,
    topLeftProvider: () -> Offset,
    mapState: MapState,
    density: Density,
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
        draftScreenToCoordinate(mapState, density, parentOffset)?.let(handler::onMovePinDrag)
    }
}

private fun draftScreenToCoordinate(mapState: MapState, density: Density, offset: Offset): GeoCoordinate? {
    val dpOffset = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
    return mapState.positionFromScreenLocation(dpOffset)
        ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        ?.let { GeoCoordinate(latitude = it.latitude, longitude = it.longitude) }
}

private fun findNearestLegWithin(
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
