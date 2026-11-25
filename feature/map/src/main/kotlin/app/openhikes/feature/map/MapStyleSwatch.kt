package app.openhikes.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.openhikes.core.model.MapStyleId

private data class SwatchPalette(
    val ground: Color,
    val forest: Color,
    val water: Color,
    val contour: Color,
    val road: Color,
    val roadCasing: Color,
    val trail: Color
)

private val OpenHikesLightSwatch = SwatchPalette(
    ground = Color(0xFFF3EFE6),
    forest = Color(0xFFBED8AE),
    water = Color(0xFFA9D0E8),
    contour = Color(0xFFCBBBA4),
    road = Color(0xFFFFFFFF),
    roadCasing = Color(0xFFD5C8B5),
    trail = Color(0xFF7D5A3C)
)

private val OpenHikesDarkSwatch = SwatchPalette(
    ground = Color(0xFF1F2421),
    forest = Color(0xFF2D4331),
    water = Color(0xFF1E3C54),
    contour = Color(0xFF3E4640),
    road = Color(0xFF5A655F),
    roadCasing = Color(0xFF2D332F),
    trail = Color(0xFFD1A477)
)

private val OpenFreeMapSwatch = SwatchPalette(
    ground = Color(0xFFF6F5F0),
    forest = Color(0xFFCDE6C0),
    water = Color(0xFFAAD3DF),
    contour = Color(0xFFE3DFD4),
    road = Color(0xFFFFFFFF),
    roadCasing = Color(0xFFD9C79B),
    trail = Color(0xFF4F7A5B)
)

private val OsmRasterSwatch = SwatchPalette(
    ground = Color(0xFFF2EFE9),
    forest = Color(0xFFADD19E),
    water = Color(0xFFAAD3DF),
    contour = Color(0xFFD8D0C2),
    road = Color(0xFFFCD6A4),
    roadCasing = Color(0xFFE09F67),
    trail = Color(0xFFD65A38)
)

private val StadiaOutdoorsSwatch = SwatchPalette(
    ground = Color(0xFFF3F4EB),
    forest = Color(0xFFC7DFB5),
    water = Color(0xFF9EC7E0),
    contour = Color(0xFFC8BCA4),
    road = Color(0xFFFFFFFF),
    roadCasing = Color(0xFFC7BDA9),
    trail = Color(0xFF9A5B32)
)

private val StamenTerrainSwatch = SwatchPalette(
    ground = Color(0xFFEAE8DC),
    forest = Color(0xFFAEC79E),
    water = Color(0xFF9AC0D9),
    contour = Color(0xFFB5B09B),
    road = Color(0xFFF7F1DC),
    roadCasing = Color(0xFFC9BE9E),
    trail = Color(0xFF6B7857)
)

private val ThunderforestOutdoorsSwatch = SwatchPalette(
    ground = Color(0xFFF8F6EE),
    forest = Color(0xFFCFE4B8),
    water = Color(0xFFB3DCF2),
    contour = Color(0xFFD4A87E),
    road = Color(0xFFFDE6A8),
    roadCasing = Color(0xFFD5A96A),
    trail = Color(0xFFC94435)
)

private const val CONTOUR_STROKE_FRACTION = 0.016f
private const val ROAD_CASING_FRACTION = 0.068f
private const val ROAD_FILL_FRACTION = 0.042f
private const val TRAIL_STROKE_FRACTION = 0.028f
private const val DASH_ON_FRACTION = 0.06f
private const val DASH_OFF_FRACTION = 0.045f

private object SwatchGeometry {
    const val WEST_FOREST_END_X = 0.62f
    const val WEST_FOREST_C1_X = 0.56f
    const val WEST_FOREST_C1_Y = 0.30f
    const val WEST_FOREST_C2_X = 0.36f
    const val WEST_FOREST_C2_Y = 0.44f
    const val WEST_FOREST_END_Y = 0.62f
    const val WEST_FOREST_MID_X = 0.24f
    const val WEST_FOREST_EDGE_Y = 0.58f

    const val EAST_FOREST_START_X = 0.68f
    const val EAST_FOREST_START_Y = 0.55f
    const val EAST_FOREST_C1_X = 0.80f
    const val EAST_FOREST_C1_Y = 0.48f
    const val EAST_FOREST_C2_X = 0.94f
    const val EAST_FOREST_C2_Y = 0.52f
    const val EAST_FOREST_EDGE_Y = 0.44f
    const val EAST_FOREST_BOTTOM_X = 0.52f

