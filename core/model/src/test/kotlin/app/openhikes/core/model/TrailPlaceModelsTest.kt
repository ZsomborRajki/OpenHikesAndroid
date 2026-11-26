package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailPlaceModelsTest {
    private val hikeId = HikeId("hike-1")

    @Test
    fun symbolParsingMatchesStoredIdsExactlyAndRejectsUnknown() {
        assertThat(TrailPlaceSymbol.fromStoredId("Shelter")).isEqualTo(TrailPlaceSymbol.SHELTER)
        assertThat(TrailPlaceSymbol.fromStoredId("Water")).isEqualTo(TrailPlaceSymbol.WATER)
        assertThat(TrailPlaceSymbol.fromStoredId("Viewpoint")).isEqualTo(TrailPlaceSymbol.VIEWPOINT)
        assertThat(TrailPlaceSymbol.fromStoredId("unknown")).isNull()
        assertThat(TrailPlaceSymbol.fromStoredId(null)).isNull()
    }

    @Test
    fun osmUrlAndWikidataParsersEnforceStrictFormat() {
        val node = TrailPlaceOsm.fromUrl("https://www.openstreetmap.org/node/123456")
        assertThat(node).isEqualTo(TrailPlaceOsm(elementType = "node", elementId = 123456L))
        assertThat(node?.url).isEqualTo("https://www.openstreetmap.org/node/123456")

        assertThat(TrailPlaceOsm.fromUrl("https://openstreetmap.org/way/42")).isEqualTo(
            TrailPlaceOsm(elementType = "way", elementId = 42L)
        )
        assertThat(TrailPlaceOsm.fromUrl("https://www.openstreetmap.org/relation/7/history")).isNull()
        assertThat(TrailPlaceOsm.fromUrl("https://example.com/node/123")).isNull()
        assertThat(TrailPlaceOsm.fromUrl("https://www.openstreetmap.org/node/0")).isNull()
        assertThat(TrailPlaceOsm.fromUrl("https://www.openstreetmap.org/node/-5")).isNull()

        assertThat(TrailPlaceOsm.wikidataItem("Q4115189")).isEqualTo("Q4115189")
        assertThat(TrailPlaceOsm.wikidataItem("  q42  ")).isEqualTo("Q42")
        assertThat(TrailPlaceOsm.wikidataItem("Q0")).isNull()
        assertThat(TrailPlaceOsm.wikidataItem("Q12abc")).isNull()
        assertThat(TrailPlaceOsm.wikidataItem("P18")).isNull()
    }

    @Test
    fun factExtractionPreservesCanonicalOrderAndFallbackTagKeys() {
        val facts = TrailPlaceFact.facts(
            tags = mapOf(
                "contact:website" to "https://hut.example.org",
                "contact:phone" to "+49 8651 1234",
                "ele" to "1450",
                "description" to "Wooden alpine shelter",
                "operator" to "DAV",
                "fee" to "   "
            ),
            boundValue = { raw -> raw?.trim()?.takeIf(String::isNotEmpty) }
        )

        assertThat(facts).containsExactly(
            TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "1450"),
            TrailPlaceFact(TrailPlaceFact.Kind.DESCRIPTION, "Wooden alpine shelter"),
            TrailPlaceFact(TrailPlaceFact.Kind.OPERATOR_NAME, "DAV"),
            TrailPlaceFact(TrailPlaceFact.Kind.PHONE, "+49 8651 1234"),
            TrailPlaceFact(TrailPlaceFact.Kind.WEBSITE, "https://hut.example.org")
        ).inOrder()
    }

    @Test
    fun placeOrderingRespectsRouteProgressAndNeverBridgesPauseBoundaries() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7020, 12.8000),
            // Large pause jump across 47.7100 -> 47.7200
            RouteCoordinate(47.7200, 12.8000, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7220, 12.8000)
        )
        val firstRunPlace = TrailPoint(
            id = "p1",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7010, 12.8000),
            name = "Start Spring"
        )
        val pausedGapPlace = TrailPoint(
            id = "p-gap",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7110, 12.8000),
            name = "Mid-Gap Point"
        )
        val secondRunPlace = TrailPoint(
            id = "p2",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7210, 12.8000),
            name = "Second Run Hut"
        )

        val ordered = TrailPlaceOrder.ordered(listOf(secondRunPlace, pausedGapPlace, firstRunPlace), route)

        assertThat(ordered.map { it.id }).containsExactly("p1", "p2", "p-gap").inOrder()
        assertThat(ordered[0].isOnTheLine).isTrue()
        assertThat(ordered[1].isOnTheLine).isTrue()
        assertThat(ordered[2].anchor).isNull()
        assertThat(ordered[1].anchor!!.distanceAlongRouteMeters)
            .isGreaterThan(ordered[0].anchor!!.distanceAlongRouteMeters)
    }

    @Test
    fun holdingDetectsSameOsmElementAndNearbyDuplicateWithinTwentyFiveMeters() {
        val heldOsm = TrailPoint(
            id = "h1",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7185, 12.8318),
            name = "Spring",
            osm = TrailPlaceOsm("node", 123456L)
        )
        val heldOwn = TrailPoint(
            id = "h2",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7200, 12.8300),
            name = "Bench"
        )
        val sameOsmDifferentCoord = TrailPoint(
            id = "c1",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7250, 12.8350),
            name = "Spring",
            osm = TrailPlaceOsm("node", 123456L)
        )
        val nearbyOwn = TrailPoint(
            id = "c2",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.72005, 12.83005),
            name = "Same Bench"
        )
        val distinct = TrailPoint(
            id = "c3",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7240, 12.8290),
            name = "Lakeshore Hut",
            osm = TrailPlaceOsm("node", 999L)
        )

        val unheld = TrailPlaceHolding.unheld(
            found = listOf(sameOsmDifferentCoord, nearbyOwn, distinct),
            held = listOf(heldOsm, heldOwn)
        )
        assertThat(unheld.map { it.id }).containsExactly("c3")
    }

    @Test
    fun aroundListingSeparatesOnTrailAndNearbyEntries() {
        val onTrailPlace = TrailPoint(
            id = "p-on",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7010, 12.8000),
            name = "On Trail"
        )
        val nearbyPlace = TrailPoint(
            id = "p-near",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7010, 12.8050),
            name = "Nearby"
        )
        val onTrailRow = TrailPlaceRow(
            place = onTrailPlace,
            anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 100.0, offRouteMeters = 10.0)
        )
        val nearbyRow = TrailPlaceRow(
            place = nearbyPlace,
            anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 120.0, offRouteMeters = 350.0)
        )
        val listing = TrailPlaceAroundListing.from(
            held = listOf(onTrailRow),
            candidates = listOf(nearbyRow)
        )

        assertThat(listing.onTrail.map { it.id to it.isAdded }).containsExactly("p-on" to true)
        assertThat(listing.nearby.map { it.id to it.isAdded }).containsExactly("p-near" to false)
    }
}
