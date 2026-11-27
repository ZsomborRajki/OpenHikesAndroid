package app.openhikes.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteChevronShade
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import kotlin.math.min
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private const val PAUSED_SEGMENT_OPACITY = 0.45f
private const val PAUSED_DASH_LENGTH = 2.0
private const val PAUSED_GAP_LENGTH = 2.5
private const val SELECTED_WIDTH_BONUS_DP = 1.5f
private const val SELECTED_OPACITY = 1.0f
private const val UNSELECTED_OPACITY = 0.88f
private const val LIBRARY_LINE_OPACITY = 0.6f
private const val LIBRARY_LINE_WIDTH_DP = 3.5f
private const val BORDER_MULTIPLIER = 2f
private const val ICON_PADDING_DP = 4f
private const val HALF_DIVISOR = 2f
private const val HIGHLIGHT_RADIUS_DP = 7.0f
private const val HIGHLIGHT_STROKE_DP = 2.5f
private const val WALK_COVERAGE_CASING_WIDTH_DP = 9.0f
private const val WALK_COVERAGE_LINE_WIDTH_DP = 6.0f
private const val COLOR_RADIX = 16
private const val HEX_RGB_LENGTH = 6
private const val HEX_RGBA_LENGTH = 8
private const val SHIFT_24 = 24
private const val SHIFT_16 = 16
private const val SHIFT_8 = 8
private const val FULL_ALPHA = 0xFFL
private const val BYTE_MASK = 0xFFL

internal data class RouteChevronRenderSpec(
    val spacingDp: Float,
    val iconWidthDp: Float,
    val iconHeightDp: Float,
    val halfLengthDp: Float,
    val halfWidthDp: Float,
    val strokeWidthDp: Float,
    val color: Color,
    val borderColor: Color?,
    val borderStrokeWidthDp: Float?
)

internal data class WalkedRouteRenderPlan(
    val drawsLine: Boolean,
    val lineCap: LineCap,
    val lineColor: Color,
    val lineWidthDp: Float,
    val lineDashArray: List<Number>?,
    val borderColor: Color?,
    val borderWidthDp: Float?,
    val borderDashArray: List<Number>?,
    val chevronSpec: RouteChevronRenderSpec?
)

internal data class LibraryLineRenderPlan(
    val lineColor: Color,
    val lineWidthDp: Float,
    val borderColor: Color,
    val borderWidthDp: Float,
    val opacity: Float
)

internal fun buildLibraryLineRenderPlan(line: MapLibraryLineOverlay): LibraryLineRenderPlan {
    val parsed = parseHexColorOrDefault(
        line.tintHex,
        parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green)
    )
    val opaqueTint = parsed.copy(alpha = 1.0f)
    val borderOutsetDp = RouteBorder.width(LIBRARY_LINE_WIDTH_DP.toDouble()).toFloat() * BORDER_MULTIPLIER
    return LibraryLineRenderPlan(
        lineColor = opaqueTint,
        lineWidthDp = LIBRARY_LINE_WIDTH_DP,
        borderColor = Color.Black,
        borderWidthDp = LIBRARY_LINE_WIDTH_DP + borderOutsetDp,
        opacity = LIBRARY_LINE_OPACITY
    )
}

/**
 * Draws every unselected finished hike in the user's library faded beneath the selected route,
 * oldest first so the newest hike renders on top.
 */
@Composable
@MaplibreComposable
internal fun LibraryLineOverlayLayers(libraryLines: List<MapLibraryLineOverlay>) {
    for (line in libraryLines.asReversed()) {
        if (line.runs.isEmpty()) continue
        key("library-${line.hikeId.value}") {
            val plan = remember(line.tintHex) { buildLibraryLineRenderPlan(line) }
            val sourceData = remember(line.runs) { buildMultiLineFeatureCollection(line.runs) }
            val source = rememberGeoJsonSource(data = sourceData)
            LineLayer(
                id = "library-line-border-${line.hikeId.value}",
                source = source,
                color = const(plan.borderColor),
                width = const(plan.borderWidthDp.dp),
                opacity = const(plan.opacity),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round)
            )
            LineLayer(
                id = "library-line-stroke-${line.hikeId.value}",
                source = source,
                color = const(plan.lineColor),
                width = const(plan.lineWidthDp.dp),
                opacity = const(plan.opacity),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round)
            )
        }
    }
}

