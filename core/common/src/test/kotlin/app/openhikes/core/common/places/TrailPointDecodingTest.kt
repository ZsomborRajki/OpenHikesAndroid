package app.openhikes.core.common.places

import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceSymbol
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TrailPointDecodingTest {
    @Test
    fun decodesOverpassNodesAndWayCentersWithFactsAndWikidata() {
        val json = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 9100004,
                  "lat": 47.7215,
                  "lon": 12.841363,
                  "tags": {
                    "natural": "peak",
                    "name": "Thumsee Kopf",
                    "ele": "712",
                    "wikidata": "Q4115189"
                  }
                },
                {
                  "type": "way",
                  "id": 9100002,
                  "center": { "lat": 47.724008, "lon": 12.829098 },
                  "tags": {
                    "tourism": "alpine_hut",
                    "name": "Lakeshore Hut",
                    "fee": "no"
                  }
                },
                {
                  "type": "way",
                  "id": 9100099,
                  "center": { "lat": 47.718823, "lon": 12.831149 },
                  "tags": {
                    "amenity": "parking",
                    "name": "Ignored Polygon Parking"
                  }
                }
              ]
            }
        """.trimIndent().encodeToByteArray()

        val places = TrailPointDecoding.decodeOverpassResponse(json)
        assertThat(places).hasSize(2)

        val peak = places.first { it.name == "Thumsee Kopf" }
        assertThat(peak.symbolKind).isEqualTo(TrailPlaceSymbol.SUMMIT)
        assertThat(peak.osm?.elementType).isEqualTo("node")
        assertThat(peak.osm?.elementId).isEqualTo(9_100_004L)
        assertThat(peak.osm?.wikidata).isEqualTo("Q4115189")
        assertThat(peak.osm?.facts).containsExactly(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712"))

        val hut = places.first { it.name == "Lakeshore Hut" }
        assertThat(hut.symbolKind).isEqualTo(TrailPlaceSymbol.SHELTER)
        assertThat(hut.osm?.elementType).isEqualTo("way")
    }

    @Test
    fun throwsOverpassRemarkExceptionWhenServerReportsRemark() {
        val json = """
            {
              "remark": "runtime error: Query timed out in \"query\" at line 1 after 25 seconds.",
              "elements": []
            }
        """.trimIndent().encodeToByteArray()

        val error = assertThrows(OverpassRemarkException::class.java) {
            TrailPointDecoding.decodeOverpassResponse(json)
        }
        assertThat(error.remark).contains("timed out")
    }
}
