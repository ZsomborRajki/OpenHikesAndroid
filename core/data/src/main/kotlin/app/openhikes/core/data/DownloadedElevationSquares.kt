package app.openhikes.core.data

import app.openhikes.core.model.ElevationSquare
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local elevation squares bundled inside downloaded offline countries, read ahead of the disk
 * square cache and remote tile server so routes inside downloaded areas receive heights offline.
 */
fun interface DownloadedElevationSquares {
    /**
     * Returns raw `OHEL` bytes for each square in [squares] covered by any installed offline
     * country elevation archive (newest build wins when areas overlap). Missing keys mean no
     * downloaded country covers that square.
     */
    suspend fun elevationSquares(squares: List<ElevationSquare>): Map<ElevationSquare, ByteArray>
}

/**
 * Production [DownloadedElevationSquares] ensuring installed offline country archives are prepared
 * on disk before reading so cold-start elevation lookups never race ahead of archive startup.
 */
@Singleton
internal class OfflineCountryElevationSquares @Inject constructor(
    private val offlineCountryRepository: OfflineCountryRepository,
    private val archives: OfflineCountryArchives
) : DownloadedElevationSquares {
    override suspend fun elevationSquares(squares: List<ElevationSquare>): Map<ElevationSquare, ByteArray> {
        offlineCountryRepository.ensurePrepared()
        return archives.elevationSquares(squares)
    }
}