@Composable
@MaplibreComposable
internal fun RouteOverlayLayers(routes: List<MapRouteOverlay>, idPrefix: String = "") {
    for (route in routes) {
        key("$idPrefix${route.hikeId.value}") {
            val lineColor = parseHexColorOrDefault(
                route.tintHex,
                parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green)
            )
            val widthDp = if (route.isSelected) route.widthDp + SELECTED_WIDTH_BONUS_DP else route.widthDp
            val opacity = if (route.isSelected) SELECTED_OPACITY else UNSELECTED_OPACITY

            if (route.walkedSegments.isNotEmpty()) {
                WalkedRouteLayers(route = route, opacity = opacity, idPrefix = idPrefix)
            }
            if (route.pausedSegments.isNotEmpty()) {
                PausedRouteLayer(route = route, lineColor = lineColor, widthDp = widthDp, idPrefix = idPrefix)
            }
        }
    }
}

@Composable
@MaplibreComposable
private fun WalkedRouteLayers(route: MapRouteOverlay, opacity: Float, idPrefix: String = "") {
    val plan = remember(route) { buildWalkedRouteRenderPlan(route) }
    val walkedSourceData = remember(route.walkedSegments) {
        buildMultiLineFeatureCollection(route.walkedSegments)
    }
    val walkedSource = rememberGeoJsonSource(data = walkedSourceData)

    if (plan.drawsLine && plan.borderColor != null && plan.borderWidthDp != null) {
        LineLayer(
            id = "${idPrefix}route-border-${route.hikeId.value}",
            source = walkedSource,
            color = const(plan.borderColor),
            width = const(plan.borderWidthDp.dp),
            opacity = const(opacity),
            cap = const(plan.lineCap),
            join = const(LineJoin.Round),
            dasharray = plan.borderDashArray?.let(::const)
        )
    }

    if (plan.drawsLine) {
        LineLayer(
            id = "${idPrefix}route-line-${route.hikeId.value}",
            source = walkedSource,
            color = const(plan.lineColor),
            width = const(plan.lineWidthDp.dp),
            opacity = const(opacity),
            cap = const(plan.lineCap),
            join = const(LineJoin.Round),
            dasharray = plan.lineDashArray?.let(::const)
        )
        if (route.shadedStretches.isNotEmpty()) {
            ShadedRouteLineLayer(route = route, plan = plan, opacity = opacity, idPrefix = idPrefix)
        }
    }

    plan.chevronSpec?.let { chevron ->
        RouteChevronLayers(
            route = route,
            plan = plan,
            chevron = chevron,
            walkedSource = walkedSource,
            opacity = opacity,
            idPrefix = idPrefix
        )
    }
}

@Composable
@MaplibreComposable
private fun RouteChevronLayers(
    route: MapRouteOverlay,
    plan: WalkedRouteRenderPlan,
    chevron: RouteChevronRenderSpec,
    walkedSource: org.maplibre.compose.sources.GeoJsonSource,
    opacity: Float,
    idPrefix: String
) {
    val chevronPainter = remember(chevron) { RouteChevronPainter(chevron) }
    SymbolLayer(
        id = "${idPrefix}route-chevrons-${route.hikeId.value}",
        source = walkedSource,
        placement = const(SymbolPlacement.Line),
        spacing = const(chevron.spacingDp.dp),
        iconImage = image(
            value = chevronPainter,
            size = DpSize(chevron.iconWidthDp.dp, chevron.iconHeightDp.dp)
        ),
        iconOpacity = const(opacity),
        iconRotationAlignment = const(IconRotationAlignment.Map),
        iconPitchAlignment = const(IconPitchAlignment.Map),
        iconKeepUpright = const(false),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true)
    )
    if (!plan.drawsLine && route.shadedStretches.isNotEmpty()) {
        ShadedArrowheadLayers(
            route = route,
            plan = plan,
            opacity = (opacity * plan.lineColor.alpha).coerceIn(0f, 1f),
            idPrefix = idPrefix
        )
    }
}

