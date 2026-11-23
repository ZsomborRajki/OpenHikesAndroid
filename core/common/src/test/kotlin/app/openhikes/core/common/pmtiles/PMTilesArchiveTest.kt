package app.openhikes.core.common.pmtiles

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PMTilesArchiveTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun readsGzippedTilesFromFileChannelAcrossLeafDirectories() {
        val specs = (0 until 12).map { index ->
            PMTilesWriter.TileSpec(
                z = 4,
                x = index,
                y = index,
                payload = "tile-payload-$index".encodeToByteArray()
            )
        }
        val archiveBytes = PMTilesWriter.buildArchive(
            tiles = specs,
            compressInternal = true,
            compressTiles = true,
            forceLeafSize = 3
        )
        val file = File(tempFolder.root, "AT-20261117.pmtiles").apply { writeBytes(archiveBytes) }

        PMTilesArchive.open(file).use { archive ->
            assertThat(archive.header.leafDirectoryLength).isGreaterThan(0L)
            for (index in 0 until 12) {
                assertThat(archive.contains(4, index, index)).isTrue()
                assertThat(archive.tile(4, index, index)).isEqualTo("tile-payload-$index".encodeToByteArray())
            }
            assertThat(archive.contains(4, 15, 15)).isFalse()
            assertThat(archive.tile(4, 15, 15)).isNull()
            assertThat(archive.tile(15, 0, 0)).isNull()
        }
    }

    @Test
    fun supportsRunLengthEntriesAndRejectsTruncatedArchive() {
        val sharedPayload = "ocean-empty-tile".encodeToByteArray()
        val archiveBytes = PMTilesWriter.buildArchive(
            tiles = listOf(
                PMTilesWriter.TileSpec(z = 1, x = 0, y = 0, payload = sharedPayload, runLength = 4L)
            ),
            compressInternal = false,
            compressTiles = false
        )

        PMTilesArchive.open(InMemoryPMTilesByteReader(archiveBytes)).use { archive ->
            assertThat(archive.tile(1, 0, 0)).isEqualTo(sharedPayload)
            assertThat(archive.tile(1, 0, 1)).isEqualTo(sharedPayload)
            assertThat(archive.tile(1, 1, 1)).isEqualTo(sharedPayload)
            assertThat(archive.tile(1, 1, 0)).isEqualTo(sharedPayload)
        }

        val truncated = archiveBytes.copyOf(archiveBytes.size - 5)
        assertThrows(PMTilesException.Truncated::class.java) {
            PMTilesArchive.open(InMemoryPMTilesByteReader(truncated))
        }
    }
}
