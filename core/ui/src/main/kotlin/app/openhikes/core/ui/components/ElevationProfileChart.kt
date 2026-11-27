package app.openhikes.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ElevationSample
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val ChartPlotHeight = 184.dp
private val PlaceholderHeight = 180.dp
private val PlaceholderCornerRadius = 16.dp
private val PlaceholderIconSize = 32.dp
private val PlaceholderSpinnerSize = 28.dp
private val PlaceholderSpinnerStroke = 2.5.dp
private const val PLACEHOLDER_TINT_ALPHA = 0.12f
private val AxisPaddingTop = 28.dp
private val AxisPaddingBottom = 8.dp
private val AxisLabelPaddingHorizontal = 4.dp
private val ReadoutCornerRadius = 10.dp
private val ReadoutElevation = 2.dp
private val ReadoutPaddingHorizontal = 8.dp
private val ReadoutPaddingVertical = 4.dp
private const val VERTICAL_EXAGGERATION = 3.0
private const val MAX_SPAN_MULTIPLIER = 4.0
private const val DOMAIN_PADDING_FRACTION = 0.15
private const val FLAT_ELEVATION_MARGIN_METERS = 10.0
private const val AREA_TOP_ALPHA = 0.42f
private const val AREA_BOTTOM_ALPHA = 0.10f
private const val PAUSE_RULE_ALPHA = 0.40f
private const val SCRUB_RULE_ALPHA = 0.55f
private const val GRID_ALPHA = 0.22f
private const val LINE_WIDTH_DP = 2.5f
private const val MARKER_RADIUS_DP = 5.5f
private const val MARKER_HALO_RADIUS_DP = 8.0f
private const val ACCESSIBILITY_SCRUB_STEPS = 20.0
private const val PERCENT_MULTIPLIER = 100
private const val PAUSE_DASH_ON_PX = 4f
private const val PAUSE_DASH_OFF_PX = 6f
private const val SCRUB_DASH_ON_PX = 8f
private const val SCRUB_DASH_OFF_PX = 6f
private const val GRID_MIDPOINT_RATIO = 0.5f
private val GridLineRatios = floatArrayOf(0f, GRID_MIDPOINT_RATIO, 1f)

/**
 * Interactive elevation profile chart separating static plot rendering from draw-phase
 * scrub overlays so dragging or brisk tapping never recomposes the plot or parent detail card.
 */
@Composable
fun ElevationProfileChart(
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    tintHex: String,
    scrubDistanceProvider: () -> Double,
    onScrubDistance: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onScrubbingChanged: (Boolean) -> Unit = {}
) {
    if (profile.samples.size <= 1) return

    val accentColor = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary)
    var plotWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val plotHeightPx = with(density) { ChartPlotHeight.toPx() }
    val domain = remember(profile, plotWidthPx, plotHeightPx) {
        computeElevationDomain(profile, plotWidthPx, plotHeightPx)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartPlotHeight)
                .onSizeChanged { plotWidthPx = it.width }
        ) {
            ElevationProfilePlot(
                profile = profile,
                accentColor = accentColor,
                domainMin = domain.start,
                domainMax = domain.endInclusive,
                modifier = Modifier.fillMaxSize()
            )
            ElevationTouchAndAccessibilityOverlay(
                profile = profile,
                distanceUnit = distanceUnit,
                scrubDistanceProvider = scrubDistanceProvider,
                onScrubDistance = onScrubDistance,
                onScrubbingChanged = onScrubbingChanged,
                modifier = Modifier.fillMaxSize()
            )
            ElevationScrubOverlay(
                profile = profile,
                distanceUnit = distanceUnit,
                accentColor = accentColor,
                domainMin = domain.start,
                domainMax = domain.endInclusive,
                plotWidthPx = plotWidthPx,
                scrubDistanceProvider = scrubDistanceProvider,
                modifier = Modifier.fillMaxSize()
            )
        }
        ElevationAxisLabels(
            profile = profile,
            distanceUnit = distanceUnit,
            domainMin = domain.start,
            domainMax = domain.endInclusive
        )
    }
}