internal fun buildWalkedRouteRenderPlan(route: MapRouteOverlay): WalkedRouteRenderPlan {
    val lineColor = parseHexColorOrDefault(
        route.tintHex,
        parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green)
    )
    val lineWidthDp = if (route.isSelected) route.widthDp + SELECTED_WIDTH_BONUS_DP else route.widthDp
    val drawsLine = route.linePattern.drawsLine
    val hasBorder = RouteBorder.isVisible(route.borderHex)
    val resolvedBorderColor = if (hasBorder) {
        parseHexColorOrDefault(RouteBorder.resolvedBorderHex(route.borderHex), Color.Black)
    } else {
        null
    }
    val borderOutsetDp = RouteBorder.width(lineWidthDp.toDouble()).toFloat() * BORDER_MULTIPLIER
    val borderWidthDp = if (drawsLine && hasBorder) lineWidthDp + borderOutsetDp else null
    val lineDashArray = if (drawsLine) {
        mapLibreDashArray(route.linePattern, routeWidthDp = lineWidthDp, layerWidthDp = lineWidthDp)
    } else {
        null
    }
    val borderDashArray = if (drawsLine && borderWidthDp != null) {
        mapLibreDashArray(route.linePattern, routeWidthDp = lineWidthDp, layerWidthDp = borderWidthDp)
    } else {
        null
    }

    return WalkedRouteRenderPlan(
        drawsLine = drawsLine,
        lineCap = if (route.linePattern.usesRoundCap) LineCap.Round else LineCap.Butt,
        lineColor = lineColor,
        lineWidthDp = lineWidthDp,
        lineDashArray = lineDashArray,
        borderColor = resolvedBorderColor,
        borderWidthDp = borderWidthDp,
        borderDashArray = borderDashArray,
        chevronSpec = buildChevronRenderSpec(route.linePattern, lineColor, resolvedBorderColor, lineWidthDp)
    )
}

internal fun buildChevronRenderSpec(
    pattern: RouteLinePattern,
    lineColor: Color,
    resolvedBorderColor: Color?,
    lineWidthDp: Float
): RouteChevronRenderSpec? {
    val metrics = pattern.chevronMetrics(lineWidthDp.toDouble()) ?: return null
    val halfLengthDp = metrics.halfLengthDp.toFloat()
    val halfWidthDp = metrics.halfWidthDp.toFloat()
    val strokeWidthDp = metrics.strokeWidthDp.toFloat()
    val borderColor = if (pattern.chevronsUseRouteTint) resolvedBorderColor else null
    val borderStrokeWidthDp = if (borderColor != null) {
        strokeWidthDp + RouteBorder.width(lineWidthDp.toDouble()).toFloat() * BORDER_MULTIPLIER
    } else {
        null
    }
    val color = if (pattern.chevronsUseRouteTint) {
        lineColor
    } else {
        val gray = RouteChevronShade.contrastingGray(lineColor.luminance())
        Color(red = gray, green = gray, blue = gray, alpha = RouteChevronShade.ALPHA)
    }
    val maxStrokeDp = borderStrokeWidthDp ?: strokeWidthDp
    return RouteChevronRenderSpec(
        spacingDp = metrics.spacingDp.toFloat(),
        iconWidthDp = halfLengthDp * HALF_DIVISOR + maxStrokeDp + ICON_PADDING_DP,
        iconHeightDp = halfWidthDp * HALF_DIVISOR + maxStrokeDp + ICON_PADDING_DP,
        halfLengthDp = halfLengthDp,
        halfWidthDp = halfWidthDp,
        strokeWidthDp = strokeWidthDp,
        color = color,
        borderColor = borderColor,
        borderStrokeWidthDp = borderStrokeWidthDp
    )
}

/**
 * Converts screen-dp dash lengths into MapLibre's line-width-relative `line-dasharray` multiples
 * so dashes stay true to desired dp sizes and keep border and foreground in lockstep phase.
 */
internal fun mapLibreDashArray(pattern: RouteLinePattern, routeWidthDp: Float, layerWidthDp: Float): List<Number>? {
    val dashLengthsDp = if (layerWidthDp > 0f) pattern.dashLengths(routeWidthDp.toDouble()) else emptyList()
    return dashLengthsDp.takeIf { it.isNotEmpty() }?.map { it / layerWidthDp.toDouble() }
}

