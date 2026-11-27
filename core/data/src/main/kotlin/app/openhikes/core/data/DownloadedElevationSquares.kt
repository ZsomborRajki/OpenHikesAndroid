package app.openhikes.core.data

import app.openhikes.core.model.ElevationSquare

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
    fun elevationSquares(squares: List<ElevationSquare>): Map<ElevationSquare, ByteArray>
}
