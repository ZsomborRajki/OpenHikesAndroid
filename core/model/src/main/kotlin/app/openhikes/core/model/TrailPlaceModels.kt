package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Curated hiking place categories stored in Room and exported as GPX `<sym>` values.
 *
 * Raw values are append-only storage and GPX contracts; unknown stored IDs decode to `null`
 * (plain unstated pin) rather than inventing a claim about a place.
 */
@Serializable
enum class TrailPlaceSymbol(val rawValue: String) {
    CAMP("Camp"),
    CAUTION("Caution"),
    JUNCTION("Junction"),
    PARKING("Parking"),
    SHELTER("Shelter"),
    SUMMIT("Summit"),
    VIEWPOINT("Viewpoint"),
    WATER("Water");

    companion object {
        fun fromStoredId(id: String?): TrailPlaceSymbol? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.rawValue == id }
        }
    }
}

/**
 * One OpenStreetMap tag fact displayed on a place card.
 */
@Serializable
data class TrailPlaceFact(val kind: Kind, val value: String) {
    @Serializable
    enum class Kind(val rawKey: String, val tagKeys: List<String> = listOf(rawKey)) {
        ELEVATION("ele"),
        DESCRIPTION("description"),
        DRINKING_WATER("drinking_water"),
        OPENING_HOURS("opening_hours"),
        FEE("fee"),
        ACCESS("access"),
        CAPACITY("capacity"),
        OPERATOR_NAME("operator"),
        PHONE("phone", listOf("phone", "contact:phone")),
        WEBSITE("website", listOf("website", "contact:website", "url"));

        companion object {
            val allTagKeys: Set<String> = entries.flatMap { it.tagKeys }.toSet()
        }
    }

    companion object {
        /**
         * Extracts all recognized OpenStreetMap facts from [tags] in canonical display order,
         * passing each raw tag value through [boundValue] (typically `BoundedText` keywords bound).
         */
        fun facts(tags: Map<String, String>, boundValue: (String?) -> String?): List<TrailPlaceFact> =
            Kind.entries.mapNotNull { kind ->
                kind.tagKeys
                    .firstNotNullOfOrNull { key -> boundValue(tags[key]) }
                    ?.let { value -> TrailPlaceFact(kind = kind, value = value) }
            }
    }
}

/**
 * OpenStreetMap element identity, facts, and optional Wikidata item link attached to a place.
 *
 * Places carrying [TrailPlaceOsm] come from OpenStreetMap: their name and category stay
 * OpenStreetMap's to state and their card links back to openstreetmap.org for edits.
 */