internal data class RouteChevronPainter(private val spec: RouteChevronRenderSpec) : Painter() {
    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val scale = min(size.width / spec.iconWidthDp, size.height / spec.iconHeightDp)
        val centerX = size.width / HALF_DIVISOR
        val centerY = size.height / HALF_DIVISOR
        val halfLength = spec.halfLengthDp * scale
        val halfWidth = spec.halfWidthDp * scale
        val chevronPath = Path().apply {
            moveTo(centerX - halfLength, centerY - halfWidth)
            lineTo(centerX + halfLength, centerY)
            lineTo(centerX - halfLength, centerY + halfWidth)
        }
        spec.borderColor?.let { border ->
            spec.borderStrokeWidthDp?.let { borderStrokeDp ->
                drawPath(
                    path = chevronPath,
                    color = border,
                    style = Stroke(
                        width = borderStrokeDp * scale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        drawPath(
            path = chevronPath,
            color = spec.color,
            style = Stroke(
                width = spec.strokeWidthDp * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
@MaplibreComposable
private fun PausedRouteLayer(route: MapRouteOverlay, lineColor: Color, widthDp: Float, idPrefix: String = "") {
    val pausedSourceData = remember(route.pausedSegments) {
        buildMultiLineFeatureCollection(route.pausedSegments)
    }
    val pausedSource = rememberGeoJsonSource(data = pausedSourceData)
    LineLayer(
        id = "${idPrefix}route-paused-${route.hikeId.value}",
        source = pausedSource,
        color = const(lineColor),
        width = const(widthDp.dp),
        opacity = const(PAUSED_SEGMENT_OPACITY),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
        dasharray = const(listOf<Number>(PAUSED_DASH_LENGTH, PAUSED_GAP_LENGTH))
    )
}

@Composable
@MaplibreComposable
internal fun LiveRecordingRouteLayers(liveOverlayState: StateFlow<MapRouteOverlay?>) {
    val liveRoute by liveOverlayState.collectAsStateWithLifecycle()
    val current = liveRoute ?: return
    RouteOverlayLayers(routes = listOf(current))
}

@Composable
@MaplibreComposable
internal fun WalkCoverageOverlayLayers(walkCoverageState: StateFlow<MapWalkCoverageOverlay?>) {
    val coverage by walkCoverageState.collectAsStateWithLifecycle()
    val current = coverage ?: return
    if (current.segments.isEmpty()) return
    val lineColor = parseHexColorOrDefault(
        current.tintHex,
        parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green)
    )
    val data = remember(current.segments) { buildMultiLineFeatureCollection(current.segments) }
    val source = rememberGeoJsonSource(data = data)
    LineLayer(
        id = "walk-coverage-casing",
        source = source,
        color = const(Color.White),
        width = const(WALK_COVERAGE_CASING_WIDTH_DP.dp),
        opacity = const(SELECTED_OPACITY),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round)
    )
    LineLayer(
        id = "walk-coverage-line",
        source = source,
        color = const(lineColor),
        width = const(WALK_COVERAGE_LINE_WIDTH_DP.dp),
        opacity = const(SELECTED_OPACITY),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round)
    )
}

@Composable
@MaplibreComposable
internal fun HighlightMarkerLayers(highlightState: StateFlow<MapRouteHighlight?>) {
    val highlight by highlightState.collectAsStateWithLifecycle()
    val current = highlight ?: return
    val fillColor = parseHexColorOrDefault(current.tintHex, Color.Green)
    val pointData = remember(current.coordinate) {
        val pos = Position(longitude = current.coordinate.longitude, latitude = current.coordinate.latitude)
        GeoJsonData.Features(FeatureCollection(listOf(Feature<Point, JsonObject?>(Point(pos), null))))
    }
    val source = rememberGeoJsonSource(data = pointData)
    CircleLayer(
        id = "route-highlight-marker",
        source = source,
        color = const(fillColor),
        radius = const(HIGHLIGHT_RADIUS_DP.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(HIGHLIGHT_STROKE_DP.dp)
    )
}

internal fun buildMultiLineFeatureCollection(segments: List<List<GeoCoordinate>>): GeoJsonData {
    val lines = segments.mapNotNull { segment ->
        if (segment.size < 2) {
            null
        } else {
            segment.map { Position(longitude = it.longitude, latitude = it.latitude) }
        }
    }
    val geometry = MultiLineString(lines)
    return GeoJsonData.Features(FeatureCollection(listOf(Feature<MultiLineString, JsonObject?>(geometry, null))))
}

internal fun parseHexColorOrDefault(hex: String, defaultColor: Color): Color {
    val clean = hex.trim().removePrefix("#")
    val parsed = clean.toLongOrNull(COLOR_RADIX) ?: return defaultColor
    return when (clean.length) {
        HEX_RGB_LENGTH -> {
            val r = ((parsed ushr SHIFT_16) and BYTE_MASK).toInt()
            val g = ((parsed ushr SHIFT_8) and BYTE_MASK).toInt()
            val b = (parsed and BYTE_MASK).toInt()
            Color(red = r, green = g, blue = b, alpha = FULL_ALPHA.toInt())
        }
        HEX_RGBA_LENGTH -> {
            val r = ((parsed ushr SHIFT_24) and BYTE_MASK).toInt()
            val g = ((parsed ushr SHIFT_16) and BYTE_MASK).toInt()
            val b = ((parsed ushr SHIFT_8) and BYTE_MASK).toInt()
            val a = (parsed and BYTE_MASK).toInt()
            Color(red = r, green = g, blue = b, alpha = a)
        }
        else -> defaultColor
    }
}
