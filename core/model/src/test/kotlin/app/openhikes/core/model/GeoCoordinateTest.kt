package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class GeoCoordinateTest {
    @Test
    fun acceptsValidCoordinatesIncludingPolarAndAntimeridianBounds() {
        val thumsee = GeoCoordinate(latitude = 47.7176, longitude = 12.8257)
        val southWestCorner = GeoCoordinate(latitude = -90.0, longitude = -180.0)
        val northEastCorner = GeoCoordinate(latitude = 90.0, longitude = 180.0)

        assertThat(thumsee.latitude).isWithin(1e-6).of(47.7176)
        assertThat(thumsee.longitude).isWithin(1e-6).of(12.8257)
        assertThat(southWestCorner.latitude).isEqualTo(-90.0)
        assertThat(northEastCorner.longitude).isEqualTo(180.0)
    }

    @Test
    fun rejectsOutOfBoundsAndNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            GeoCoordinate(latitude = 90.001, longitude = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeoCoordinate(latitude = 0.0, longitude = -180.001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeoCoordinate(latitude = Double.NaN, longitude = 12.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeoCoordinate(latitude = 47.0, longitude = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun hikeIdRejectsBlankValues() {
        val valid = HikeId("thumsee-loop")
        assertThat(valid.value).isEqualTo("thumsee-loop")

        assertThrows(IllegalArgumentException::class.java) {
            HikeId("   ")
        }
    }
}
