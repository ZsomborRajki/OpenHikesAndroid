package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceSearchModelsTest {
    @Test
    fun squareGridCoversOneDegreeCellsAndAntimeridianCrossing() {
        val box = PlaceSearchBox(south = 47.2, west = 12.1, north = 48.3, east = 12.9)
        val squares = PlaceSquareGrid.squaresCovering(box)
        assertThat(squares).containsExactly(
            PlaceSquare(row = 137, column = 192),
            PlaceSquare(row = 138, column = 192)
        )

        val crossingArea = PlaceSearchArea(
            center = GeoCoordinate(latitude = 0.0, longitude = 179.95),
            radiusMeters = 10_000.0
        )
        val boxes = TrailPointQuery.searchBoxes(crossingArea)
        assertThat(boxes).hasSize(2)
        assertThat(boxes.any { it.west >= 179.0 && it.east <= 180.0 }).isTrue()
        assertThat(boxes.any { it.west >= -180.0 && it.east <= -179.0 }).isTrue()

        val overCeiling = PlaceSearchArea(
            center = GeoCoordinate(latitude = 0.0, longitude = 179.95),
            radiusMeters = 30_000.0
        )
        assertThat(TrailPointQuery.searchBoxes(overCeiling)).isEmpty()
    }

    @Test
    fun symbolClassifierHonorsTagPriorityAndAreaDistinction() {
        // On nodes, water beats shelter (a spring beside a hut is the water source you look for).
        val nodeWithWaterAndHut = TrailPointQuery.symbolFor(
            tags = mapOf("tourism" to "alpine_hut", "amenity" to "drinking_water"),
            among = TrailPlaceSymbol.entries.toSet(),
            isArea = false
        )
        assertThat(nodeWithWaterAndHut).isEqualTo(TrailPlaceSymbol.WATER)

        // On areas (ways/relations), drinking_water is node-only so the alpine_hut building wins.
        val areaWithWaterAndHut = TrailPointQuery.symbolFor(
            tags = mapOf("tourism" to "alpine_hut", "amenity" to "drinking_water"),
            among = TrailPlaceSymbol.entries.toSet(),
            isArea = true
        )
        assertThat(areaWithWaterAndHut).isEqualTo(TrailPlaceSymbol.SHELTER)

        // Summits beat viewpoints on peaks carrying both tags.
        val peakViewpoint = TrailPointQuery.symbolFor(
            tags = mapOf("natural" to "peak", "tourism" to "viewpoint", "name" to "Thumsee Kopf"),
            among = TrailPlaceSymbol.entries.toSet(),
            isArea = false
        )
        assertThat(peakViewpoint).isEqualTo(TrailPlaceSymbol.SUMMIT)
    }

    @Test
    fun corridorSearchSkipsPauseStretchesAndCapsAreaCount() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7050, 12.8000),
            // 20 km unwalked pause jump far wider than one 5 km corridor circle.
            RouteCoordinate(47.9000, 12.8000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.9050, 12.8000)
        )
        val areas = TrailPlaceCorridorSearch.areas(route, reaching = 1_000.0)
        assertThat(areas).hasSize(2)
        assertThat(areas.size).isAtMost(TrailPlaceCorridorSearch.MAXIMUM_AREAS)
        // Neither area should sit in the middle of the unwalked pause gap around lat 47.80.
        assertThat(areas.none { it.center.latitude in 47.75..47.85 }).isTrue()
    }

    @Test
    fun nearbySuggestionsFiltersByRadiusHeldPlacesAndMaxCount() {
        val here = GeoCoordinate(47.7185, 12.8318)
        val held = TrailPoint(
            id = "held-1",
            hikeId = HikeId("h1"),
            coordinate = GeoCoordinate(47.71852, 12.83182),
            name = "Held Spring",
            osm = TrailPlaceOsm("node", 10L)
        )
        val far = TrailPoint(
            id = "far-1",
            hikeId = HikeId("h1"),
            coordinate = GeoCoordinate(47.7250, 12.8400),
            name = "Far Summit",
            osm = TrailPlaceOsm("node", 20L)
        )
        val closePlaces = (1..12).map { index ->
            TrailPoint(
                id = "near-$index",
                hikeId = HikeId("h1"),
                coordinate = GeoCoordinate(47.7185 + index * 0.00005, 12.8318),
                name = "Nearby $index",
                osm = TrailPlaceOsm("node", 100L + index)
            )
        }

        val suggestions = NearbyPlaceSuggestions.suggestions(
            found = listOf(held, far) + closePlaces,
            around = here,
            excluding = listOf(held)
        )

        assertThat(suggestions).hasSize(NearbyPlaceSuggestions.MAXIMUM_SUGGESTIONS)
        assertThat(suggestions.map { it.id }).doesNotContain("held-1")
        assertThat(suggestions.map { it.id }).doesNotContain("far-1")
        assertThat(suggestions.first().id).isEqualTo("near-1")
    }

    @Test
    fun overpassBodyIncludesRequestedSymbolsOnly() {
        val box = PlaceSearchBox(south = 47.7, west = 12.8, north = 47.8, east = 12.9)
        val body = TrailPointQuery.overpassQuery(listOf(box), setOf(TrailPlaceSymbol.WATER))!!
        assertThat(body).contains("drinking_water")
        assertThat(body).contains("spring")
        assertThat(body).doesNotContain("alpine_hut")
        assertThat(body).doesNotContain("parking")
    }
}
