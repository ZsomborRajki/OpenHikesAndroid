package app.openhikes.core.common.routing

import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TrailGraphSquareFileTest {
    @Test
    fun encodesAndDecodesWaysWithTagsAndExtractsCrossingGraph() {
        val original = TrailGraphSquareFile(
            ways = listOf(
                TrailGraphSquareFile.Way(
                    id = 42L,
                    highway = "path",
                    tags = mapOf(
                        TrailGraphSquareFile.Tag.NAME to "Thumsee Ridge Path",
                        TrailGraphSquareFile.Tag.SAC_SCALE to "mountain_hiking",
                        TrailGraphSquareFile.Tag.SURFACE to "ground"
                    ),
                    nodes = listOf(
                        TrailGraphSquareFile.Node(id = 100L, latitude = 47_718_350, longitude = 12_831_700),
                        TrailGraphSquareFile.Node(id = 101L, latitude = 47_718_900, longitude = 12_831_700)
                    )
                ),
                TrailGraphSquareFile.Way(
                    id = 99L,
                    highway = "residential",
                    tags = mapOf(TrailGraphSquareFile.Tag.NAME to "Far Street"),
                    nodes = listOf(
                        TrailGraphSquareFile.Node(id = 200L, latitude = 48_000_000, longitude = 13_000_000),
                        TrailGraphSquareFile.Node(id = 201L, latitude = 48_001_000, longitude = 13_000_000)
                    )
                )
            )
        )

        val encoded = original.encode()
        val decoded = TrailGraphSquareFile.decode(encoded)

        assertThat(decoded).isEqualTo(original)

        val bounds = GeoBoundingBox(southLat = 47.71, westLon = 12.82, northLat = 47.73, eastLon = 12.85)
        val graph = decoded.toTrailGraphCrossing(bounds)

        assertThat(graph.nodes).hasSize(2)
        assertThat(graph.edges).hasSize(1)
        assertThat(graph.edges.first().name).isEqualTo("Thumsee Ridge Path")
        assertThat(graph.edges.first().sacScale).isEqualTo("mountain_hiking")
        assertThat(graph.edges.first().highway).isEqualTo("path")
    }

    @Test
    fun rejectsWrongMagicUnsupportedVersionAndCorruptPayload() {
        val badMagic = byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), '!'.code.toByte(), 1, 0, 0)
        assertThrows(PackedFileException.WrongFormat::class.java) {
            TrailGraphSquareFile.decode(badMagic)
        }

        val badVersion =
            byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(), 9, 0, 0)
        assertThrows(PackedFileException.UnsupportedVersion::class.java) {
            TrailGraphSquareFile.decode(badVersion)
        }

        val truncated = byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'G'.code.toByte())
        assertThrows(PackedFileException.Truncated::class.java) {
            TrailGraphSquareFile.decode(truncated)
        }
    }

    @Test
    fun antimeridianSegmentCrossesEasternAndWesternBoundsWithoutCrossingGreenwich() {
        val start = GeoCoordinate(latitude = -16.5, longitude = 179.9)
        val end = GeoCoordinate(latitude = -16.5, longitude = -179.9)
        val fijiEast = GeoBoundingBox(southLat = -17.0, westLon = 179.5, northLat = -16.0, eastLon = 180.0)
        val fijiWest = GeoBoundingBox(southLat = -17.0, westLon = -180.0, northLat = -16.0, eastLon = -179.5)
        val greenwich = GeoBoundingBox(southLat = -17.0, westLon = -1.0, northLat = -16.0, eastLon = 1.0)

        assertThat(TrailGraphSquareFile.segmentCrossesBounds(start, end, fijiEast)).isTrue()
        assertThat(TrailGraphSquareFile.segmentCrossesBounds(start, end, fijiWest)).isTrue()
        assertThat(TrailGraphSquareFile.segmentCrossesBounds(start, end, greenwich)).isFalse()
    }
}
