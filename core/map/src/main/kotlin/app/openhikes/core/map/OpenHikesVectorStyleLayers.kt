package app.openhikes.core.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.rememberCustomVectorTileSource
import org.maplibre.compose.util.MaplibreComposable

private const val OPENHIKES_MIN_ZOOM = 0
private const val OPENHIKES_ONLINE_DEFAULT_MAX_ZOOM = 15
private const val OPENHIKES_OFFLINE_DEFAULT_MAX_ZOOM = 14
private const val OPENHIKES_EXTRAS_MIN_ZOOM = 4
private const val BUILDINGS_MIN_ZOOM = 13f
private const val PATHS_MIN_ZOOM = 11f
private const val NATURAL_LINES_MIN_ZOOM = 12f
private const val PRIMARY_POIS_MIN_ZOOM = 14f
private const val MICRO_POIS_MIN_ZOOM = 15f
private const val PARKS_OPACITY = 0.28f
private const val LANDCOVER_OPACITY = 0.65f
private const val LANDUSE_OPACITY = 0.45f
private const val BUILDINGS_OPACITY = 0.72f
private const val ROAD_WIDTH_DP = 1.4f
private const val PATH_WIDTH_DP = 1.2f
private const val BOUNDARY_WIDTH_DP = 1.1f
private const val NATURAL_LINE_WIDTH_DP = 1.0f
private const val POI_ICON_SIZE_DP = 16
private const val MICRO_POI_ICON_SIZE_DP = 14
private const val POI_HALO_WIDTH_DP = 1.5f
private const val BOUNDARY_DASH = 3.0
private const val BOUNDARY_GAP = 2.0
private const val PATH_DASH = 2.2
private const val PATH_GAP = 1.6

/**
 * Identity key for OpenHikes custom vector tile sources so MapLibre cleanly reloads tiles on
 * offline archive changes, online connectivity recovery, manifest archive moves, or max-zoom shifts.
 */
@Immutable
internal data class OpenHikesVectorSourceSpec(
    val archiveRevision: Long,
    val onlineRecoveryGeneration: Long,
    val activeBasemapArchive: String?,
    val activeExtrasArchive: String?,
    val basemapMaxZoom: Int,
    val extrasMaxZoom: Int
)

/**
 * Computes canonical `(basemapMaxZoom, extrasMaxZoom)` for OpenHikes custom vector sources so
 * MapLibre overzooms z14 offline basemaps natively when zoom-15 Extra Detail archives are absent.
 */
internal fun resolveOpenHikesVectorZooms(
    allowRemoteRequests: Boolean,
    onlineMaxZoom: Int?,
    offlineBasemapMaxZoom: Int,
    offlineExtrasMaxZoom: Int
): Pair<Int, Int> = if (allowRemoteRequests) {
    val maxOnline = (onlineMaxZoom ?: OPENHIKES_ONLINE_DEFAULT_MAX_ZOOM)
        .coerceIn(OPENHIKES_EXTRAS_MIN_ZOOM, OPENHIKES_ONLINE_DEFAULT_MAX_ZOOM)
    maxOnline to maxOnline
} else {
    val basemapMax = offlineBasemapMaxZoom.takeIf { it > 0 } ?: OPENHIKES_OFFLINE_DEFAULT_MAX_ZOOM
    val extrasMax = offlineExtrasMaxZoom.takeIf { it > 0 } ?: basemapMax
    basemapMax to extrasMax.coerceAtLeast(OPENHIKES_EXTRAS_MIN_ZOOM)
}

/**
 * Protomaps schema 4 / OpenHikes `pois` feature `kind` groupings rendered with offline SDF symbols.
 */
internal object OpenHikesPoiStyle {
    val WATER_KINDS: List<String> = listOf("drinking_water", "spring", "water_well", "fountain")
    val TOILET_KINDS: List<String> = listOf("toilets")
    val BENCH_KINDS: List<String> = listOf("bench", "picnic_table")
    val SHELTER_KINDS: List<String> = listOf("shelter", "alpine_hut", "wilderness_hut")
    val VIEWPOINT_KINDS: List<String> = listOf("viewpoint", "peak", "saddle")
    val PARKING_KINDS: List<String> = listOf("parking")

    val PRIMARY_KINDS: List<String> = WATER_KINDS + TOILET_KINDS + SHELTER_KINDS + VIEWPOINT_KINDS + PARKING_KINDS
    val MICRO_KINDS: List<String> = BENCH_KINDS
}

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
    val terrainLines: Color,
    val poiHalo: Color,
    val poiWater: Color,
    val poiToilets: Color,
    val poiBench: Color,
    val poiShelter: Color,
    val poiViewpoint: Color,
    val poiParking: Color
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
    terrainLines = Color(0xFF8B8175),
    poiHalo = Color(0xF0FFFFFF),
    poiWater = Color(0xFF0B6DA8),
    poiToilets = Color(0xFF5D4B7A),
    poiBench = Color(0xFF6B4F35),
    poiShelter = Color(0xFF8C4A27),
    poiViewpoint = Color(0xFF2E6B3E),
    poiParking = Color(0xFF2B5B84)
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
    terrainLines = Color(0xFF8F877D),
    poiHalo = Color(0xE6141815),
    poiWater = Color(0xFF66C2FF),
    poiToilets = Color(0xFFC7B2F0),
    poiBench = Color(0xFFD4B28C),
    poiShelter = Color(0xFFF0A678),
    poiViewpoint = Color(0xFF8CD99B),
    poiParking = Color(0xFF8ABAF0)
)

