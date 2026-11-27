package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElevationSquareGridTest {
    @Test
    fun postsPerDegreeOfLongitudeMirrorsAllSixCopernicusLatitudeBands() {
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(0)).isEqualTo(3_600)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(49)).isEqualTo(3_600)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(50)).isEqualTo(2_400)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(59)).isEqualTo(2_400)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(60)).isEqualTo(1_800)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(70)).isEqualTo(1_200)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(80)).isEqualTo(720)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(85)).isEqualTo(360)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(89)).isEqualTo(360)

        // Southern hemisphere bands mirror equatorward: -50 covers 49..50S (3600), -51 covers 50..51S (2400).
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-1)).isEqualTo(3_600)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-50)).isEqualTo(3_600)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-51)).isEqualTo(2_400)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-61)).isEqualTo(1_800)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-71)).isEqualTo(1_200)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-81)).isEqualTo(720)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-86)).isEqualTo(360)
        assertThat(ElevationSquareGrid.postsPerDegreeOfLongitude(-90)).isEqualTo(360)
    }

    @Test
    fun bilinearInterpolationAcrossCenterBandBoundaryAntimeridianAndPoles() {
        // Exact midpoint inside a 1-arc-second cell at 47.5N, 12.5E.
        val cellStep = 1.0 / 3_600.0
        val mid = requireNotNull(ElevationSquareGrid.postsAround(47.5 - cellStep * 0.5, 12.5 + cellStep * 0.5))
        val interpolated = mid.height { post ->
            when (post) {
                mid.northWest -> 100.0
                mid.northEast -> 200.0
                mid.southWest -> 300.0
                mid.southEast -> 400.0
                else -> null
            }
        }
        assertThat(interpolated).isWithin(1e-6).of(250.0)

        // Missing any one corner post returns null.
        val missingCorner = mid.height { post -> if (post == mid.southEast) null else 100.0 }
        assertThat(missingCorner).isNull()

        // Crossing the 50N band boundary puts north row in 2400/deg and south row in 3600/deg.
        val acrossBand = requireNotNull(ElevationSquareGrid.postsAround(50.0 + cellStep * 0.5, 10.25))
        assertThat(ElevationSquareGrid.tileSouthOfPostRow(acrossBand.northWest.row)).isEqualTo(50)
        assertThat(ElevationSquareGrid.tileSouthOfPostRow(acrossBand.southWest.row)).isEqualTo(49)
        assertThat(ElevationSquareGrid.placeOf(acrossBand.northWest).square.row)
            .isNotEqualTo(ElevationSquareGrid.placeOf(acrossBand.southWest).square.row)

        // Antimeridian wraps east post cleanly back to column 0.
        val antimeridian = requireNotNull(ElevationSquareGrid.postsAround(0.0, 180.0 - cellStep * 0.25))
        assertThat(antimeridian.northWest.column).isEqualTo(360 * 3_600 - 1)
        assertThat(antimeridian.northEast.column).isEqualTo(0)

        // Poles clamp without throwing and invalid/non-finite coordinates return null.
        assertThat(ElevationSquareGrid.postsAround(90.0, 0.0)).isNotNull()
        assertThat(ElevationSquareGrid.postsAround(-90.0, 0.0)).isNotNull()
        assertThat(ElevationSquareGrid.postsAround(90.1, 0.0)).isNull()
        assertThat(ElevationSquareGrid.postsAround(Double.NaN, 12.0)).isNull()
        assertThat(ElevationSquareGrid.postsAround(47.0, Double.POSITIVE_INFINITY)).isNull()
    }

    @Test
    fun placeOfAndRecordNameRoundTripAndRejectOutOfRangeNames() {
        val post = ElevationPost(row = 450 * 338 + 12, column = 450 * 1_542 + 34)
        val place = ElevationSquareGrid.placeOf(post)
        assertThat(place.square).isEqualTo(ElevationSquare(row = 338, column = 1_542))
        assertThat(place.index).isEqualTo(12 * 450 + 34)

        val name = ElevationSquareGrid.recordName(place.square)
        assertThat(name).isEqualTo("elevation1_338_1542")
        assertThat(ElevationSquareGrid.squareNamed(name)).isEqualTo(place.square)

        assertThat(ElevationSquareGrid.squareNamed("elevation2_338_1542")).isNull()
        assertThat(ElevationSquareGrid.squareNamed("elevation1_-1_0")).isNull()
        assertThat(ElevationSquareGrid.squareNamed("elevation1_${ElevationSquareGrid.ROW_COUNT}_0")).isNull()
        assertThat(ElevationSquareGrid.squareNamed("elevation1_0_${ElevationSquareGrid.COLUMN_COUNT}")).isNull()
        assertThat(ElevationSquareGrid.squareNamed("../escape")).isNull()
    }
}
