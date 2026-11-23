package app.openhikes.core.common.pmtiles

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PMTilesCodecTest {
    @Test
    fun headerRoundTrips127BytesAndRejectsBadMagicOrVersion() {
        val original = PMTilesHeader(
            rootDirectoryOffset = 127L,
            rootDirectoryLength = 256L,
            metadataOffset = 383L,
            metadataLength = 32L,
            leafDirectoryOffset = 415L,
            leafDirectoryLength = 128L,
            tileDataOffset = 543L,
            tileDataLength = 4_096L,
            addressedTiles = 10L,
            tileEntries = 8L,
            tileContents = 7L,
            isClustered = true,
            internalCompression = PMTilesCompression.GZIP.code,
            tileCompression = PMTilesCompression.GZIP.code,
            tileType = PMTilesHeader.TILE_TYPE_MVT,
            minimumZoom = 0,
            maximumZoom = 14,
            boundsE7 = listOf(95_300_000, 464_300_000, 170_500_000, 490_000_000),
            centerZoom = 7,
            centerE7 = listOf(132_900_000, 477_100_000)
        )

        val encoded = original.toByteArray()
        assertThat(encoded.size).isEqualTo(PMTilesHeader.BYTE_COUNT)
        assertThat(PMTilesHeader.parse(encoded)).isEqualTo(original)

        val wrongMagic = encoded.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertThrows(PMTilesException.NotPMTiles::class.java) { PMTilesHeader.parse(wrongMagic) }

        val wrongVersion = encoded.copyOf().apply { this[7] = 2.toByte() }
        assertThrows(PMTilesException.UnsupportedVersion::class.java) { PMTilesHeader.parse(wrongVersion) }

        assertThrows(PMTilesException.Truncated::class.java) { PMTilesHeader.parse(ByteArray(16)) }
    }

    @Test
    fun hilbertTileIdMatchesSpecVectors() {
        assertThat(PMTilesTileId.id(0, 0, 0)).isEqualTo(0L)
        assertThat(PMTilesTileId.id(1, 0, 0)).isEqualTo(1L)
        assertThat(PMTilesTileId.id(1, 0, 1)).isEqualTo(2L)
        assertThat(PMTilesTileId.id(1, 1, 1)).isEqualTo(3L)
        assertThat(PMTilesTileId.id(1, 1, 0)).isEqualTo(4L)
        assertThat(PMTilesTileId.id(2, 0, 0)).isEqualTo(5L)
        assertThat(PMTilesTileId.id(15, 17_500, 11_400)).isGreaterThan(0L)
    }

    @Test
    fun directoryRoundTripsRunsAndBinarySearchFindsExactRunAndLeaf() {
        val entries = listOf(
            PMTilesEntry(tileId = 0L, offset = 0L, length = 100L, runLength = 3L),
            PMTilesEntry(tileId = 10L, offset = 100L, length = 50L, runLength = 1L),
            PMTilesEntry(tileId = 20L, offset = 250L, length = 80L, runLength = 0L)
        )
        val decoded = PMTilesDirectory.decode(PMTilesDirectory.encode(entries))
        assertThat(decoded).isEqualTo(entries)

        assertThat(PMTilesDirectory.entryHolding(2L, decoded)).isEqualTo(entries[0])
        assertThat(PMTilesDirectory.entryHolding(3L, decoded)).isNull()
        assertThat(PMTilesDirectory.entryHolding(10L, decoded)).isEqualTo(entries[1])
        assertThat(PMTilesDirectory.entryHolding(25L, decoded)).isEqualTo(entries[2])
    }

    @Test
    fun gzipRoundTripsAndRejectsUnsupportedCompressionOrCorruptInput() {
        val raw = "OpenHikes vector tile payload".encodeToByteArray()
        val gzipped = PMTilesGzip.compress(raw)
        assertThat(PMTilesGzip.unpack(gzipped, PMTilesCompression.GZIP.code)).isEqualTo(raw)
        assertThat(PMTilesGzip.unpack(raw, PMTilesCompression.UNCOMPRESSED.code)).isEqualTo(raw)

        assertThrows(PMTilesException.UnsupportedCompression::class.java) {
            PMTilesGzip.unpack(raw, PMTilesCompression.BROTLI.code)
        }
        assertThrows(PMTilesException.Corrupt::class.java) {
            PMTilesGzip.inflate("not-gzip-at-all-00000000".encodeToByteArray())
        }
    }
}
