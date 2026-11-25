package app.openhikes.core.model

/**
 * Optional local-build developer API key slot for third-party commercial tile providers.
 */
enum class MapApiKeySlot {
    STADIA,
    THUNDERFOREST
}

/**
 * Optional provider credentials injected from local build configuration (`local.properties` /
 * `secrets.properties` in local debug builds only; distributed APKs keep these empty).
 */
data class MapProviderKeys(val stadiaApiKey: String? = null, val thunderforestApiKey: String? = null) {
    fun keyFor(slot: MapApiKeySlot?): String? = when (slot) {
        null -> null
        MapApiKeySlot.STADIA -> stadiaApiKey?.trim()?.takeIf { it.isNotEmpty() }
        MapApiKeySlot.THUNDERFOREST -> thunderforestApiKey?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isUsable(style: MapStyle): Boolean = style.apiKeySlot == null || keyFor(style.apiKeySlot) != null

    fun hasMissingKeys(styles: List<MapStyle> = MapStyleCatalog.all): Boolean = styles.any { !isUsable(it) }

    companion object {
        val Empty = MapProviderKeys()
    }
}

/**
 * Rendering pipeline category backing a selectable [MapStyle].
 */
enum class MapStyleKind {
    /** OpenHikes PMTiles vector basemap + hiking extras with offline country archive support. */
    OPENHIKES_VECTOR,

    /** Remote MapLibre JSON vector style URL (such as OpenFreeMap Liberty). */
    REMOTE_VECTOR_STYLE,

    /** Standard Slippy XYZ raster tile source rendered beneath hiking overlays and routes. */
    RASTER_TILES
}

/**
 * Selectable base map definition shown in Map Modes and resolved by `:core:map`.
 *
 * Provider terms such as [supportsBulkDownload] and [attribution] live directly on catalog entries
 * so downstream code projects licensing invariants rather than duplicating them.
 */
data class MapStyle(
    val id: String,
    val kind: MapStyleKind,
    val urlTemplate: String?,
    val maximumZoom: Int,
    val attribution: MapAttribution,
    val supportsOfflineCountries: Boolean,
    val supportsBulkDownload: Boolean,
    val apiKeySlot: MapApiKeySlot?
) {
    val requiresApiKey: Boolean
        get() = apiKeySlot != null

    /**
     * Resolves [urlTemplate] with `{key}` substituted from [keys], or `null` if unavailable.
     */
    fun resolvedUrl(keys: MapProviderKeys = MapProviderKeys.Empty): String? {
        val template = urlTemplate
        val slot = apiKeySlot
        return when {
            template == null -> null
            slot == null -> template
            else -> keys.keyFor(slot)?.let { key -> template.replace(KEY_PLACEHOLDER, key) }
        }
    }

    fun isUsable(keys: MapProviderKeys = MapProviderKeys.Empty): Boolean = keys.isUsable(this)

    private companion object {
        const val KEY_PLACEHOLDER = "{key}"
    }
}

/**
 * Canonical catalog of selectable Android map styles in display order.
 *
 * Mirrors iOS OpenHikes's `TileProvider` catalog while adapting native map styles to MapLibre
 * Compose on Android: free OpenHikes vector (offline-capable default), free OpenFreeMap vector,
 * classic OpenStreetMap raster, plus optional local-key commercial outdoor providers.
 */
object MapStyleCatalog {
    private const val OPENHIKES_MAX_ZOOM = 15
    private const val OPENFREEMAP_MAX_ZOOM = 18
    private const val OSM_MAX_ZOOM = 19
    private const val STADIA_MAX_ZOOM = 20
    private const val THUNDERFOREST_MAX_ZOOM = 22

    const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val OSM_RASTER_URL_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val STADIA_OUTDOORS_URL_TEMPLATE =
        "https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}.png?api_key={key}"
    const val STAMEN_TERRAIN_URL_TEMPLATE =
        "https://tiles.stadiamaps.com/tiles/stamen_terrain/{z}/{x}/{y}.png?api_key={key}"
    const val THUNDERFOREST_OUTDOORS_URL_TEMPLATE =
        "https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}"

