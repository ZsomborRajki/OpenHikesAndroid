package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineRouteCoverageTest {
    private val squareWest = boxCountry("AA", west = 0.0, south = 0.0, east = 4.0, north = 4.0)
    private val squareEast = boxCountry("BB", west = 6.0, south = 0.0, east = 10.0, north = 4.0)
    private val squareMiddle = boxCountry("CC", west = 4.0, south = 0.0, east = 6.0, north = 4.0)

    @Test
    fun cutsSparseWalkedLegAcrossIntermediateCountry() {
        val route = listOf(
            RouteCoordinate(latitude = 2.0, longitude = 1.0),
            RouteCoordinate(latitude = 2.0, longitude = 9.0)
        )
        val coverage = OfflineRouteCoverageCalculator.compute(
            route = route,
            countries = listOf(squareWest, squareMiddle, squareEast)
        )

        assertThat(coverage.crossed.map { it.id }).containsExactly("AA", "CC", "BB").inOrder()
        assertThat(coverage.leavesThem).isFalse()
    }

    @Test
    fun skipsPausedGapAndNeverClaimsCountryOnlyCrossedWhilePaused() {
        val route = listOf(
            RouteCoordinate(latitude = 2.0, longitude = 1.0),
            RouteCoordinate(latitude = 2.0, longitude = 2.0),
            RouteCoordinate(latitude = 2.0, longitude = 8.0, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(latitude = 2.0, longitude = 9.0)
        )
        val coverage = OfflineRouteCoverageCalculator.compute(
            route = route,
            countries = listOf(squareWest, squareMiddle, squareEast)
        )

        assertThat(coverage.crossed.map { it.id }).containsExactly("AA", "BB").inOrder()
        assertThat(coverage.leavesThem).isFalse()
    }

    @Test
    fun testsLoneFixAndEvaluatesSavingStates() {
        val route = listOf(
            RouteCoordinate(latitude = 2.0, longitude = 1.0),
            RouteCoordinate(latitude = 2.0, longitude = 8.0, boundary = RouteBoundary.PAUSED)
        )
        val coverage = OfflineRouteCoverageCalculator.compute(
            route = route,
            countries = listOf(squareWest, squareEast)
        )

        assertThat(coverage.crossed.map { it.id }).containsExactly("AA", "BB").inOrder()
        assertThat(OfflineRouteCoverageCalculator.evaluateSaving(coverage, emptySet()))
            .isEqualTo(OfflineRouteSaving.UNSAVED)
        assertThat(OfflineRouteCoverageCalculator.evaluateSaving(coverage, setOf("AA")))
            .isEqualTo(OfflineRouteSaving.PART)
        assertThat(OfflineRouteCoverageCalculator.evaluateSaving(coverage, setOf("AA", "BB")))
            .isEqualTo(OfflineRouteSaving.WHOLE)
        assertThat(
            OfflineRouteCoverageCalculator.evaluateSaving(
                coverage.copy(leavesThem = true),
                setOf("AA", "BB")
            )
        ).isEqualTo(OfflineRouteSaving.PART)
    }

    @Test
    fun splitsLegCrossingAntimeridian() {
        val westEdge = boxCountry("FJ", west = 178.0, south = -20.0, east = 180.0, north = -15.0)
        val eastEdge = boxCountry("WS", west = -180.0, south = -20.0, east = -178.0, north = -15.0)
        val route = listOf(
            RouteCoordinate(latitude = -17.0, longitude = 179.0),
            RouteCoordinate(latitude = -17.0, longitude = -179.0)
        )
        val coverage = OfflineRouteCoverageCalculator.compute(route, listOf(westEdge, eastEdge))

        assertThat(coverage.crossed.map { it.id }).containsExactly("FJ", "WS").inOrder()
        assertThat(coverage.leavesThem).isFalse()
    }

    private fun boxCountry(id: String, west: Double, south: Double, east: Double, north: Double): OfflineCountry =
        OfflineCountry(
            id = id,
            build = "20261117",
            schema = OfflineCountryManifest.READABLE_SCHEMA,
            bounds = OfflineBounds(west = west, south = south, east = east, north = north),
            outline = listOf(
                listOf(
                    listOf(west, south),
                    listOf(east, south),
                    listOf(east, north),
                    listOf(west, north),
                    listOf(west, south)
                )
            ),
            files = listOf(
                OfflineCountryFile(
                    role = OfflineFileRole.BASEMAP,
                    path = "countries/$id-20261117.pmtiles",
                    byteCount = 1_000L,
                    sha256 = "0".repeat(64),
                    minimumZoom = 0,
                    maximumZoom = 14
                )
            )
        )
}
