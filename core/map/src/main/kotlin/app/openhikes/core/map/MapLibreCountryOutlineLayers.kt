package app.openhikes.core.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

private const val INSTALLED_FILL_ALPHA = 0.22f
private const val SELECTED_FILL_ALPHA = 0.10f
private const val BASE_STROKE_WIDTH_DP = 1.5f
private const val SELECTED_STROKE_WIDTH_DP = 3.0f
private const val UNINSTALLED_STROKE_OPACITY = 0.55f
private const val MIN_POLYGON_VERTICES = 3

/**
 * Renders offline country polygon outlines above base tiles/hiking routes and below hike routes.
 */
@Composable
@MaplibreComposable
internal fun CountryOutlineLayers(outlines: List<MapCountryOutline>) {
    if (outlines.isEmpty()) return

    val accent = MaterialTheme.colorScheme.primary
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant

    val unselectedUninstalled = remember(outlines) {
        buildOutlineFeatures(outlines.filter { !it.isInstalled && !it.isSelected })
    }
    val installedUnselected = remember(outlines) {
        buildOutlineFeatures(outlines.filter { it.isInstalled && !it.isSelected })
    }
    val selectedUninstalled = remember(outlines) {
        buildOutlineFeatures(outlines.filter { !it.isInstalled && it.isSelected })
    }
    val selectedInstalled = remember(outlines) {
        buildOutlineFeatures(outlines.filter { it.isInstalled && it.isSelected })
    }

    OutlineBucketLayer(
        idPrefix = "offline-outline-base",
        data = unselectedUninstalled,
        fillColor = null,
        fillOpacity = 0f,
        strokeColor = subtle,
        strokeWidthDp = BASE_STROKE_WIDTH_DP,
        strokeOpacity = UNINSTALLED_STROKE_OPACITY
    )
    OutlineBucketLayer(
        idPrefix = "offline-outline-installed",
        data = installedUnselected,
        fillColor = accent,
        fillOpacity = INSTALLED_FILL_ALPHA,
        strokeColor = accent,
        strokeWidthDp = BASE_STROKE_WIDTH_DP,
        strokeOpacity = 1f
    )
    OutlineBucketLayer(
        idPrefix = "offline-outline-selected",
        data = selectedUninstalled,
        fillColor = accent,
        fillOpacity = SELECTED_FILL_ALPHA,
        strokeColor = accent,
        strokeWidthDp = SELECTED_STROKE_WIDTH_DP,
        strokeOpacity = 1f
    )
    OutlineBucketLayer(
        idPrefix = "offline-outline-selected-installed",
        data = selectedInstalled,
        fillColor = accent,
        fillOpacity = INSTALLED_FILL_ALPHA,
        strokeColor = accent,
        strokeWidthDp = SELECTED_STROKE_WIDTH_DP,
        strokeOpacity = 1f
    )
}

@Composable
@MaplibreComposable
private fun OutlineBucketLayer(
    idPrefix: String,
    data: GeoJsonData?,
    fillColor: Color?,
    fillOpacity: Float,
    strokeColor: Color,
    strokeWidthDp: Float,
    strokeOpacity: Float
) {
    if (data == null) return
    val source = rememberGeoJsonSource(data = data)
    if (fillColor != null && fillOpacity > 0f) {
        FillLayer(
            id = "$idPrefix-fill",
            source = source,
            color = const(fillColor),
            opacity = const(fillOpacity)
        )
    }
    LineLayer(
        id = "$idPrefix-stroke",
        source = source,
        color = const(strokeColor),
        width = const(strokeWidthDp.dp),
        opacity = const(strokeOpacity),
        join = const(LineJoin.Round)
    )
}

private fun buildOutlineFeatures(outlines: List<MapCountryOutline>): GeoJsonData? {
    val features = ArrayList<Feature<Polygon, JsonObject?>>()
    for (outline in outlines) {
        for (ring in outline.polygons) {
            if (ring.size < MIN_POLYGON_VERTICES) continue
            val positions = ring.map { Position(longitude = it.longitude, latitude = it.latitude) }.toMutableList()
            if (positions.first() != positions.last()) {
                positions.add(positions.first())
            }
            features.add(Feature(Polygon(listOf(positions)), null))
        }
    }
    if (features.isEmpty()) return null
    return GeoJsonData.Features(FeatureCollection(features))
}
