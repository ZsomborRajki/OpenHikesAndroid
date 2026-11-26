package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryFootprintTest {
    @Test
    fun clockedPartOfBoundsOnlyStampedSubrange() {
        val route = listOf(
            RouteCoordinate(47.0, 12.0),
            RouteCoordinate(47.55, 12.97, timestampEpochMillis = 1_000L),
            RouteCoordinate(47.56, 12.98),
            RouteCoordinate(47.57, 12.99, timestampEpochMillis = 2_000L),
            RouteCoordinate(48.9, 13.9)
        )
        val footprint = LibraryFootprint.clockedPartOf(route)
        assertThat(footprint).isNotNull()
        assertThat(footprint!!.southLat).isWithin(1e-9).of(47.55)
        assertThat(footprint.northLat).isWithin(1e-9).of(47.57)
        assertThat(footprint.westLon).isWithin(1e-9).of(12.97)
        assertThat(footprint.lonSpan).isWithin(1e-9).of(0.02)
    }

    @Test
    fun lineBlocksMatchPointByPointBoundsExactly() {
        val route = (0..250).map { step ->
            RouteCoordinate(
                latitude = 47.50 + step * 0.0004 + if (step % 17 == 0) 0.002 else 0.0,
                longitude = 12.90 + step * 0.0003 - if (step % 23 == 0) 0.003 else 0.0
            )
        }
        val profile = RouteProfile.from(route)
        val line = LibraryFootprint.Line(profile)
        val stretches = listOf(
            profile.distances[10]..profile.distances[195],
            profile.distances[210]..profile.distances[240]
        )
        val blocked = line.footprint(stretches)

        val bruteForcePoints = buildList {
            for (stretch in stretches) {
                profile.coordinateAt(stretch.start)?.let(::add)
                profile.coordinateAt(stretch.endInclusive)?.let(::add)
                profile.distances.forEachIndexed { idx, dist ->
                    if (dist >= stretch.start && dist <= stretch.endInclusive) {
                        add(profile.coordinates[idx])
                    }
                }
            }
        }
        val expected = LibraryFootprint.fromCoordinates(bruteForcePoints)
        assertThat(blocked).isEqualTo(expected)
    }

    @Test
    fun handlesAntimeridianWrappingAndSymmetricMargin() {
        val acrossDateLine = LibraryFootprint.fromCoordinates(
            listOf(
                GeoCoordinate(10.0, 179.9),
                GeoCoordinate(10.1, -179.9)
            )
        )!!
        assertThat(acrossDateLine.westLon).isWithin(1e-9).of(179.9)
        assertThat(acrossDateLine.lonSpan).isWithin(1e-9).of(0.2)

        val nearbyEast = LibraryFootprint.fromCoordinates(listOf(GeoCoordinate(10.05, -179.899)))!!
        val farAway = LibraryFootprint.fromCoordinates(listOf(GeoCoordinate(10.05, 178.0)))!!
        assertThat(acrossDateLine.meets(nearbyEast, marginMeters = 200.0)).isTrue()
        assertThat(nearbyEast.meets(acrossDateLine, marginMeters = 200.0)).isTrue()
        assertThat(acrossDateLine.meets(farAway, marginMeters = 200.0)).isFalse()
        assertThat(farAway.meets(acrossDateLine, marginMeters = 200.0)).isFalse()
    }
}
