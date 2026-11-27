package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.sin
import org.junit.Test

class LibraryLineOutlineTest {
    @Test
    fun dropsDenseWalkingFixesPreservesEndpointsAndRespectsMaximumPointBudget() {
        val denseSwitchbacks = (0..1_200).map { step ->
            val lat = 47.60 + step * 0.00008
            val lon = 12.90 + sin(step / 25.0) * 0.004
            RouteCoordinate(latitude = lat, longitude = lon)
        }
        val runs = LibraryLineOutline.runs(denseSwitchbacks)
        assertThat(runs).hasSize(1)
        val outline = runs.single()
        assertThat(outline.size).isAtMost(LibraryLineOutline.MAXIMUM_POINTS)
        assertThat(outline.first()).isEqualTo(denseSwitchbacks.first().coordinate)
        assertThat(outline.last()).isEqualTo(denseSwitchbacks.last().coordinate)
    }

    @Test
    fun preservesOutAndBackTurnaroundPoint() {
        val start = RouteCoordinate(47.600, 12.900)
        val turnaround = RouteCoordinate(47.610, 12.900)
        val end = RouteCoordinate(47.600, 12.900)
        val runs = LibraryLineOutline.runs(listOf(start, turnaround, end), maximumPoints = 3)
        assertThat(runs.single()).containsExactly(start.coordinate, turnaround.coordinate, end.coordinate).inOrder()
    }

    @Test
    fun omitsExcessShortPiecesWithoutBridgingPauseBoundaries() {
        val route = buildList {
            for (segment in 0 until 34) {
                val baseLat = 47.0 + segment * 0.002
                val pieceSpan = if (segment == 33) 0.0001 else 0.001
                val boundary = if (segment == 0) null else RouteBoundary.PAUSED
                add(RouteCoordinate(baseLat, 12.0, boundary = boundary))
                add(RouteCoordinate(baseLat + pieceSpan, 12.0))
            }
        }
        val runs = LibraryLineOutline.runs(route, maximumPoints = 256, pauseBudget = 32)
        assertThat(runs).hasSize(33)
        assertThat(runs.sumOf { it.size }).isAtMost(256)

        // Midpoint of the 33rd (~111 m) pause gap must NOT be hittable.
        val line = LibraryLine(HikeId("gpx-33-pauses"), RouteTint.DEFAULT_HEX, runs)
        val droppedGapMidpoint = GeoCoordinate(47.0 + 32 * 0.002 + 0.0015, 12.0)
        assertThat(LibraryLineHitTest.nearestHikeId(droppedGapMidpoint, zoom = 16.0, lines = listOf(line))).isNull()

        // Even with pauseBudget = 0, a pause is never bridged (only the single longest piece is kept).
        val singlePiece = LibraryLineOutline.runs(route, maximumPoints = 256, pauseBudget = 0)
        assertThat(singlePiece).hasSize(1)
        assertThat(singlePiece.single()).hasSize(2)
    }

    @Test
    fun hitTestMatchesAntimeridianCrossingSegmentAtZoom16() {
        val seamLine = LibraryLine(
            hikeId = HikeId("seam"),
            tintHex = RouteTint.DEFAULT_HEX,
            runs = listOf(listOf(GeoCoordinate(0.0, 179.999), GeoCoordinate(0.0, -179.999)))
        )

        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(0.0, 180.0), zoom = 16.0, lines = listOf(seamLine)))
            .isEqualTo(HikeId("seam"))
        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(0.0, -180.0), zoom = 16.0, lines = listOf(seamLine)))
            .isEqualTo(HikeId("seam"))
        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(0.0, 0.0), zoom = 16.0, lines = listOf(seamLine)))
            .isNull()
    }

    @Test
    fun hitTestPicksNearestLineWithinZoomToleranceAndPrefersNewestOnTie() {
        val newerLine = LibraryLine(
            hikeId = HikeId("newer"),
            tintHex = RouteTint.DEFAULT_HEX,
            runs = listOf(listOf(GeoCoordinate(47.600, 12.900), GeoCoordinate(47.600, 12.910)))
        )
        val olderLine = LibraryLine(
            hikeId = HikeId("older"),
            tintHex = RouteTint.palette[1],
            runs = listOf(listOf(GeoCoordinate(47.605, 12.900), GeoCoordinate(47.605, 12.910)))
        )
        val lines = listOf(newerLine, olderLine)

        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(47.6001, 12.905), zoom = 14.0, lines = lines))
            .isEqualTo(HikeId("newer"))
        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(47.6051, 12.905), zoom = 14.0, lines = lines))
            .isEqualTo(HikeId("older"))
        assertThat(LibraryLineHitTest.nearestHikeId(GeoCoordinate(47.700, 12.905), zoom = 14.0, lines = lines))
            .isNull()

        val overlappingOlder = newerLine.copy(hikeId = HikeId("older-same"))
        assertThat(
            LibraryLineHitTest.nearestHikeId(
                GeoCoordinate(47.600, 12.905),
                zoom = 14.0,
                lines = listOf(newerLine, overlappingOlder)
            )
        ).isEqualTo(HikeId("newer"))
    }
}