    val openHikes = MapStyle(
        id = MapStyleId.OPENHIKES,
        kind = MapStyleKind.OPENHIKES_VECTOR,
        urlTemplate = null,
        maximumZoom = OPENHIKES_MAX_ZOOM,
        attribution = MapAttribution.forOpenHikes(showsHikingRoutes = false),
        supportsOfflineCountries = true,
        supportsBulkDownload = false,
        apiKeySlot = null
    )

    val openFreeMap = MapStyle(
        id = MapStyleId.OPENFREEMAP,
        kind = MapStyleKind.REMOTE_VECTOR_STYLE,
        urlTemplate = OPENFREEMAP_LIBERTY_STYLE_URL,
        maximumZoom = OPENFREEMAP_MAX_ZOOM,
        attribution = MapAttribution.forOpenFreeMap(showsHikingRoutes = false),
        supportsOfflineCountries = false,
        supportsBulkDownload = false,
        apiKeySlot = null
    )

    val openStreetMapRaster = MapStyle(
        id = MapStyleId.OSM_RASTER,
        kind = MapStyleKind.RASTER_TILES,
        urlTemplate = OSM_RASTER_URL_TEMPLATE,
        maximumZoom = OSM_MAX_ZOOM,
        attribution = MapAttribution.forOpenStreetMapRaster(showsHikingRoutes = false),
        supportsOfflineCountries = false,
        supportsBulkDownload = false,
        apiKeySlot = null
    )

    val stadiaOutdoors = MapStyle(
        id = MapStyleId.STADIA_OUTDOORS,
        kind = MapStyleKind.RASTER_TILES,
        urlTemplate = STADIA_OUTDOORS_URL_TEMPLATE,
        maximumZoom = STADIA_MAX_ZOOM,
        attribution = MapAttribution.forStadiaOutdoors(showsHikingRoutes = false),
        supportsOfflineCountries = false,
        supportsBulkDownload = true,
        apiKeySlot = MapApiKeySlot.STADIA
    )

    val stamenTerrain = MapStyle(
        id = MapStyleId.STAMEN_TERRAIN,
        kind = MapStyleKind.RASTER_TILES,
        urlTemplate = STAMEN_TERRAIN_URL_TEMPLATE,
        maximumZoom = STADIA_MAX_ZOOM,
        attribution = MapAttribution.forStamenTerrain(showsHikingRoutes = false),
        supportsOfflineCountries = false,
        supportsBulkDownload = true,
        apiKeySlot = MapApiKeySlot.STADIA
    )

    val thunderforestOutdoors = MapStyle(
        id = MapStyleId.THUNDERFOREST_OUTDOORS,
        kind = MapStyleKind.RASTER_TILES,
        urlTemplate = THUNDERFOREST_OUTDOORS_URL_TEMPLATE,
        maximumZoom = THUNDERFOREST_MAX_ZOOM,
        attribution = MapAttribution.forThunderforestOutdoors(showsHikingRoutes = false),
        supportsOfflineCountries = false,
        supportsBulkDownload = false,
        apiKeySlot = MapApiKeySlot.THUNDERFOREST
    )

    val all: List<MapStyle> = listOf(
        openHikes,
        openFreeMap,
        openStreetMapRaster,
        stadiaOutdoors,
        stamenTerrain,
        thunderforestOutdoors
    )

    val default: MapStyle = openHikes

    /**
     * Looks up a style by [id], falling back to [default] when blank or unrecognized.
     */
    fun style(id: String?): MapStyle = all.firstOrNull { it.id == id } ?: default

    /**
     * Resolves the style the map can actually render for a persisted [id] given [keys].
     *
     * Key-gated styles whose key is missing fall back to [default] without overwriting the stored
     * preference so the map never renders blank 401 tiles.
     */
    fun renderable(id: String?, keys: MapProviderKeys = MapProviderKeys.Empty): MapStyle {
        val stored = style(id)
        return if (stored.isUsable(keys)) stored else default
    }

    fun usableStyles(keys: MapProviderKeys = MapProviderKeys.Empty): List<MapStyle> = all.filter { it.isUsable(keys) }
}