/**
 * Native MapLibre Compose vector style rendering OpenHikes's Protomaps schema 4 basemap,
 * generation 2 hiking extras, and offline-capable POI symbols (`water`, `toilets`, `benches`,
 * huts/shelters, peaks/viewpoints, and parking) directly from local `.pmtiles` or `tiles.openhikes.app`.
 */
@Composable
@MaplibreComposable
internal fun OpenHikesVectorBaseLayers(
    basemapProvider: VectorTileProvider,
    extrasProvider: VectorTileProvider,
    sourceSpec: OpenHikesVectorSourceSpec
) {
    val palette = if (isSystemInDarkTheme()) DarkVectorPalette else LightVectorPalette

    BackgroundLayer(
        id = "openhikes-bg-water",
        color = const(palette.water)
    )

    key(sourceSpec) {
        val basemapSource = rememberCustomVectorTileSource(
            options = CustomVectorTileSourceOptions {
                minZoom = OPENHIKES_MIN_ZOOM
                maxZoom = sourceSpec.basemapMaxZoom
            },
            provider = basemapProvider
        )
        val extrasSource = rememberCustomVectorTileSource(
            options = CustomVectorTileSourceOptions {
                minZoom = OPENHIKES_EXTRAS_MIN_ZOOM
                maxZoom = sourceSpec.extrasMaxZoom
            },
            provider = extrasProvider
        )
        OpenHikesBasemapLayers(source = basemapSource, palette = palette)
        OpenHikesExtrasLayers(source = extrasSource, palette = palette)
        OpenHikesPoiLayers(source = basemapSource, idPrefix = "openhikes-basemap-pois", palette = palette)
        OpenHikesPoiLayers(source = extrasSource, idPrefix = "openhikes-extras-pois", palette = palette)
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

@Composable
@MaplibreComposable
private fun OpenHikesPoiLayers(source: VectorSource, idPrefix: String, palette: OpenHikesVectorPalette) {
    val primarySize = DpSize(POI_ICON_SIZE_DP.dp, POI_ICON_SIZE_DP.dp)
    val microSize = DpSize(MICRO_POI_ICON_SIZE_DP.dp, MICRO_POI_ICON_SIZE_DP.dp)
    val waterIcon = image(painterResource(OpenHikesIcons.PoiWater), size = primarySize, drawAsSdf = true)
    val toiletsIcon = image(painterResource(OpenHikesIcons.PoiToilets), size = primarySize, drawAsSdf = true)
    val shelterIcon = image(painterResource(OpenHikesIcons.PoiShelter), size = primarySize, drawAsSdf = true)
    val viewpointIcon = image(painterResource(OpenHikesIcons.PoiViewpoint), size = primarySize, drawAsSdf = true)
    val parkingIcon = image(painterResource(OpenHikesIcons.PoiParking), size = primarySize, drawAsSdf = true)
    val benchIcon = image(painterResource(OpenHikesIcons.PoiBench), size = microSize, drawAsSdf = true)

    SymbolLayer(
        id = "$idPrefix-primary",
        source = source,
        sourceLayer = "pois",
        minZoom = PRIMARY_POIS_MIN_ZOOM,
        filter = switch(
            input = feature["kind"],
            case(label = OpenHikesPoiStyle.PRIMARY_KINDS, output = const(true)),
            fallback = const(false)
        ),
        iconImage = switch(
            input = feature["kind"],
            case(label = OpenHikesPoiStyle.WATER_KINDS, output = waterIcon),
            case(label = OpenHikesPoiStyle.TOILET_KINDS, output = toiletsIcon),
            case(label = OpenHikesPoiStyle.SHELTER_KINDS, output = shelterIcon),
            case(label = OpenHikesPoiStyle.VIEWPOINT_KINDS, output = viewpointIcon),
            case(label = OpenHikesPoiStyle.PARKING_KINDS, output = parkingIcon),
            fallback = waterIcon
        ),
        iconColor = switch(
            input = feature["kind"],
            case(label = OpenHikesPoiStyle.WATER_KINDS, output = const(palette.poiWater)),
            case(label = OpenHikesPoiStyle.TOILET_KINDS, output = const(palette.poiToilets)),
            case(label = OpenHikesPoiStyle.SHELTER_KINDS, output = const(palette.poiShelter)),
            case(label = OpenHikesPoiStyle.VIEWPOINT_KINDS, output = const(palette.poiViewpoint)),
            case(label = OpenHikesPoiStyle.PARKING_KINDS, output = const(palette.poiParking)),
            fallback = const(palette.poiWater)
        ),
        iconHaloColor = const(palette.poiHalo),
        iconHaloWidth = const(POI_HALO_WIDTH_DP.dp)
    )
    SymbolLayer(
        id = "$idPrefix-micro",
        source = source,
        sourceLayer = "pois",
        minZoom = MICRO_POIS_MIN_ZOOM,
        filter = switch(
            input = feature["kind"],
            case(label = OpenHikesPoiStyle.MICRO_KINDS, output = const(true)),
            fallback = const(false)
        ),
        iconImage = benchIcon,
        iconColor = const(palette.poiBench),
        iconHaloColor = const(palette.poiHalo),
        iconHaloWidth = const(POI_HALO_WIDTH_DP.dp)
    )
}