    const val LAKE_START_X = 0.64f
    const val LAKE_EDGE_Y = 0.34f
    const val LAKE_C1_X = 0.84f
    const val LAKE_C1_Y = 0.40f
    const val LAKE_C2_X = 0.68f
    const val LAKE_C2_Y = 0.26f

    const val CONTOUR_UPPER_Y = 0.26f
    const val CONTOUR_UPPER_CTRL_X = 0.28f
    const val CONTOUR_UPPER_CTRL_Y = 0.14f
    const val CONTOUR_UPPER_END_X = 0.54f
    const val CONTOUR_UPPER_END_Y = 0.30f

    const val CONTOUR_LOWER_Y = 0.42f
    const val CONTOUR_LOWER_CTRL_X = 0.32f
    const val CONTOUR_LOWER_CTRL_Y = 0.30f
    const val CONTOUR_LOWER_END_X = 0.60f
    const val CONTOUR_LOWER_END_Y = 0.48f

    const val ROAD_START_Y = 0.78f
    const val ROAD_C1_X = 0.34f
    const val ROAD_C1_Y = 0.70f
    const val ROAD_C2_X = 0.58f
    const val ROAD_C2_Y = 0.54f
    const val ROAD_END_Y = 0.62f

    const val TRAIL_START_X = 0.14f
    const val TRAIL_C1_X = 0.28f
    const val TRAIL_C1_Y = 0.64f
    const val TRAIL_C2_X = 0.46f
    const val TRAIL_C2_Y = 0.42f
    const val TRAIL_PEAK_X = 0.60f
    const val TRAIL_PEAK_Y = 0.16f
}

/**
 * Stylized deterministic cartographic thumbnail representing a [MapStyleId] when offline,
 * for vector styles, or while a live raster preview is loading.
 */
@Composable
internal fun MapStyleSwatch(styleId: String, modifier: Modifier = Modifier) {
    val darkTheme = isSystemInDarkTheme()
    val palette = swatchPaletteFor(styleId = styleId, darkTheme = darkTheme)
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = palette.ground)
        drawForestPatch(palette.forest)
        drawWaterBody(palette.water)
        drawContourLines(palette.contour)
        drawRoad(casing = palette.roadCasing, fill = palette.road)
        drawTrail(palette.trail)
    }
}

private fun swatchPaletteFor(styleId: String, darkTheme: Boolean): SwatchPalette = when (styleId) {
    MapStyleId.OPENHIKES -> if (darkTheme) OpenHikesDarkSwatch else OpenHikesLightSwatch
    MapStyleId.OPENFREEMAP -> OpenFreeMapSwatch
    MapStyleId.OSM_RASTER -> OsmRasterSwatch
    MapStyleId.STADIA_OUTDOORS -> StadiaOutdoorsSwatch
    MapStyleId.STAMEN_TERRAIN -> StamenTerrainSwatch
    MapStyleId.THUNDERFOREST_OUTDOORS -> ThunderforestOutdoorsSwatch
    else -> if (darkTheme) OpenHikesDarkSwatch else OpenHikesLightSwatch
}

private fun DrawScope.drawForestPatch(color: Color) {
    val w = size.width
    val h = size.height
    val forestPath = Path().apply {
        moveTo(0f, 0f)
        lineTo(w * SwatchGeometry.WEST_FOREST_END_X, 0f)
        cubicTo(
            w * SwatchGeometry.WEST_FOREST_C1_X,
            h * SwatchGeometry.WEST_FOREST_C1_Y,
            w * SwatchGeometry.WEST_FOREST_C2_X,
            h * SwatchGeometry.WEST_FOREST_C2_Y,
            w * SwatchGeometry.WEST_FOREST_MID_X,
            h * SwatchGeometry.WEST_FOREST_END_Y
        )
        lineTo(0f, h * SwatchGeometry.WEST_FOREST_EDGE_Y)
        close()
    }
    drawPath(path = forestPath, color = color)

    val eastWood = Path().apply {
        moveTo(w * SwatchGeometry.EAST_FOREST_START_X, h * SwatchGeometry.EAST_FOREST_START_Y)
        cubicTo(
            w * SwatchGeometry.EAST_FOREST_C1_X,
            h * SwatchGeometry.EAST_FOREST_C1_Y,
            w * SwatchGeometry.EAST_FOREST_C2_X,
            h * SwatchGeometry.EAST_FOREST_C2_Y,
            w,
            h * SwatchGeometry.EAST_FOREST_EDGE_Y
        )
        lineTo(w, h)
        lineTo(w * SwatchGeometry.EAST_FOREST_BOTTOM_X, h)
        close()
    }
    drawPath(path = eastWood, color = color)
}

