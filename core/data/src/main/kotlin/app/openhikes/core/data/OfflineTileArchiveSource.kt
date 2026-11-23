package app.openhikes.core.data

import app.openhikes.core.model.TileAddress
import kotlinx.coroutines.flow.StateFlow

/**
 * Render-thread-safe local PMTiles reader for installed offline countries.
 *
 * Reads directly from opened `PMTilesArchive` handles without touching Room or the network,
 * so downloaded countries render with zero signal and zero main-thread I/O.
 */
interface OfflineTileArchiveSource {
    /**
     * Monotonically incrementing counter bumped whenever installed country archives are reopened.
     */
    val archiveRevision: StateFlow<Long>

    fun readBasemapTile(address: TileAddress): ByteArray?

    fun readExtrasTile(address: TileAddress): ByteArray?

    fun containsTile(address: TileAddress): Boolean
}
