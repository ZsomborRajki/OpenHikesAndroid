package app.openhikes.core.map

import app.openhikes.core.data.OfflineTileArchiveSource
import app.openhikes.core.model.TileAddress
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.network.VectorTileFetchResult
import app.openhikes.core.network.VectorTileNetworkClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider

private val EMPTY_TILE = ByteArray(0)

/**
 * Thrown by OpenHikes vector tile providers when a tile cannot currently be fetched due to
 * offline state or a transient upstream failure, preventing MapLibre from caching an empty tile
 * and letting MapLibre fall back to lower-zoom tiles until connectivity or upstream health recovers.
 */
class TileUnavailableException(message: String) : IOException(message)

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
    override suspend fun loadTile(tile: TileCoordinate): ByteArray = resolveVectorTile(
        tile = tile,
        readOffline = offlineArchives::readBasemapTile,
        fetchOnline = networkClient::fetchBasemapTile,
        tileNetworkPolicy = tileNetworkPolicy
    )
}

/**
 * Resolves OpenHikes extras vector tiles (trails, paths, parks, cliffs, ridges, POIs) offline-first
 * from installed local country `.pmtiles` archives, falling back to `tiles.openhikes.app` when online.
 */
@Singleton
class OpenHikesExtrasTileProvider @Inject constructor(
    private val offlineArchives: OfflineTileArchiveSource,
    private val networkClient: VectorTileNetworkClient,
    private val tileNetworkPolicy: TileNetworkPolicy
) : VectorTileProvider {
    override suspend fun loadTile(tile: TileCoordinate): ByteArray = resolveVectorTile(
        tile = tile,
        readOffline = offlineArchives::readExtrasTile,
        fetchOnline = networkClient::fetchExtrasTile,
        tileNetworkPolicy = tileNetworkPolicy
    )
}

private suspend fun resolveVectorTile(
    tile: TileCoordinate,
    readOffline: (TileAddress) -> ByteArray?,
    fetchOnline: suspend (TileAddress) -> VectorTileFetchResult,
    tileNetworkPolicy: TileNetworkPolicy
): ByteArray {
    val address = TileAddress(z = tile.zoomLevel, x = tile.x.toInt(), y = tile.y.toInt())
    val offline = if (address.isValid) readOffline(address) else null
    return when {
        !address.isValid -> EMPTY_TILE
        offline != null -> offline
        !tileNetworkPolicy.allowsInteractiveRequests.value ->
            throw TileUnavailableException("Offline and no local archive contains $address")

        else -> when (val fetched = fetchOnline(address)) {
            is VectorTileFetchResult.Tile -> fetched.bytes
            VectorTileFetchResult.Missing -> EMPTY_TILE
            VectorTileFetchResult.Offline,
            VectorTileFetchResult.Unavailable,
            VectorTileFetchResult.Refused ->
                throw TileUnavailableException("Transient vector tile failure ($fetched) for $address")
        }
    }
}
