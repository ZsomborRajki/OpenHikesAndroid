package app.openhikes.core.map

import app.openhikes.core.data.OfflineTileArchiveSource
import app.openhikes.core.model.TileAddress
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.network.VectorTileFetchResult
import app.openhikes.core.network.VectorTileNetworkClient
import javax.inject.Inject
import javax.inject.Singleton
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider

private val EMPTY_TILE = ByteArray(0)

/**
 * Resolves OpenHikes Protomaps basemap vector tiles offline-first from installed local country
 * `.pmtiles` archives, falling back to `tiles.openhikes.app` only when interactive network access
 * is permitted.
 */
@Singleton
class OpenHikesBasemapTileProvider @Inject constructor(
    private val offlineArchives: OfflineTileArchiveSource,
    private val networkClient: VectorTileNetworkClient,
    private val tileNetworkPolicy: TileNetworkPolicy
) : VectorTileProvider {
    override suspend fun loadTile(tile: TileCoordinate): ByteArray {
        val address = TileAddress(z = tile.zoomLevel, x = tile.x.toInt(), y = tile.y.toInt())
        val offline = if (address.isValid) offlineArchives.readBasemapTile(address) else null
        return when {
            offline != null -> offline
            !address.isValid || !tileNetworkPolicy.allowsInteractiveRequests.value -> EMPTY_TILE
            else -> (networkClient.fetchBasemapTile(address) as? VectorTileFetchResult.Tile)?.bytes ?: EMPTY_TILE
        }
    }
}

/**
 * Resolves OpenHikes extras vector tiles (trails, paths, parks, cliffs, ridges) offline-first from
 * installed local country `.pmtiles` archives, falling back to `tiles.openhikes.app` when online.
 */
@Singleton
class OpenHikesExtrasTileProvider @Inject constructor(
    private val offlineArchives: OfflineTileArchiveSource,
    private val networkClient: VectorTileNetworkClient,
    private val tileNetworkPolicy: TileNetworkPolicy
) : VectorTileProvider {
    override suspend fun loadTile(tile: TileCoordinate): ByteArray {
        val address = TileAddress(z = tile.zoomLevel, x = tile.x.toInt(), y = tile.y.toInt())
        val offline = if (address.isValid) offlineArchives.readExtrasTile(address) else null
        return when {
            offline != null -> offline
            !address.isValid || !tileNetworkPolicy.allowsInteractiveRequests.value -> EMPTY_TILE
            else -> (networkClient.fetchExtrasTile(address) as? VectorTileFetchResult.Tile)?.bytes ?: EMPTY_TILE
        }
    }
}
