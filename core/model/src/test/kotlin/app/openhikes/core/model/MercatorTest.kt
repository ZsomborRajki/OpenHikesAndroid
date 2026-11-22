package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MercatorTest {
    @Test
    fun representableBoundsCheckEnforcesWebMercatorLimitsAndFiniteNumbers() {
        assertThat(Mercator.isRepresentable(47.7176, 12.8257)).isTrue()
        assertThat(Mercator.isRepresentable(Mercator.LATITUDE_LIMIT, 180.0)).isTrue()
        assertThat(Mercator.isRepresentable(-Mercator.LATITUDE_LIMIT, -180.0)).isTrue()

        assertThat(Mercator.isRepresentable(86.0, 12.0)).isFalse()
        assertThat(Mercator.isRepresentable(-86.0, 12.0)).isFalse()
        assertThat(Mercator.isRepresentable(47.0, 181.0)).isFalse()
        assertThat(Mercator.isRepresentable(Double.NaN, 12.0)).isFalse()
        assertThat(Mercator.isRepresentable(47.0, Double.POSITIVE_INFINITY)).isFalse()
    }

    @Test
    fun boundingBoxComputesCenterAndSinglePointFlag() {
        val single = GeoBoundingBox.from(listOf(GeoCoordinate(47.7176, 12.8257)))!!
        assertThat(single.isSinglePoint).isTrue()
        assertThat(single.center).isEqualTo(GeoCoordinate(47.7176, 12.8257))

        val multi = GeoBoundingBox.from(
            listOf(
                GeoCoordinate(47.0, 12.0),
                GeoCoordinate(49.0, 14.0)
            )
        )!!
        assertThat(multi.isSinglePoint).isFalse()
        assertThat(multi.center).isEqualTo(GeoCoordinate(48.0, 13.0))
        assertThat(GeoBoundingBox.from(emptyList())).isNull()

        assertThrows(IllegalArgumentException::class.java) {
            GeoBoundingBox(southLat = 50.0, westLon = 10.0, northLat = 49.0, eastLon = 12.0)
        }
    }
}
