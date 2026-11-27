package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteShadeBlendTest {
    @Test
    fun keepsSingleStretchAndDisjointOrSameShadeStretchesUnblended() {
        val a = GeoCoordinate(47.700, 12.830)
        val b = GeoCoordinate(47.704, 12.830)
        val c = GeoCoordinate(47.708, 12.830)
        val d = GeoCoordinate(47.712, 12.830)

        val single = RouteShadeBlend.blend(listOf(RouteShadeStretch(RouteShade.EASIEST, listOf(a, b))))
        assertThat(single).hasSize(1)
        assertThat(single.single().colorHex).isEqualTo(RouteShade.EASIEST.hexColor)

        // Disjoint stretches (pause/gap) never blend across the gap.
        val disjoint = RouteShadeBlend.blend(
            listOf(
                RouteShadeStretch(RouteShade.EASIEST, listOf(a, b)),
                RouteShadeStretch(RouteShade.HARDEST, listOf(c, d))
            )
        )
        assertThat(disjoint).hasSize(2)
        assertThat(disjoint.map { it.colorHex }).containsExactly(
            RouteShade.EASIEST.hexColor,
            RouteShade.HARDEST.hexColor
        ).inOrder()

        // Contiguous stretches of identical shade never emit extra transition sub-segments.
        val sameShade = RouteShadeBlend.blend(
            listOf(
                RouteShadeStretch(RouteShade.MODERATE, listOf(a, b)),
                RouteShadeStretch(RouteShade.MODERATE, listOf(b, c))
            )
        )
        assertThat(sameShade).hasSize(2)
        assertThat(sameShade.all { it.colorHex == RouteShade.MODERATE.hexColor }).isTrue()
    }

    @Test
    fun blendsContiguousShadeChangeOverQuarterOfShorterNeighborInRouteOrder() {
        val a = GeoCoordinate(47.700, 12.830)
        val b = GeoCoordinate(47.704, 12.830)
        val c = GeoCoordinate(47.712, 12.830)

        val firstStretch = RouteShadeStretch(RouteShade.EASIEST, listOf(a, b))
        val secondStretch = RouteShadeStretch(RouteShade.HARD, listOf(b, c))
        val firstLen = RouteGeometry.lengthMeters(firstStretch.coordinates)
        val secondLen = RouteGeometry.lengthMeters(secondStretch.coordinates)
        val expectedReach = RouteShadeBlend.REACH_SHARE * minOf(firstLen, secondLen)

        val blended = RouteShadeBlend.blend(listOf(firstStretch, secondStretch))

        // First flat segment + 5 interpolated transition sub-segments + second flat segment = 7 segments.
        assertThat(blended).hasSize(2 + RouteShadeBlend.BLEND_SUBSTEPS)
        assertThat(blended.first().colorHex).isEqualTo(RouteShade.EASIEST.hexColor)
        assertThat(blended.last().colorHex).isEqualTo(RouteShade.HARD.hexColor)
        assertThat(blended.first().coordinates.first()).isEqualTo(a)
        assertThat(blended.last().coordinates.last()).isEqualTo(c)

        val flatFirstLen = RouteGeometry.lengthMeters(blended.first().coordinates)
        val flatSecondLen = RouteGeometry.lengthMeters(blended.last().coordinates)
        assertThat(flatFirstLen).isWithin(1.0).of(firstLen - expectedReach)
        assertThat(flatSecondLen).isWithin(1.0).of(secondLen - expectedReach)

        val transitionLen = blended.subList(1, 1 + RouteShadeBlend.BLEND_SUBSTEPS)
            .sumOf { RouteGeometry.lengthMeters(it.coordinates) }
        assertThat(transitionLen).isWithin(1.0).of(expectedReach * 2.0)

        // Transition hex colors are distinct intermediate colors between EASIEST and HARD.
        val transitionColors = blended.subList(1, 1 + RouteShadeBlend.BLEND_SUBSTEPS).map { it.colorHex }
        assertThat(transitionColors.distinct()).hasSize(RouteShadeBlend.BLEND_SUBSTEPS)
        assertThat(transitionColors).doesNotContain(RouteShade.EASIEST.hexColor)
        assertThat(transitionColors).doesNotContain(RouteShade.HARD.hexColor)
    }

    @Test
    fun routeColoringLockingElevationMapsOnlyElevationToDifficulty() {
        assertThat(RouteColoring.ELEVATION.lockingElevation(locked = true)).isEqualTo(RouteColoring.DIFFICULTY)
        assertThat(RouteColoring.ELEVATION.lockingElevation(locked = false)).isEqualTo(RouteColoring.ELEVATION)
        assertThat(RouteColoring.DIFFICULTY.lockingElevation(locked = true)).isEqualTo(RouteColoring.DIFFICULTY)
        assertThat(RouteColoring.OFF.lockingElevation(locked = true)).isEqualTo(RouteColoring.OFF)
        assertThat(RouteColoring.fromStoredId(null)).isEqualTo(RouteColoring.ELEVATION)
        assertThat(RouteColoring.fromStoredId("unknown")).isEqualTo(RouteColoring.ELEVATION)
        assertThat(RouteColoring.fromStoredId("difficulty")).isEqualTo(RouteColoring.DIFFICULTY)
        assertThat(RouteColoring.fromStoredId("off")).isEqualTo(RouteColoring.OFF)
    }
}
