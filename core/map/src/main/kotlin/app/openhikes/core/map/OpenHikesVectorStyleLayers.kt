package app.openhikes.core.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.rememberCustomVectorTileSource
import org.maplibre.compose.util.MaplibreComposable

private const val OPENHIKES_MIN_ZOOM = 0
private const val OPENHIKES_MAX_ZOOM = 15
private const val OPENHIKES_EXTRAS_MIN_ZOOM = 4
private const val BUILDINGS_MIN_ZOOM = 13f
private const val PATHS_MIN_ZOOM = 11f
private const val NATURAL_LINES_MIN_ZOOM = 12f
private const val PARKS_OPACITY = 0.28f
private const val LANDCOVER_OPACITY = 0.65f
private const val LANDUSE_OPACITY = 0.45f
private const val BUILDINGS_OPACITY = 0.72f
private const val ROAD_WIDTH_DP = 1.4f
private const val PATH_WIDTH_DP = 1.2f
private const val BOUNDARY_WIDTH_DP = 1.1f
private const val NATURAL_LINE_WIDTH_DP = 1.0f
private const val BOUNDARY_DASH = 3.0
private const val BOUNDARY_GAP = 2.0
private const val PATH_DASH = 2.2
private const val PATH_GAP = 1.6

private data class OpenHikesVectorPalette(
    val water: Color,
    val earth: Color,
    val landcover: Color,
    val landuse: Color,
    val parks: Color,
    val buildings: Color,
    val roads: Color,
    val boundaries: Color,
    val paths: Color,
    val terrainLines: Color
)

private val LightVectorPalette = OpenHikesVectorPalette(
    water = Color(0xFFB7D7EB),
    earth = Color(0xFFF3EFE6),
    landcover = Color(0xFFD5E6C9),
    landuse = Color(0xFFE7E2D3),
    parks = Color(0xFF9AC58A),
    buildings = Color(0xFFD8D0C5),
    roads = Color(0xFFFFFFFF),
    boundaries = Color(0xFF8C7B96),
    paths = Color(0xFF7D5A3C),
    terrainLines = Color(0xFF8B8175)
)

private val DarkVectorPalette = OpenHikesVectorPalette(
    water = Color(0xFF1A3347),
    earth = Color(0xFF1E221F),
    landcover = Color(0xFF243326),
    landuse = Color(0xFF282D29),
    parks = Color(0xFF2F4A33),
    buildings = Color(0xFF343936),
    roads = Color(0xFF4F5753),
    boundaries = Color(0xFF7C6D85),
    paths = Color(0xFFC29B74),
    terrainLines = Color(0xFF8F877D)
)

/**
 * Native MapLibre Compose vector style rendering OpenHikes's Protomaps schema 4 basemap and
 * generation 2 hiking extras directly from offline country `.pmtiles` archives or `tiles.openhikes.app`.
 */
@Composable
@MaplibreComposable
internal fun OpenHikesVectorBaseLayers(
    basemapProvider: VectorTileProvider,
    extrasProvider: VectorTileProvider,
    archiveRevision: Long
) {
    val palette = if (isSystemInDarkTheme()) DarkVectorPalette else LightVectorPalette

    BackgroundLayer(
        id = "openhikes-bg-water",
        color = const(palette.water)
    )

    key(archiveRevision) {
        val basemapSource = rememberCustomVectorTileSource(
            options = CustomVectorTileSourceOptions {
                minZoom = OPENHIKES_MIN_ZOOM
                maxZoom = OPENHIKES_MAX_ZOOM
            },
            provider = basemapProvider
        )
        val extrasSource = rememberCustomVectorTileSource(
            options = CustomVectorTileSourceOptions {
                minZoom = OPENHIKES_EXTRAS_MIN_ZOOM
                maxZoom = OPENHIKES_MAX_ZOOM
            },
            provider = extrasProvider
        )
        OpenHikesBasemapLayers(source = basemapSource, palette = palette)
        OpenHikesExtrasLayers(source = extrasSource, palette = palette)
    }
}

@Composable
@MaplibreComposable
private fun OpenHikesBasemapLayers(source: VectorSource, palette: OpenHikesVectorPalette) {
    FillLayer(
        id = "openhikes-earth",
        source = source,
        sourceLayer = "earth",
        color = const(palette.earth)
    )
    FillLayer(
        id = "openhikes-landcover",
        source = source,
        sourceLayer = "landcover",
        color = const(palette.landcover),
        opacity = const(LANDCOVER_OPACITY)
    )
    FillLayer(
        id = "openhikes-landuse",
        source = source,
        sourceLayer = "landuse",
        color = const(palette.landuse),
        opacity = const(LANDUSE_OPACITY)
    )
    FillLayer(
        id = "openhikes-water",
        source = source,
        sourceLayer = "water",
        color = const(palette.water)
    )
    FillLayer(
        id = "openhikes-buildings",
        source = source,
        sourceLayer = "buildings",
        minZoom = BUILDINGS_MIN_ZOOM,
        color = const(palette.buildings),
        opacity = const(BUILDINGS_OPACITY)
    )
    LineLayer(
        id = "openhikes-roads",
        source = source,
        sourceLayer = "roads",
        color = const(palette.roads),
        width = const(ROAD_WIDTH_DP.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round)
    )
    LineLayer(
        id = "openhikes-boundaries",
        source = source,
        sourceLayer = "boundaries",
        color = const(palette.boundaries),
        width = const(BOUNDARY_WIDTH_DP.dp),
        dasharray = const(listOf<Number>(BOUNDARY_DASH, BOUNDARY_GAP))
    )
}

@Composable
@MaplibreComposable
private fun OpenHikesExtrasLayers(source: VectorSource, palette: OpenHikesVectorPalette) {
    FillLayer(
        id = "openhikes-extras-parks",
        source = source,
        sourceLayer = "parks",
        color = const(palette.parks),
        opacity = const(PARKS_OPACITY)
    )
    FillLayer(
        id = "openhikes-extras-protected",
        source = source,
        sourceLayer = "protected_areas",
        color = const(palette.parks),
        opacity = const(PARKS_OPACITY)
    )
    LineLayer(
        id = "openhikes-extras-paths",
        source = source,
        sourceLayer = "paths",
        minZoom = PATHS_MIN_ZOOM,
        color = const(palette.paths),
        width = const(PATH_WIDTH_DP.dp),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
        dasharray = const(listOf<Number>(PATH_DASH, PATH_GAP))
    )
    LineLayer(
        id = "openhikes-extras-ridges",
        source = source,
        sourceLayer = "ridges",
        minZoom = NATURAL_LINES_MIN_ZOOM,
        color = const(palette.terrainLines),
        width = const(NATURAL_LINE_WIDTH_DP.dp)
    )
    LineLayer(
        id = "openhikes-extras-cliffs",
        source = source,
        sourceLayer = "cliffs",
        minZoom = NATURAL_LINES_MIN_ZOOM,
        color = const(palette.terrainLines),
        width = const(NATURAL_LINE_WIDTH_DP.dp)
    )
}