/**
 * Tinted Material 3 Expressive placeholder card displayed in the elevation chart slot when a route
 * has no heights yet or while Copernicus DEM heights are being read.
 */
@Composable
fun ElevationPlaceholderCard(tintHex: String, isReading: Boolean, modifier: Modifier = Modifier) {
    val accentColor = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary)
    val message = stringResource(
        if (isReading) R.string.elevation_placeholder_reading else R.string.elevation_placeholder_empty
    )
    Surface(
        shape = RoundedCornerShape(PlaceholderCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .height(PlaceholderHeight)
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.ELEVATION_PLACEHOLDER)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(accentColor.copy(alpha = PLACEHOLDER_TINT_ALPHA))
                .padding(horizontal = 16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isReading) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = PlaceholderSpinnerStroke,
                        modifier = Modifier.size(PlaceholderSpinnerSize)
                    )
                } else {
                    Icon(
                        painter = painterResource(OpenHikesIcons.TrendingUp),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(PlaceholderIconSize)
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ElevationProfilePlot(
    profile: RouteProfile,
    accentColor: Color,
    domainMin: Double,
    domainMax: Double,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GRID_ALPHA)
    val pauseRuleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PAUSE_RULE_ALPHA)
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(PAUSE_DASH_ON_PX, PAUSE_DASH_OFF_PX), 0f) }

    Canvas(modifier = modifier) {
        val topPad = AxisPaddingTop.toPx()
        val bottomPad = AxisPaddingBottom.toPx()
        val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val totalDistance = profile.totalDistanceMeters.coerceAtLeast(1.0)
        val elevSpan = (domainMax - domainMin).coerceAtLeast(1.0)

        for (ratio in GridLineRatios) {
            val y = topPad + plotHeight * ratio
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val geometry = PlotGeometry(size.width, topPad, plotHeight, totalDistance, domainMin, elevSpan)
        val (linePath, areaPath) = buildElevationPaths(samples = profile.samples, geometry = geometry)
        val baseY = topPad + plotHeight
        val areaColors = listOf(accentColor.copy(alpha = AREA_TOP_ALPHA), accentColor.copy(alpha = AREA_BOTTOM_ALPHA))

        drawPath(areaPath, Brush.verticalGradient(areaColors, startY = topPad, endY = baseY))
        drawPath(
            path = linePath,
            color = accentColor,
            style = Stroke(width = LINE_WIDTH_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        for (pauseDist in profile.pauseDistances) {
            val x = ((pauseDist / totalDistance).toFloat().coerceIn(0f, 1f)) * size.width
            drawLine(pauseRuleColor, Offset(x, topPad), Offset(x, baseY), 1.dp.toPx(), pathEffect = dashEffect)
        }
    }
}

private data class PlotGeometry(
    val width: Float,
    val topPad: Float,
    val plotHeight: Float,
    val totalDistance: Double,
    val domainMin: Double,
    val elevSpan: Double
)

private fun buildElevationPaths(samples: List<ElevationSample>, geometry: PlotGeometry): Pair<Path, Path> {
    val linePath = Path()
    val areaPath = Path()
    val baseY = geometry.topPad + geometry.plotHeight
    samples.forEachIndexed { index, sample ->
        val xFraction = (sample.distanceMeters / geometry.totalDistance).toFloat().coerceIn(0f, 1f)
        val x = xFraction * geometry.width
        val yNorm = ((sample.elevationMeters - geometry.domainMin) / geometry.elevSpan).toFloat().coerceIn(0f, 1f)
        val y = geometry.topPad + geometry.plotHeight * (1f - yNorm)
        if (index == 0) {
            linePath.moveTo(x, y)
            areaPath.moveTo(x, baseY)
            areaPath.lineTo(x, y)
        } else {
            linePath.lineTo(x, y)
            areaPath.lineTo(x, y)
        }
        if (index == samples.lastIndex) {
            areaPath.lineTo(x, baseY)
            areaPath.close()
        }
    }
    return linePath to areaPath
}

@Composable
private fun ElevationTouchAndAccessibilityOverlay(
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    scrubDistanceProvider: () -> Double,
    onScrubDistance: (Double) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnScrubDistance by rememberUpdatedState(onScrubDistance)
    val currentOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)
    val totalDistance = profile.totalDistanceMeters.coerceAtLeast(0.0)
    val currentDistance = scrubDistanceProvider().coerceIn(0.0, totalDistance)
    val sample = profile.sampleAt(currentDistance)
    val chartLabel = stringResource(R.string.elevation_chart_content_description)
    val forwardLabel = stringResource(R.string.elevation_chart_step_forward)
    val backwardLabel = stringResource(R.string.elevation_chart_step_backward)
    val spokenState = formatSpokenPosition(
        sample = sample,
        profile = profile,
        distanceUnit = distanceUnit,
        noDataText = stringResource(R.string.elevation_chart_no_data)
    )

    Box(
        modifier = modifier
            .scrubPointerInput(
                profile = profile,
                totalDistance = totalDistance,
                onScrubbingChanged = { currentOnScrubbingChanged(it) },
                onScrubDistance = { currentOnScrubDistance(it) }
            )
            .semantics {
                contentDescription = chartLabel
                stateDescription = spokenState
                customActions = buildScrubAccessibilityActions(
                    forwardLabel = forwardLabel,
                    backwardLabel = backwardLabel,
                    totalDistance = totalDistance,
                    scrubDistanceProvider = scrubDistanceProvider,
                    onScrubDistance = { currentOnScrubDistance(it) }
                )
            }
            .testTag(OpenHikesTestTags.ELEVATION_CHART)
    )
}

private class ScrubGestureState(val startX: Float, val startY: Float) {
    var isHorizontalScrub = false
    var yieldedToVertical = false
}

private class ScrubCallbacks(val onScrubbingChanged: (Boolean) -> Unit, val onScrubDistance: (Double) -> Unit)

private fun Modifier.scrubPointerInput(
    profile: RouteProfile,
    totalDistance: Double,
    onScrubbingChanged: (Boolean) -> Unit,
    onScrubDistance: (Double) -> Unit
): Modifier = pointerInput(profile) {
    val touchSlop = viewConfiguration.touchSlop
    val callbacks = ScrubCallbacks(onScrubbingChanged, onScrubDistance)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val state = ScrubGestureState(startX = down.position.x, startY = down.position.y)
        trackScrubPointer(
            pointerId = down.id,
            touchSlop = touchSlop,
            state = state,
            totalDistance = totalDistance,
            callbacks = callbacks
        )
        if (state.isHorizontalScrub) {
            onScrubbingChanged(false)
        }
    }
}

