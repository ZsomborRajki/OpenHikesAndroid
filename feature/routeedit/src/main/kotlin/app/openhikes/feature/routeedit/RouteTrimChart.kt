package app.openhikes.feature.routeedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

private val TrimmerHeight = 96.dp
private val TrimmerCardCorner = 16.dp
private val HandleVisualWidth = 18.dp
private val HandleHitWidth = 48.dp
private val HandleOuterCorner = 8.dp
private val FrameBorderWidth = 3.dp
private val HandleIconSize = 16.dp
private val StretchAccentColor = Color(0xFFF57C00)
private const val SILHOUETTE_FILL_ALPHA = 0.35f
private const val FLOOR_SHARE = 0.25f
private const val DIM_ALPHA = 0.62f
private const val HALF_DIVISOR = 2f

/**
 * Video-trimmer-style elevation profile chart with isolated start/end drag handles.
 *
 * The elevation silhouette recomposes only when [profile] changes; the handle overlay reads
 * [rangeStateFlow] independently so dragging never redraws the underlying silhouette path.
 */
@Composable
internal fun RouteTrimChart(
    profile: RouteProfile?,
    tintHex: String,
    distanceUnit: DistanceUnit,
    isSelectingStretch: Boolean,
    rangeStateFlow: StateFlow<RouteTrimRangeState>,
    onMoveHandle: (isStart: Boolean, meters: Double) -> Unit,
    onAdjustHandle: (isStart: Boolean, direction: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val routeTint = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    val accentColor = if (isSelectingStretch) StretchAccentColor else routeTint

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(TrimmerCardCorner),
        modifier = modifier
            .fillMaxWidth()
            .height(TrimmerHeight)
    ) {
        if (profile == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                RouteTrimSilhouette(
                    profile = profile,
                    tint = routeTint,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = HandleVisualWidth)
                )
                RouteTrimRangeOverlay(
                    rangeStateFlow = rangeStateFlow,
                    accentColor = accentColor,
                    distanceUnit = distanceUnit,
                    isSelectingStretch = isSelectingStretch,
                    onMoveHandle = onMoveHandle,
                    onAdjustHandle = onAdjustHandle,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun RouteTrimSilhouette(profile: RouteProfile, tint: Color, modifier: Modifier = Modifier) {
    val fillColor = tint.copy(alpha = SILHOUETTE_FILL_ALPHA)
    Canvas(modifier = modifier) {
        val total = profile.totalDistanceMeters
        val range = profile.elevationRange
        val samples = profile.samples
        val hasElevationRange = range != null && range.endInclusive > range.start
        if (total <= 0.0 || !hasElevationRange || samples.size <= 1) {
            val inset = size.height * FLOOR_SHARE
            drawRect(
                color = fillColor,
                topLeft = Offset(0f, inset),
                size = Size(size.width, (size.height - inset * 2f).coerceAtLeast(1f))
            )
            return@Canvas
        }
        val span = range.endInclusive - range.start
        val path = Path().apply {
            moveTo(0f, size.height)
            for (sample in samples) {
                val across = (sample.distanceMeters / total).toFloat().coerceIn(0f, 1f)
                val up = FLOOR_SHARE +
                    (1f - FLOOR_SHARE) * ((sample.elevationMeters - range.start) / span).toFloat().coerceIn(0f, 1f)
                lineTo(size.width * across, size.height * (1f - up))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path = path, color = fillColor)
    }
}

@Composable
private fun RouteTrimRangeOverlay(
    rangeStateFlow: StateFlow<RouteTrimRangeState>,
    accentColor: Color,
    distanceUnit: DistanceUnit,
    isSelectingStretch: Boolean,
    onMoveHandle: (isStart: Boolean, meters: Double) -> Unit,
    onAdjustHandle: (isStart: Boolean, direction: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rangeState by rangeStateFlow.collectAsStateWithLifecycle()
    val dimColor = MaterialTheme.colorScheme.surface.copy(alpha = DIM_ALPHA)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val handleWidthPx = with(density) { HandleVisualWidth.toPx() }
        val hitWidthPx = with(density) { HandleHitWidth.toPx() }
        val frameWidthPx = with(density) { FrameBorderWidth.toPx() }
        val plotWidthPx = max(totalWidthPx - 2f * handleWidthPx, 1f)
        val totalMeters = max(rangeState.totalMeters, 1.0)
        val startX = handleWidthPx + plotWidthPx * (rangeState.startMeters / totalMeters).toFloat().coerceIn(0f, 1f)
        val endX = handleWidthPx + plotWidthPx * (rangeState.endMeters / totalMeters).toFloat().coerceIn(0f, 1f)
        val metersPerPx = totalMeters / plotWidthPx.toDouble()

        RouteTrimBandCanvas(
            startX = startX,
            endX = endX,
            frameWidthPx = frameWidthPx,
            dimColor = dimColor,
            accentColor = accentColor,
            isSelectingStretch = isSelectingStretch
        )

        val startHandleCenterX = startX - handleWidthPx / HALF_DIVISOR
        val endHandleCenterX = endX + handleWidthPx / HALF_DIVISOR

        TrimmerHandle(
            isStart = true,
            positionMeters = rangeState.startMeters,
            distanceUnit = distanceUnit,
            isSelectingStretch = isSelectingStretch,
            accentColor = accentColor,
            centerOffsetX = startHandleCenterX,
            hitWidthPx = hitWidthPx,
            metersPerPx = metersPerPx,
            onMove = { meters -> onMoveHandle(true, meters) },
            onAdjust = { dir -> onAdjustHandle(true, dir) }
        )
        TrimmerHandle(
            isStart = false,
            positionMeters = rangeState.endMeters,
            distanceUnit = distanceUnit,
            isSelectingStretch = isSelectingStretch,
            accentColor = accentColor,
            centerOffsetX = endHandleCenterX,
            hitWidthPx = hitWidthPx,
            metersPerPx = metersPerPx,
            onMove = { meters -> onMoveHandle(false, meters) },
            onAdjust = { dir -> onAdjustHandle(false, dir) }
        )
    }
}

@Composable
private fun RouteTrimBandCanvas(
    startX: Float,
    endX: Float,
    frameWidthPx: Float,
    dimColor: Color,
    accentColor: Color,
    isSelectingStretch: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (!isSelectingStretch) {
            if (startX > 0f) drawRect(color = dimColor, topLeft = Offset.Zero, size = Size(startX, size.height))
            if (endX < size.width) {
                drawRect(
                    color = dimColor,
                    topLeft = Offset(endX, 0f),
                    size = Size((size.width - endX).coerceAtLeast(0f), size.height)
                )
            }
        }
        val railWidth = (endX - startX).coerceAtLeast(0f)
        drawRect(color = accentColor, topLeft = Offset(startX, 0f), size = Size(railWidth, frameWidthPx))
        drawRect(
            color = accentColor,
            topLeft = Offset(startX, size.height - frameWidthPx),
            size = Size(railWidth, frameWidthPx)
        )
    }
}

@Composable
private fun TrimmerHandle(
    isStart: Boolean,
    positionMeters: Double,
    distanceUnit: DistanceUnit,
    isSelectingStretch: Boolean,
    accentColor: Color,
    centerOffsetX: Float,
    hitWidthPx: Float,
    metersPerPx: Double,
    onMove: (Double) -> Unit,
    onAdjust: (Int) -> Unit
) {
    val latestPosition by rememberUpdatedState(positionMeters)
    val latestMetersPerPx by rememberUpdatedState(metersPerPx)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnAdjust by rememberUpdatedState(onAdjust)
    val label = trimmerHandleLabel(isStart, isSelectingStretch)
    val valueText = HikeMetricsFormatter.formatDistance(positionMeters, distanceUnit)
    val forwardLabel = stringResource(R.string.route_edit_handle_move_forward)
    val backwardLabel = stringResource(R.string.route_edit_handle_move_backward)
    val tag = if (isStart) OpenHikesTestTags.ROUTE_TRIM_START else OpenHikesTestTags.ROUTE_TRIM_END

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset(x = (centerOffsetX - hitWidthPx / HALF_DIVISOR).roundToInt(), y = 0) }
            .width(HandleHitWidth)
            .height(TrimmerHeight)
            .pointerInput(isStart) {
                var dragOriginMeters = 0.0
                var accumulatedPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragOriginMeters = latestPosition
                        accumulatedPx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedPx += dragAmount
                        latestOnMove(dragOriginMeters + accumulatedPx * latestMetersPerPx)
                    }
                )
            }
            .semantics {
                contentDescription = label
                stateDescription = valueText
                customActions = listOf(
                    CustomAccessibilityAction(forwardLabel) {
                        latestOnAdjust(1)
                        true
                    },
                    CustomAccessibilityAction(backwardLabel) {
                        latestOnAdjust(-1)
                        true
                    }
                )
            }
            .testTag(tag)
    ) {
        TrimmerHandleGrip(isStart = isStart, accentColor = accentColor)
    }
}

@Composable
private fun trimmerHandleLabel(isStart: Boolean, isSelectingStretch: Boolean): String = when {
    isStart && isSelectingStretch -> stringResource(R.string.route_edit_handle_start_stretch)
    !isStart && isSelectingStretch -> stringResource(R.string.route_edit_handle_end_stretch)
    isStart -> stringResource(R.string.route_edit_handle_start_route)
    else -> stringResource(R.string.route_edit_handle_end_route)
}

@Composable
private fun TrimmerHandleGrip(isStart: Boolean, accentColor: Color) {
    val shape = if (isStart) {
        RoundedCornerShape(topStart = HandleOuterCorner, bottomStart = HandleOuterCorner)
    } else {
        RoundedCornerShape(topEnd = HandleOuterCorner, bottomEnd = HandleOuterCorner)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(HandleVisualWidth)
            .height(TrimmerHeight)
            .clip(shape)
            .background(accentColor)
    ) {
        Icon(
            painter = painterResource(if (isStart) OpenHikesIcons.ChevronLeft else OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(HandleIconSize)
        )
    }
}
