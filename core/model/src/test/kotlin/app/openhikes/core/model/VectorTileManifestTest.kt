package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorTileManifestTest {
    @Test
    fun archiveNameAllowlistRejectsTraversalUppercaseAndEmptyHyphens() {
        assertThat(VectorTileArchiveManifest.isArchiveName("planet-20261118")).isTrue()
        assertThat(VectorTileArchiveManifest.isArchiveName("extras-20261122-00")).isTrue()
        assertThat(VectorTileArchiveManifest.isArchiveName("")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("-planet")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("planet-")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("planet--2026")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("Planet-2026")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("../etc/passwd")).isFalse()
        assertThat(VectorTileArchiveManifest.isArchiveName("a".repeat(65))).isFalse()
    }

    @Test
    fun sourceOnPicksFirstCompatibleBasemapAndExtrasAndRejectsUnknownVersion() {
        val manifest = VectorTileArchiveManifest(
            version = 1,
            revision = 11,
            basemaps = listOf(
                VectorTileArchiveManifest.BasemapArchive(name = "planet-future", schema = 99, maximumZoom = 15),
                VectorTileArchiveManifest.BasemapArchive(name = "planet-20261118", schema = 4, maximumZoom = 15),
                VectorTileArchiveManifest.BasemapArchive(name = "planet-older", schema = 4, maximumZoom = 14)
            ),
            extras = listOf(
                VectorTileArchiveManifest.ExtrasArchive(name = "extras-future", generation = 9, minimumZoom = 4),
                VectorTileArchiveManifest.ExtrasArchive(name = "extras-20261122-00", generation = 2, minimumZoom = 4)
            ),
            places = listOf(
                VectorTileArchiveManifest.SquareArchive(name = "places-future", generation = 9, zoom = 9),
                VectorTileArchiveManifest.SquareArchive(name = "places-20261122-00", generation = 1, zoom = 9)
            ),
            elevation = listOf(
                VectorTileArchiveManifest.SquareArchive(name = "elevation-future", generation = 9, zoom = 12),
                VectorTileArchiveManifest.SquareArchive(name = "elevation-wrong-zoom", generation = 1, zoom = 10),
                VectorTileArchiveManifest.SquareArchive(name = "elevation-20261126-20", generation = 1, zoom = 12)
            )
        )

        val source = manifest.sourceOn("https://tiles.openhikes.app/")
        assertThat(source).isNotNull()
        assertThat(source!!.basemapArchiveName).isEqualTo("planet-20261118")
        assertThat(source.basemapUrl).isEqualTo("https://tiles.openhikes.app/planet-20261118")
        assertThat(source.extras?.archiveName).isEqualTo("extras-20261122-00")
        assertThat(source.places?.archiveName).isEqualTo("places-20261122-00")
        assertThat(source.places?.squareTileUrl(PlaceSquare(row = 137, column = 192)))
            .isEqualTo("https://tiles.openhikes.app/places-20261122-00/9/192/137.bin")
        assertThat(source.elevation?.archiveName).isEqualTo("elevation-20261126-20")
        assertThat(source.elevation?.squareTileUrl(ElevationSquare(row = 338, column = 1542)))
            .isEqualTo("https://tiles.openhikes.app/elevation-20261126-20/12/1542/338.bin")
        assertThat(source.basemapTileUrl(TileAddress(17, 400, 800)))
            .isEqualTo("https://tiles.openhikes.app/planet-20261118/15/100/200.mvt")
        assertThat(source.extrasTileUrl(TileAddress(3, 1, 1))).isNull()
        assertThat(source.extrasTileUrl(TileAddress(4, 1, 1)))
            .isEqualTo("https://tiles.openhikes.app/extras-20261122-00/4/1/1.mvt")

        val badVersion = manifest.copy(version = 2)
        assertThat(badVersion.sourceOn("https://tiles.openhikes.app")).isNull()
    }
}
