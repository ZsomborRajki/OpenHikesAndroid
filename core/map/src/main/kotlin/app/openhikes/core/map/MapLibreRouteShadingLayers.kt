package app.openhikes.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.RouteShade
import app.openhikes.core.model.RouteShadeBlend
import app.openhikes.core.model.RouteShadeStretch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

internal const val SHADE_COLOR_PROPERTY = "color"

/**
 * Draws smoothly blended [RouteShadeStretch] segments directly over a selected route's base line,
 * preserving exact route draw order so out-and-back return legs stay painted above outward legs.
 */
@Composable
@MaplibreComposable
internal fun ShadedRouteLineLayer(
    route: MapRouteOverlay,
    plan: WalkedRouteRenderPlan,
    opacity: Float,
    idPrefix: String
) {
    val shadedData = remember(route.shadedStretches) {
        buildBlendedShadeFeatureCollection(route.shadedStretches)
    }
    val shadedSource = rememberGeoJsonSource(data = shadedData)
    val shadeOpacity = (opacity * plan.lineColor.alpha).coerceIn(0f, 1f)
    LineLayer(
        id = "${idPrefix}route-shaded-${route.hikeId.value}",
        source = shadedSource,
        color = feature[SHADE_COLOR_PROPERTY].convertToColor(const(plan.lineColor)),
        width = const(plan.lineWidthDp.dp),
        opacity = const(shadeOpacity),
        cap = const(plan.lineCap),
        join = const(LineJoin.Round),
        dasharray = plan.lineDashArray?.let { const(it) }
    )
}

/**
 * Draws tinted arrowheads per [RouteShade] when a route uses the arrowheads-only line pattern.
 */
@Composable
@MaplibreComposable
internal fun ShadedArrowheadLayers(
    route: MapRouteOverlay,
    plan: WalkedRouteRenderPlan,
    opacity: Float,
    idPrefix: String
) {
    val grouped = remember(route.shadedStretches) {
        route.shadedStretches.groupBy(
            keySelector = { it.shade },
            valueTransform = { it.coordinates }
        )
    }
    for (shade in RouteShade.scale) {
        val segments = grouped[shade].orEmpty()
        if (segments.isEmpty()) continue
        key("${idPrefix}route-shaded-arrow-${route.hikeId.value}-${shade.step}") {
            val shadeColor = parseHexColorOrDefault(shade.hexColor, plan.lineColor)
            val spec = remember(route.linePattern, shadeColor, plan.borderColor, plan.lineWidthDp) {
                buildChevronRenderSpec(route.linePattern, shadeColor, plan.borderColor, plan.lineWidthDp)
            }
            if (spec != null) {
                val painter = remember(spec) { RouteChevronPainter(spec) }
                val sourceData = remember(segments) { buildMultiLineFeatureCollection(segments) }
                val source = rememberGeoJsonSource(data = sourceData)
                SymbolLayer(
                    id = "${idPrefix}route-shaded-chevrons-${route.hikeId.value}-${shade.step}",
                    source = source,
                    placement = const(SymbolPlacement.Line),
                    spacing = const(spec.spacingDp.dp),
                    iconImage = image(value = painter, size = DpSize(spec.iconWidthDp.dp, spec.iconHeightDp.dp)),
                    iconOpacity = const(opacity),
                    iconRotationAlignment = const(IconRotationAlignment.Map),
                    iconPitchAlignment = const(IconPitchAlignment.Map),
                    iconKeepUpright = const(false),
                    iconAllowOverlap = const(true),
                    iconIgnorePlacement = const(true)
                )
            }
        }
    }
}

/**
 * Converts route-ordered [RouteShadeStretch] runs into a blended GeoJSON [FeatureCollection] with
 * per-feature hex color properties consumed by MapLibre's `to-color` expression.
 */
internal fun buildBlendedShadeFeatureCollection(stretches: List<RouteShadeStretch>): GeoJsonData =
    GeoJsonData.Features(buildBlendedShadeFeatures(stretches))

internal fun buildBlendedShadeFeatures(stretches: List<RouteShadeStretch>): FeatureCollection<LineString, JsonObject> {
    val blended = RouteShadeBlend.blend(stretches)
    val features = blended.mapNotNull { span ->
        if (span.coordinates.size < 2) {
            null
        } else {
            val positions = span.coordinates.map { Position(longitude = it.longitude, latitude = it.latitude) }
            val props = JsonObject(mapOf(SHADE_COLOR_PROPERTY to JsonPrimitive(span.colorHex)))
            Feature<LineString, JsonObject>(LineString(positions), props)
        }
    }
    return FeatureCollection(features)
}
