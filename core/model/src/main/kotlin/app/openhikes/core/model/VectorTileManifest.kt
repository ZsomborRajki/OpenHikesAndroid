package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Tile server manifest (`https://tiles.openhikes.app/manifest.json`) declaring the current
 * Protomaps basemap and OpenHikes extras PMTiles archive names served by the OpenHikes tile server.
 */
@Serializable
data class VectorTileArchiveManifest(
    val version: Int,
    val revision: Int,
    val basemaps: List<BasemapArchive> = emptyList(),
    val extras: List<ExtrasArchive> = emptyList(),
    val routing: List<SquareArchive> = emptyList()
) {
    /**
     * Resolves the newest compatible basemap and optional extras source on [serverBaseUrl],
     * or `null` if [version] is unsupported or no compatible basemap archive is listed.
     */
    fun sourceOn(serverBaseUrl: String): VectorTileSource? {
        val basemap = if (version == CURRENT_VERSION) basemaps.firstOrNull { it.isDrawn } else null
        return basemap?.let { chosen ->
            val normalizedBase = serverBaseUrl.trimEnd('/')
            val drawnExtras = extras.firstOrNull { it.isDrawn }
            val activeRouting = routing.firstOrNull { it.isReadableRouting }
            VectorTileSource(
                basemapArchiveName = chosen.name,
                basemapUrl = "$normalizedBase/${chosen.name}",
                maximumZoom = chosen.maximumZoom,
                extras = drawnExtras?.let { item ->
                    ExtrasTileSource(
                        archiveName = item.name,
                        archiveUrl = "$normalizedBase/${item.name}",
                        minimumZoom = item.minimumZoom
                    )
                },
                routing = activeRouting?.let { item ->
                    RoutingArchiveSource(
                        archiveName = item.name,
                        archiveUrl = "$normalizedBase/${item.name}",
                        zoom = item.zoom
                    )
                }
            )
        }
    }

    @Serializable
    data class BasemapArchive(val name: String, val schema: Int, val maximumZoom: Int) {
        val isDrawn: Boolean
            get() = schema == OfflineCountryManifest.READABLE_SCHEMA &&
                isArchiveName(name) &&
                maximumZoom in VALID_ZOOM_RANGE
    }

    @Serializable
    data class ExtrasArchive(val name: String, val generation: Int, val minimumZoom: Int) {
        val isDrawn: Boolean
            get() = generation in 1..READABLE_EXTRAS_GENERATION &&
                isArchiveName(name) &&
                minimumZoom in VALID_ZOOM_RANGE
    }

    @Serializable
    data class SquareArchive(val name: String, val generation: Int, val zoom: Int) {
        val isReadableRouting: Boolean
            get() = generation == TrailGraphSquareGrid.GENERATION &&
                zoom == TrailGraphSquareGrid.REGION_ZOOM &&
                isArchiveName(name) &&
                name.startsWith(ROUTING_ARCHIVE_PREFIX)
    }

    companion object {
        const val DEFAULT_SERVER_BASE_URL = "https://tiles.openhikes.app"
        const val CURRENT_VERSION = 1
        const val READABLE_EXTRAS_GENERATION = OfflineCountryManifest.READABLE_EXTRAS_GENERATION
        private const val ROUTING_ARCHIVE_PREFIX = "routing-"
        private const val MAX_ARCHIVE_NAME_LENGTH = 64
        private val VALID_ZOOM_RANGE = 0..30

        /**
         * Validates that [name] is a safe archive object name served by `tiles.openhikes.app`:
         * 1..64 ASCII lowercase letters, digits, and single non-edge hyphens.
         */
        fun isArchiveName(name: String): Boolean {
            if (name.length !in 1..MAX_ARCHIVE_NAME_LENGTH) return false
            val parts = name.split('-')
            return parts.all { part ->
                part.isNotEmpty() && part.all { ch -> ch in 'a'..'z' || ch in '0'..'9' }
            }
        }
    }
}

/**
 * Resolved vector tile archive endpoints for OpenHikes basemap and optional extras overlays.
 */
@Serializable
data class VectorTileSource(
    val basemapArchiveName: String,
    val basemapUrl: String,
    val maximumZoom: Int,
    val extras: ExtrasTileSource? = null,
    val routing: RoutingArchiveSource? = null
) {
    /**
     * Returns the source tile coordinate clamped to [maximumZoom] so deeper map zooms overzoom cleanly.
     */
    fun dataTileFor(address: TileAddress): TileAddress = address.overzoomedTo(maximumZoom)

    fun basemapTileUrl(address: TileAddress): String {
        val dataTile = dataTileFor(address)
        return "$basemapUrl/${dataTile.z}/${dataTile.x}/${dataTile.y}.mvt"
    }

    fun extrasTileUrl(address: TileAddress): String? {
        val activeExtras = extras
        val dataTile = dataTileFor(address)
        return if (activeExtras != null && dataTile.z >= activeExtras.minimumZoom) {
            "${activeExtras.archiveUrl}/${dataTile.z}/${dataTile.x}/${dataTile.y}.mvt"
        } else {
            null
        }
    }
}

/**
 * Resolved extras vector tile archive endpoint paired with a [VectorTileSource].
 */
@Serializable
data class ExtrasTileSource(val archiveName: String, val archiveUrl: String, val minimumZoom: Int)

/**
 * Resolved pre-built OSM routing graph archive endpoint paired with a [VectorTileSource].
 */
@Serializable
data class RoutingArchiveSource(val archiveName: String, val archiveUrl: String, val zoom: Int) {
    fun squareTileUrl(region: TrailGraphRegion): String = "$archiveUrl/${region.zoom}/${region.x}/${region.y}.bin"
}