private fun DrawScope.drawWaterBody(color: Color) {
    val w = size.width
    val h = size.height
    val lake = Path().apply {
        moveTo(w * SwatchGeometry.LAKE_START_X, 0f)
        lineTo(w, 0f)
        lineTo(w, h * SwatchGeometry.LAKE_EDGE_Y)
        cubicTo(
            w * SwatchGeometry.LAKE_C1_X,
            h * SwatchGeometry.LAKE_C1_Y,
            w * SwatchGeometry.LAKE_C2_X,
            h * SwatchGeometry.LAKE_C2_Y,
            w * SwatchGeometry.LAKE_START_X,
            0f
        )
        close()
    }
    drawPath(path = lake, color = color)
}

private fun DrawScope.drawContourLines(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * CONTOUR_STROKE_FRACTION, cap = StrokeCap.Round)
    val upperContour = Path().apply {
        moveTo(0f, h * SwatchGeometry.CONTOUR_UPPER_Y)
        quadraticTo(
            w * SwatchGeometry.CONTOUR_UPPER_CTRL_X,
            h * SwatchGeometry.CONTOUR_UPPER_CTRL_Y,
            w * SwatchGeometry.CONTOUR_UPPER_END_X,
            h * SwatchGeometry.CONTOUR_UPPER_END_Y
        )
    }
    val lowerContour = Path().apply {
        moveTo(0f, h * SwatchGeometry.CONTOUR_LOWER_Y)
        quadraticTo(
            w * SwatchGeometry.CONTOUR_LOWER_CTRL_X,
            h * SwatchGeometry.CONTOUR_LOWER_CTRL_Y,
            w * SwatchGeometry.CONTOUR_LOWER_END_X,
            h * SwatchGeometry.CONTOUR_LOWER_END_Y
        )
    }
    drawPath(path = upperContour, color = color, style = stroke)
    drawPath(path = lowerContour, color = color, style = stroke)
}

private fun DrawScope.drawRoad(casing: Color, fill: Color) {
    val w = size.width
    val h = size.height
    val roadPath = Path().apply {
        moveTo(0f, h * SwatchGeometry.ROAD_START_Y)
        cubicTo(
            w * SwatchGeometry.ROAD_C1_X,
            h * SwatchGeometry.ROAD_C1_Y,
            w * SwatchGeometry.ROAD_C2_X,
            h * SwatchGeometry.ROAD_C2_Y,
            w,
            h * SwatchGeometry.ROAD_END_Y
        )
    }
    drawPath(
        path = roadPath,
        color = casing,
        style = Stroke(width = w * ROAD_CASING_FRACTION, cap = StrokeCap.Round)
    )
    drawPath(
        path = roadPath,
        color = fill,
        style = Stroke(width = w * ROAD_FILL_FRACTION, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawTrail(color: Color) {
    val w = size.width
    val h = size.height
    val trailPath = Path().apply {
        moveTo(w * SwatchGeometry.TRAIL_START_X, h)
        cubicTo(
            w * SwatchGeometry.TRAIL_C1_X,
            h * SwatchGeometry.TRAIL_C1_Y,
            w * SwatchGeometry.TRAIL_C2_X,
            h * SwatchGeometry.TRAIL_C2_Y,
            w * SwatchGeometry.TRAIL_PEAK_X,
            h * SwatchGeometry.TRAIL_PEAK_Y
        )
    }
    val dash = PathEffect.dashPathEffect(
        floatArrayOf(w * DASH_ON_FRACTION, w * DASH_OFF_FRACTION),
        0f
    )
    drawPath(
        path = trailPath,
        color = color,
        style = Stroke(
            width = w * TRAIL_STROKE_FRACTION,
            cap = StrokeCap.Round,
            pathEffect = dash
        )
    )
    drawCircle(
        color = color,
        radius = w * TRAIL_STROKE_FRACTION,
        center = Offset(w * SwatchGeometry.TRAIL_PEAK_X, h * SwatchGeometry.TRAIL_PEAK_Y)
    )
}
