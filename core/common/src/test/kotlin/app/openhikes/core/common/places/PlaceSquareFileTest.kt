package app.openhikes.core.common.places

import app.openhikes.core.common.routing.PackedFileException
import app.openhikes.core.model.PlaceSearchBox
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceSymbol
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaceSquareFileTest {
    @Test
    fun roundTripsNodesWaysAndRelationsAndExtractsDeduplicatedPlaces() {
        val encoded = sampleSquareFile().encode()
        val decoded = PlaceSquareFile.decode(encoded)

        assertThat(decoded.coordinateScale).isEqualTo(PlaceSquareFile.STANDARD_COORDINATE_SCALE)
        assertThat(decoded.places).hasSize(4)

        val searchBox = PlaceSearchBox(south = 47.70, west = 12.80, north = 47.75, east = 12.90)
        // Passing the same decoded square twice must still deduplicate by OSM element key.
        val extracted = PlaceSquareFile.extractPlaces(
            squares = listOf(decoded, decoded),
            showing = TrailPlaceSymbol.entries.toSet()
        ) { _, ground ->
            searchBox.overlaps(ground)
        }

        assertThat(extracted).hasSize(2)
        val peak = extracted.first { it.name == "Thumsee Kopf" }
        assertThat(peak.symbolKind).isEqualTo(TrailPlaceSymbol.SUMMIT)
        assertThat(peak.osm?.wikidata).isEqualTo("Q4115189")
        assertThat(peak.osm?.facts).contains(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712"))
        val hut = extracted.first { it.name == "Lakeshore Hut" }
        assertThat(hut.symbolKind).isEqualTo(TrailPlaceSymbol.SHELTER)
        assertThat(hut.osm?.elementType).isEqualTo("way")
    }

    private fun sampleSquareFile(): PlaceSquareFile = PlaceSquareFile(
        places = listOf(
            PlaceSquareFile.Place(
                element = PlaceSquareFile.Element.WAY,
                id = 9_100_002L,
                box = PlaceSquareFile.Box(
                    south = 47_723_900,
                    west = 12_828_900,
                    north = 47_724_100,
                    east = 12_829_200
                ),
                tags = mapOf("tourism" to "alpine_hut", "name" to "Lakeshore Hut", "fee" to "no")
            ),
            PlaceSquareFile.Place(
                element = PlaceSquareFile.Element.WAY,
                id = 9_100_099L,
                box = PlaceSquareFile.Box(
                    south = 47_718_700,
                    west = 12_831_000,
                    north = 47_718_900,
                    east = 12_831_300
                ),
                // Polygon parking ways are intentionally excluded (nodes only for parking).
                tags = mapOf("amenity" to "parking", "name" to "Ignored Bay")
            ),
            PlaceSquareFile.Place(
                element = PlaceSquareFile.Element.NODE,
                id = 9_100_004L,
                point = PlaceSquareFile.Point(latitude = 47_721_500, longitude = 12_841_363),
                tags = mapOf(
                    "natural" to "peak",
                    "name" to "Thumsee Kopf",
                    "ele" to "712",
                    "wikidata" to "Q4115189"
                )
            ),
            PlaceSquareFile.Place(
                element = PlaceSquareFile.Element.NODE,
                id = 50L,
                point = PlaceSquareFile.Point(latitude = -33_856_784, longitude = -70_648_270),
                tags = mapOf("tourism" to "viewpoint", "name" to "Southern Overlook")
            )
        )
    )

    @Test
    fun rejectsBadMagicVersionAndTruncatedPayload() {
        val valid = PlaceSquareFile(emptyList()).encode()

        val badMagic = valid.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(PackedFileException::class.java) { PlaceSquareFile.decode(badMagic) }

        val badVersion = valid.copyOf().also { it[4] = 9 }
        assertThrows(PackedFileException.UnsupportedVersion::class.java) { PlaceSquareFile.decode(badVersion) }

        val truncated = valid.copyOf(valid.size - 2)
        assertThrows(PackedFileException::class.java) { PlaceSquareFile.decode(truncated) }
    }
}