private suspend fun AwaitPointerEventScope.trackScrubPointer(
    pointerId: PointerId,
    touchSlop: Float,
    state: ScrubGestureState,
    totalDistance: Double,
    callbacks: ScrubCallbacks
) {
    var keepTracking = true
    while (keepTracking) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
        keepTracking = change != null &&
            processScrubChange(
                change = change,
                widthPx = size.width,
                touchSlop = touchSlop,
                state = state,
                totalDistance = totalDistance,
                callbacks = callbacks
            )
    }
}

@Suppress("LongParameterList")
private fun processScrubChange(
    change: PointerInputChange,
    widthPx: Int,
    touchSlop: Float,
    state: ScrubGestureState,
    totalDistance: Double,
    callbacks: ScrubCallbacks
): Boolean = when {
    !change.pressed -> {
        if (!state.isHorizontalScrub && !state.yieldedToVertical && !change.isConsumed) {
            change.consume()
            callbacks.onScrubDistance(distanceForOffset(change.position.x, widthPx, totalDistance))
        }
        false
    }
    change.isConsumed -> {
        state.yieldedToVertical = true
        false
    }
    state.isHorizontalScrub -> {
        change.consume()
        callbacks.onScrubDistance(distanceForOffset(change.position.x, widthPx, totalDistance))
        true
    }
    else -> {
        val dx = abs(change.position.x - state.startX)
        val dy = abs(change.position.y - state.startY)
        when {
            dx >= touchSlop && dx >= dy -> {
                state.isHorizontalScrub = true
                change.consume()
                callbacks.onScrubbingChanged(true)
                callbacks.onScrubDistance(distanceForOffset(change.position.x, widthPx, totalDistance))
                true
            }
            dy >= touchSlop && dy > dx -> {
                state.yieldedToVertical = true
                false
            }
            else -> true
        }
    }
}