@Serializable
data class TrailPlaceOsm(
    val elementType: String,
    val elementId: Long,
    val facts: List<TrailPlaceFact> = emptyList(),
    val wikidata: String? = null
) {
    val elementKey: String
        get() = "$elementType/$elementId"

    val url: String
        get() = "https://www.openstreetmap.org/$elementType/$elementId"

    fun isSameElement(other: TrailPlaceOsm): Boolean = elementType == other.elementType && elementId == other.elementId

    companion object {
        val ELEMENT_TYPES: Set<String> = setOf("node", "way", "relation")
        private const val MAX_WIKIDATA_DIGITS = 12
        private const val OSM_HOST = "openstreetmap.org"
        private const val WWW_OSM_HOST = "www.openstreetmap.org"

        /**
         * Parses a single Wikidata Q-item (`Q` + 1..12 digits without leading zero) from an OSM
         * `wikidata` tag, rejecting multi-item lists (`Q1;Q2`), URLs, or malformed values.
         */
        fun wikidataItem(fromTag: String?): String? {
            val trimmed = fromTag?.trim().orEmpty()
            val hasPrefix = trimmed.length >= 2 && (trimmed[0] == 'Q' || trimmed[0] == 'q')
            val digits = if (hasPrefix) trimmed.substring(1) else ""
            val validDigits = hasPrefix &&
                digits.length in 1..MAX_WIKIDATA_DIGITS &&
                digits[0] != '0' &&
                digits.all { it in '0'..'9' }
            return if (validDigits) "Q$digits" else null
        }

        /**
         * Parses an OpenStreetMap element URL (`https://www.openstreetmap.org/node/123456`)
         * carried by a GPX `<link href="..."/>`, returning `null` for any other URL.
         */
        fun fromUrl(urlString: String?): TrailPlaceOsm? {
            val trimmed = urlString?.trim().orEmpty()
            val withoutScheme = when {
                trimmed.startsWith("https://") -> trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> trimmed.removePrefix("http://")
                else -> null
            } ?: return null
            val pathStart = withoutScheme.indexOf('/')
            val host = if (pathStart > 0) withoutScheme.substring(0, pathStart).lowercase() else ""
            val isOsmHost = host == WWW_OSM_HOST || host == OSM_HOST
            val parts = if (isOsmHost) {
                withoutScheme.substring(pathStart + 1)
                    .substringBefore('?')
                    .substringBefore('#')
                    .trimEnd('/')
                    .split('/')
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            val elementId = parts.takeIf { it.size == 2 && it[0] in ELEMENT_TYPES }
                ?.get(1)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            return elementId?.let { TrailPlaceOsm(elementType = parts[0], elementId = it) }
        }
    }
}

/**
 * Projection of a place onto a hike's walked route legs.
 */
data class TrailPlaceAnchor(val distanceAlongRouteMeters: Double, val offRouteMeters: Double) {
    val describesTheRoute: Boolean
        get() = offRouteMeters <= DESCRIBABLE_OFF_ROUTE_METERS

    companion object {
        const val DESCRIBABLE_OFF_ROUTE_METERS = 250.0
        const val TOUCHED_OFF_ROUTE_METERS = 50.0
    }
}

/**
 * A place paired with its route projection for list rows, map pins, and place cards.
 */
data class TrailPlaceRow(
    val place: TrailPoint,
    val anchor: TrailPlaceAnchor? = null,
    val offRouteMeters: Double? = anchor?.offRouteMeters
) {
    val id: String
        get() = place.id

    val isOnTheLine: Boolean
        get() = offRouteMeters != null && offRouteMeters <= TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS
}

/**
 * Orders and projects places strictly along walked legs (`TrailRuns.walkedRuns`), never bridging
 * across pause or `<trkseg>` boundaries.
 */
object TrailPlaceOrder {
    fun anchors(places: List<TrailPoint>, route: List<RouteCoordinate>): Map<String, TrailPlaceAnchor> {
        if (route.size <= 1 || places.isEmpty()) return emptyMap()
        val best = HashMap<String, TrailPlaceAnchor>(places.size)
        val runs = route.toTrailRuns()
        var traveled = 0.0
        for (run in runs.walkedRuns) {
            for (leg in run.first until run.last) {
                val start = route[leg].coordinate
                val end = route[leg + 1].coordinate
                val length = RouteGeometry.distanceMeters(start, end)
                for (place in places) {
                    val projection = RouteGeometry.project(place.coordinate, start, end)
                    val candidate = TrailPlaceAnchor(
                        distanceAlongRouteMeters = traveled + length * projection.fraction,
                        offRouteMeters = projection.offRouteMeters
                    )
                    val existing = best[place.id]
                    if (existing == null || candidate.offRouteMeters < existing.offRouteMeters) {
                        best[place.id] = candidate
                    }
                }
                traveled += length
            }
        }
        return best
    }

    fun touched(places: List<TrailPoint>, route: List<RouteCoordinate>): List<TrailPoint> {
        val anchorMap = anchors(places, route)
        return places.filter { place ->
            val anchor = anchorMap[place.id]
            anchor != null && anchor.offRouteMeters <= TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS
        }
    }

    fun ordered(places: List<TrailPoint>, route: List<RouteCoordinate>): List<TrailPlaceRow> =
        ordered(places, anchors(places, route))

    fun ordered(places: List<TrailPoint>, anchors: Map<String, TrailPlaceAnchor>): List<TrailPlaceRow> {
        if (anchors.isEmpty()) {
            return places.map { TrailPlaceRow(place = it, anchor = null, offRouteMeters = null) }
        }
        return places
            .map { place ->
                val raw = anchors[place.id]
                TrailPlaceRow(
                    place = place,
                    anchor = raw?.takeIf { it.describesTheRoute },
                    offRouteMeters = raw?.offRouteMeters
                )
            }
            .sortedWith(
                compareBy<TrailPlaceRow>(
                    { it.anchor?.distanceAlongRouteMeters ?: Double.POSITIVE_INFINITY },
                    { it.offRouteMeters ?: Double.POSITIVE_INFINITY }
                )
            )
    }

    fun rowFor(place: TrailPoint, route: List<RouteCoordinate>): TrailPlaceRow {
        val anchor = anchors(listOf(place), route)[place.id]
        return TrailPlaceRow(
            place = place,
            anchor = anchor?.takeIf { it.describesTheRoute },
            offRouteMeters = anchor?.offRouteMeters
        )
    }
}

/**
 * Duplicate-detection rules shared between place search ranking and adding places to a hike.
 */
object TrailPlaceHolding {
    const val ALREADY_MARKED_METERS = 25.0

    fun isSameElement(place: TrailPoint, others: Collection<TrailPoint>): Boolean {
        val osm = place.osm ?: return false
        return others.any { other -> other.osm?.let(osm::isSameElement) == true }
    }

    fun holds(place: TrailPoint, held: Collection<TrailPoint>): Boolean = isSameElement(place, held) ||
        held.any { existing ->
            RouteGeometry.distanceMeters(existing.coordinate, place.coordinate) <= ALREADY_MARKED_METERS
        }

    fun unheld(found: List<TrailPoint>, held: List<TrailPoint>): List<TrailPoint> {
        if (held.isEmpty()) return found
        val heldElements = held.mapNotNull { it.osm?.elementKey }.toSet()
        return found.filter { candidate ->
            val osmKey = candidate.osm?.elementKey
            val sameOsm = osmKey != null && osmKey in heldElements
            val nearby = held.any { existing ->
                RouteGeometry.distanceMeters(existing.coordinate, candidate.coordinate) <= ALREADY_MARKED_METERS
            }
            !sameOsm && !nearby
        }
    }
}

private const val TRAIL_PLACE_NEARBY_METERS = 500.0
private const val TRAIL_PLACE_AROUND_METERS = 1_000.0

/**
 * Reach selector for *Places Around Trail*.
 */
enum class TrailPlaceReach(val meters: Double) {
    ON_TRAIL(TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS),
    NEARBY(TRAIL_PLACE_NEARBY_METERS),
    AROUND(TRAIL_PLACE_AROUND_METERS)
}

/**
 * Entry in *Places Around Trail* / *Places Nearby* indicating whether the hike already holds the place.
 */
data class TrailPlaceAroundEntry(val row: TrailPlaceRow, val isAdded: Boolean) {
    val id: String
        get() = row.id
}

/**
 * Merged *On This Trail* and *Nearby* sections sorted in walking order.
 */
data class TrailPlaceAroundListing(
    val onTrail: List<TrailPlaceAroundEntry> = emptyList(),
    val nearby: List<TrailPlaceAroundEntry> = emptyList()
) {
    val isEmpty: Boolean
        get() = onTrail.isEmpty() && nearby.isEmpty()

    companion object {
        fun from(held: List<TrailPlaceRow>, candidates: List<TrailPlaceRow>): TrailPlaceAroundListing {
            val entries = held.map { TrailPlaceAroundEntry(row = it, isAdded = true) } +
                candidates.map { TrailPlaceAroundEntry(row = it, isAdded = false) }
            val sorted = entries.sortedWith(
                compareBy<TrailPlaceAroundEntry>(
                    { it.row.anchor?.distanceAlongRouteMeters ?: Double.POSITIVE_INFINITY },
                    { it.row.offRouteMeters ?: Double.POSITIVE_INFINITY }
                )
            )
            val (onTrailEntries, nearbyEntries) = sorted.partition { it.row.isOnTheLine }
            return TrailPlaceAroundListing(onTrail = onTrailEntries, nearby = nearbyEntries)
        }
    }
}
