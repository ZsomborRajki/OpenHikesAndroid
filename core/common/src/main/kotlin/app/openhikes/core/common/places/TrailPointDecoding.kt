package app.openhikes.core.common.places

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.Mercator
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thrown when an Overpass API `200 OK` JSON response carries a server `remark` indicating an
 * aborted or rate-limited query rather than an empty result set.
 */
class OverpassRemarkException(val remark: String) : Exception("Overpass remark: $remark")

/**
 * Decodes OpenStreetMap elements from Overpass `out tags center` JSON or pre-built `OHPS` squares
 * through one shared rule set so both sources produce identical [TrailPoint] values.
 */
object TrailPointDecoding {
    private val json = Json { ignoreUnknownKeys = true }
    private val UNASSIGNED_HIKE_ID = HikeId("unassigned")

    /**
     * Parses every recognized place from an Overpass JSON payload, throwing
     * [OverpassRemarkException] if the response contains a non-blank `remark`.
     */
    fun decodeOverpassResponse(
        bytes: ByteArray,
        showing: Set<TrailPlaceSymbol> = TrailPlaceSymbol.entries.toSet()
    ): List<TrailPoint> {
        val response = json.decodeFromString<OverpassResponse>(bytes.decodeToString())
        val remark = response.remark?.trim()?.takeIf { it.isNotEmpty() }
        if (remark != null) {
            throw OverpassRemarkException(remark)
        }
        return response.elements.mapNotNull { element -> decodeElement(element, showing) }
    }

    /**
     * Builds one [TrailPoint] from an OpenStreetMap element, or returns `null` when its tags
     * do not match any requested symbol or its coordinate is not Mercator-representable.
     */
    fun place(
        elementType: String,
        id: Long,
        latitude: Double,
        longitude: Double,
        tags: Map<String, String>,
        showing: Set<TrailPlaceSymbol> = TrailPlaceSymbol.entries.toSet()
    ): TrailPoint? {
        val validIdentity = elementType in TrailPlaceOsm.ELEMENT_TYPES &&
            id > 0L &&
            Mercator.isRepresentable(latitude, longitude)
        val symbol = if (validIdentity) {
            TrailPointQuery.symbolFor(tags = tags, among = showing, isArea = elementType != "node")
        } else {
            null
        }
        return symbol?.let { resolved ->
            TrailPoint(
                id = "osm-$elementType-$id",
                hikeId = UNASSIGNED_HIKE_ID,
                coordinate = GeoCoordinate(latitude = latitude, longitude = longitude),
                name = BoundedText.boundedOrEmpty(tags["name"], TextBound.TITLE),
                symbol = resolved.rawValue,
                osm = TrailPlaceOsm(
                    elementType = elementType,
                    elementId = id,
                    facts = TrailPlaceFact.facts(tags) { raw -> BoundedText.bounded(raw, TextBound.KEYWORDS) },
                    wikidata = TrailPlaceOsm.wikidataItem(tags["wikidata"])
                )
            )
        }
    }

    private fun decodeElement(element: OverpassElement, showing: Set<TrailPlaceSymbol>): TrailPoint? {
        val type = element.type
        val id = element.id
        val lat = element.lat ?: element.center?.lat
        val lon = element.lon ?: element.center?.lon
        val hasIdentity = type != null && id != null
        val hasCoordinates = lat != null && lon != null
        return if (hasIdentity && hasCoordinates) {
            place(
                elementType = type,
                id = id,
                latitude = lat,
                longitude = lon,
                tags = element.tags,
                showing = showing
            )
        } else {
            null
        }
    }

    @Serializable
    private data class OverpassResponse(val elements: List<OverpassElement> = emptyList(), val remark: String? = null)

    @Serializable
    private data class OverpassCenter(val lat: Double, val lon: Double)

    @Serializable
    private data class OverpassElement(
        val type: String? = null,
        val id: Long? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: OverpassCenter? = null,
        val tags: Map<String, String> = emptyMap()
    )
}
