package app.openhikes.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
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
private const val ARROWHEAD_DASH = 1.5
private const val ARROWHEAD_GAP = 3.0
private const val BORDER_MULTIPLIER = 2f
private const val HIGHLIGHT_RADIUS_DP = 7.0f
private const val HIGHLIGHT_STROKE_DP = 2.5f
private const val COLOR_RADIX = 16
private const val HEX_RGB_LENGTH = 6
private const val HEX_RGBA_LENGTH = 8
private const val SHIFT_24 = 24
private const val SHIFT_16 = 16
private const val SHIFT_8 = 8
private const val FULL_ALPHA = 0xFFL
private const val BYTE_MASK = 0xFFL

@Composable
@MaplibreComposable
internal fun RouteOverlayLayers(routes: List<MapRouteOverlay>) {
    for (route in routes) {
        key(route.hikeId.value) {
            val lineColor = parseHexColorOrDefault(
                route.tintHex,
                parseHexColorOrDefault(RouteTint.DEFAULT_HEX, Color.Green)
            )
            val widthDp = if (route.isSelected) route.widthDp + SELECTED_WIDTH_BONUS_DP else route.widthDp
            val opacity = if (route.isSelected) SELECTED_OPACITY else UNSELECTED_OPACITY

            if (route.walkedSegments.isNotEmpty()) {
                WalkedRouteLayers(route = route, lineColor = lineColor, widthDp = widthDp, opacity = opacity)
            }
            if (route.pausedSegments.isNotEmpty()) {
                PausedRouteLayer(route = route, lineColor = lineColor, widthDp = widthDp)
            }
        }
    }
}

@Composable
@MaplibreComposable
private fun WalkedRouteLayers(route: MapRouteOverlay, lineColor: Color, widthDp: Float, opacity: Float) {
    val lineCap = if (route.linePattern.usesRoundCap) LineCap.Round else LineCap.Butt
    val dashNumbers: List<Number>? = remember(route.linePattern, widthDp) {
        when (route.linePattern) {
            RouteLinePattern.ARROWHEADS -> listOf(ARROWHEAD_DASH, ARROWHEAD_GAP)
            else -> route.linePattern.dashLengths(widthDp.toDouble()).takeIf { it.isNotEmpty() }
        }
    }
    val walkedSourceData = remember(route.walkedSegments) {
        buildMultiLineFeatureCollection(route.walkedSegments)
    }
    val walkedSource = rememberGeoJsonSource(data = walkedSourceData)

    if (RouteBorder.isVisible(route.borderHex)) {
        val borderColor = parseHexColorOrDefault(RouteBorder.resolvedBorderHex(route.borderHex), Color.Black)
        val borderWidthDp = widthDp + RouteBorder.width(widthDp.toDouble()).toFloat() * BORDER_MULTIPLIER
        LineLayer(
            id = "route-border-${route.hikeId.value}",
            source = walkedSource,
            color = const(borderColor),
            width = const(borderWidthDp.dp),
            opacity = const(opacity),
            cap = const(lineCap),
            join = const(LineJoin.Round),
            dasharray = dashNumbers?.let { const(it) }
        )
    }

    LineLayer(
        id = "route-line-${route.hikeId.value}",
        source = walkedSource,
        color = const(lineColor),
        width = const(widthDp.dp),
        opacity = const(opacity),
        cap = const(lineCap),
        join = const(LineJoin.Round),
        dasharray = dashNumbers?.let { const(it) }
    )
}

@Composable
@MaplibreComposable
private fun PausedRouteLayer(route: MapRouteOverlay, lineColor: Color, widthDp: Float) {
    val pausedSourceData = remember(route.pausedSegments) {
        buildMultiLineFeatureCollection(route.pausedSegments)
    }
    val pausedSource = rememberGeoJsonSource(data = pausedSourceData)
    LineLayer(
        id = "route-paused-${route.hikeId.value}",
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

private fun buildMultiLineFeatureCollection(segments: List<List<GeoCoordinate>>): GeoJsonData {
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
