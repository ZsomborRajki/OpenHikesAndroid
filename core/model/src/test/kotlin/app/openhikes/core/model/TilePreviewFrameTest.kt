package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TilePreviewFrameTest {
    @Test
    fun blockContainsFourAdjacentTilesAndUnitRectMatchesGrid() {
        val frame = TilePreviewFrame.around(null)
        assertThat(frame).isEqualTo(TilePreviewFrame.around(TilePreviewFrame.FALLBACK_COORDINATE))
        assertThat(frame.z).isEqualTo(TilePreviewFrame.DEFAULT_ZOOM)

        val placed = frame.tiles.map { listOf(it.x - frame.x, it.y - frame.y, it.column, it.row) }
        assertThat(placed).containsExactly(
            listOf(0, 0, 0, 0),
            listOf(1, 0, 1, 0),
            listOf(0, 1, 0, 1),
            listOf(1, 1, 1, 1)
        ).inOrder()

        val count = (1 shl frame.z).toDouble()
        assertThat(frame.unitRect).isEqualTo(
            TilePreviewUnitRect(
                originX = frame.x.toDouble() / count,
                originY = frame.y.toDouble() / count,
                side = 2.0 / count
            )
        )
    }

    @Test
    fun antimeridianWrapsEastColumnToZero() {
        val frame = TilePreviewFrame.around(GeoCoordinate(latitude = -17.0, longitude = 179.9999))
        val count = 1 shl frame.z
        assertThat(frame.x).isEqualTo(count - 1)
        assertThat(frame.tiles.map { it.x }.toSet()).containsExactly(count - 1, 0)
    }

    @Test
    fun polesClampInsideMercatorRowRange() {
        val north = TilePreviewFrame.around(GeoCoordinate(latitude = 85.05, longitude = 10.0))
        assertThat(north.y).isEqualTo(0)

        val south = TilePreviewFrame.around(GeoCoordinate(latitude = -85.05, longitude = 10.0))
        val limit = 1 shl south.z
        assertThat(south.tiles.all { it.y in 0 until limit }).isTrue()
    }

    @Test
    fun previewSourceRespectsStyleKindAndApiKeyAvailability() {
        assertThat(TilePreviewSource.from(MapStyleCatalog.openHikes))
            .isEqualTo(TilePreviewSource.VectorSwatch(MapStyleId.OPENHIKES))
        assertThat(TilePreviewSource.from(MapStyleCatalog.openFreeMap))
            .isEqualTo(TilePreviewSource.VectorSwatch(MapStyleId.OPENFREEMAP))
        assertThat(TilePreviewSource.from(MapStyleCatalog.stadiaOutdoors, MapProviderKeys.Empty))
            .isEqualTo(TilePreviewSource.Unavailable)

        val stadiaSource = TilePreviewSource.from(
            MapStyleCatalog.stadiaOutdoors,
            MapProviderKeys(stadiaApiKey = "secret")
        ) as TilePreviewSource.RasterTiles
        val tile = TilePreviewFrame.around(TilePreviewFrame.FALLBACK_COORDINATE).tiles[3]
        assertThat(stadiaSource.urlFor(tile))
            .isEqualTo("https://tiles.stadiamaps.com/tiles/outdoors/${tile.z}/${tile.x}/${tile.y}.png?api_key=secret")
        assertThat(stadiaSource.cacheKeyFor(tile))
            .isEqualTo("stadia_outdoors_${tile.z}_${tile.x}_${tile.y}")

        val osmSource = TilePreviewSource.from(MapStyleCatalog.openStreetMapRaster) as TilePreviewSource.RasterTiles
        assertThat(osmSource.urlFor(tile))
            .isEqualTo("https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png")
    }
}