private fun buildScrubAccessibilityActions(
    forwardLabel: String,
    backwardLabel: String,
    totalDistance: Double,
    scrubDistanceProvider: () -> Double,
    onScrubDistance: (Double) -> Unit
): List<CustomAccessibilityAction> = listOf(
    CustomAccessibilityAction(forwardLabel) {
        val step = totalDistance / ACCESSIBILITY_SCRUB_STEPS
        val next = (scrubDistanceProvider() + step).coerceIn(0.0, totalDistance)
        if (next != scrubDistanceProvider()) {
            onScrubDistance(next)
            true
        } else {
            false
        }
    },
    CustomAccessibilityAction(backwardLabel) {
        val step = totalDistance / ACCESSIBILITY_SCRUB_STEPS
        val next = (scrubDistanceProvider() - step).coerceIn(0.0, totalDistance)
        if (next != scrubDistanceProvider()) {
            onScrubDistance(next)
            true
        } else {
            false
        }
    }
)

@Composable
private fun ElevationScrubOverlay(
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    accentColor: Color,
    domainMin: Double,
    domainMax: Double,
    plotWidthPx: Int,
    scrubDistanceProvider: () -> Double,
    modifier: Modifier = Modifier
) {
    val ruleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SCRUB_RULE_ALPHA)
    val scrubDash = remember { PathEffect.dashPathEffect(floatArrayOf(SCRUB_DASH_ON_PX, SCRUB_DASH_OFF_PX), 0f) }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val topPad = AxisPaddingTop.toPx()
            val bottomPad = AxisPaddingBottom.toPx()
            val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val totalDistance = profile.totalDistanceMeters.coerceAtLeast(1.0)
            val elevSpan = (domainMax - domainMin).coerceAtLeast(1.0)
            val sample = profile.sampleAt(scrubDistanceProvider()) ?: return@Canvas
            val x = ((sample.distanceMeters / totalDistance).toFloat().coerceIn(0f, 1f)) * size.width
            val yNorm = ((sample.elevationMeters - domainMin) / elevSpan).toFloat().coerceIn(0f, 1f)
            val y = topPad + plotHeight * (1f - yNorm)

            drawLine(
                color = ruleColor,
                start = Offset(x, topPad),
                end = Offset(x, topPad + plotHeight),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = scrubDash
            )
            drawCircle(color = Color.White, radius = MARKER_HALO_RADIUS_DP.dp.toPx(), center = Offset(x, y))
            drawCircle(color = accentColor, radius = MARKER_RADIUS_DP.dp.toPx(), center = Offset(x, y))
        }

        ElevationReadoutBadge(
            profile = profile,
            distanceUnit = distanceUnit,
            plotWidthPx = plotWidthPx,
            scrubDistanceProvider = scrubDistanceProvider,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
private fun ElevationReadoutBadge(
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    plotWidthPx: Int,
    scrubDistanceProvider: () -> Double,
    modifier: Modifier = Modifier
) {
    var badgeWidthPx by remember { mutableIntStateOf(0) }
    val sample = profile.sampleAt(scrubDistanceProvider()) ?: return
    val totalDistance = profile.totalDistanceMeters.coerceAtLeast(1.0)
    val fraction = (sample.distanceMeters / totalDistance).toFloat().coerceIn(0f, 1f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(ReadoutCornerRadius),
        shadowElevation = ReadoutElevation,
        modifier = modifier
            .onSizeChanged { badgeWidthPx = it.width }
            .offset {
                val maxX = (plotWidthPx - badgeWidthPx).coerceAtLeast(0)
                val targetX = (plotWidthPx * fraction - badgeWidthPx / 2f).roundToInt().coerceIn(0, maxX)
                IntOffset(x = targetX, y = 0)
            }
            .testTag(OpenHikesTestTags.ELEVATION_READOUT)
    ) {
        Text(
            text = "${HikeMetricsFormatter.formatElevation(sample.elevationMeters, distanceUnit)} · " +
                HikeMetricsFormatter.formatDistance(sample.distanceMeters, distanceUnit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = ReadoutPaddingHorizontal, vertical = ReadoutPaddingVertical)
        )
    }
}

@Composable
private fun ElevationAxisLabels(
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    domainMin: Double,
    domainMax: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AxisLabelPaddingHorizontal),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = HikeMetricsFormatter.formatDistance(0.0, distanceUnit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${HikeMetricsFormatter.formatElevation(domainMin, distanceUnit)} – " +
                HikeMetricsFormatter.formatElevation(domainMax, distanceUnit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = HikeMetricsFormatter.formatDistance(profile.totalDistanceMeters, distanceUnit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun formatSpokenPosition(
    sample: ElevationSample?,
    profile: RouteProfile,
    distanceUnit: DistanceUnit,
    noDataText: String
): String {
    if (sample == null) return noDataText
    val elevation = HikeMetricsFormatter.formatSpokenElevation(sample.elevationMeters, distanceUnit)
    val distance = HikeMetricsFormatter.formatSpokenDistance(sample.distanceMeters, distanceUnit)
    val percent = ((profile.fractionComplete(sample.distanceMeters) ?: 0.0) * PERCENT_MULTIPLIER).roundToInt()
    return stringResource(R.string.elevation_chart_state_description, elevation, distance, percent)
}

private fun distanceForOffset(xPx: Float, widthPx: Int, totalDistanceMeters: Double): Double {
    if (widthPx <= 0 || totalDistanceMeters <= 0.0) return 0.0
    val ratio = (xPx / widthPx.toFloat()).coerceIn(0f, 1f)
    return ratio * totalDistanceMeters
}

private fun computeElevationDomain(
    profile: RouteProfile,
    plotWidthPx: Int,
    plotHeightPx: Float
): ClosedFloatingPointRange<Double> {
    val range = profile.elevationRange
    return when {
        range == null -> 0.0..1.0
        range.endInclusive - range.start <= 0.0 ->
            (range.start - FLAT_ELEVATION_MARGIN_METERS)..(range.endInclusive + FLAT_ELEVATION_MARGIN_METERS)
        plotWidthPx <= 0 || plotHeightPx <= 0f -> {
            val padding = (range.endInclusive - range.start) * DOMAIN_PADDING_FRACTION
            (range.start - padding)..(range.endInclusive + padding)
        }
        else -> {
            val dataSpan = range.endInclusive - range.start
            val totalDistance = max(profile.totalDistanceMeters, 1.0)
            val metersPerPxY = (totalDistance / plotWidthPx.toDouble()) / VERTICAL_EXAGGERATION
            val exaggeratedSpan = min(plotHeightPx.toDouble() * metersPerPxY, dataSpan * MAX_SPAN_MULTIPLIER)
            val span = max(exaggeratedSpan, dataSpan)
            val padding = span * DOMAIN_PADDING_FRACTION
            val mid = (range.start + range.endInclusive) / 2.0
            (mid - span / 2.0 - padding)..(mid + span / 2.0 + padding)
        }
    }
}
