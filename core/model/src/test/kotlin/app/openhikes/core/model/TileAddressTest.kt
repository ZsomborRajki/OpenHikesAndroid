package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileAddressTest {
    @Test
    fun validatesZoomAndTileBounds() {
        assertThat(TileAddress(0, 0, 0).isValid).isTrue()
        assertThat(TileAddress(0, 1, 0).isValid).isFalse()
        assertThat(TileAddress(4, 15, 15).isValid).isTrue()
        assertThat(TileAddress(4, 16, 0).isValid).isFalse()
        assertThat(TileAddress(-1, 0, 0).isValid).isFalse()
        assertThat(TileAddress(31, 0, 0).isValid).isFalse()
    }

    @Test
    fun overzoomsDeepTilesByShiftingCoordinates() {
        val tile = TileAddress(z = 17, x = 70_000, y = 45_000)
        val overzoomed = tile.overzoomedTo(maxZoom = 15)
        assertThat(overzoomed).isEqualTo(TileAddress(z = 15, x = 70_000 ushr 2, y = 45_000 ushr 2))
        assertThat(TileAddress(z = 12, x = 100, y = 200).overzoomedTo(15)).isEqualTo(TileAddress(12, 100, 200))
    }

    @Test
    fun computesWorldBoundsAtZoomZeroAndCenterInsideBounds() {
        val world = TileAddress(0, 0, 0).toGeoBoundingBox()
        assertThat(world.westLon).isWithin(1e-6).of(-180.0)
        assertThat(world.eastLon).isWithin(1e-6).of(180.0)
        assertThat(world.southLat).isLessThan(-85.0)
        assertThat(world.northLat).isGreaterThan(85.0)

        val center = TileAddress(0, 0, 0).centerCoordinate()
        assertThat(center.latitude).isWithin(1e-6).of(0.0)
        assertThat(center.longitude).isWithin(1e-6).of(0.0)
    }
}
