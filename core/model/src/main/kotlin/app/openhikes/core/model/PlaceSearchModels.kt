package app.openhikes.core.model

import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Geographic bounding box formatted for Overpass filters and place-square overlap checks.
 */
data class PlaceSearchBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    val overpassLiteral: String
        get() = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east)

    fun overlaps(other: PlaceSearchBox): Boolean =
        other.south <= north && south <= other.north && other.west <= east && west <= other.east

    fun toGeoBoundingBox(): GeoBoundingBox = GeoBoundingBox(
        southLat = south,
        westLon = west,
        northLat = north,
        eastLon = east
    )
}

/**
 * Search region described by center + radius and optional explicit viewport bounds.
 */
data class PlaceSearchArea(val center: GeoCoordinate, val radiusMeters: Double, val bounds: GeoBoundingBox? = null) {
    fun contains(coordinate: GeoCoordinate): Boolean {
        val explicitBounds = bounds
        return if (explicitBounds != null) {
            coordinate.latitude in explicitBounds.southLat..explicitBounds.northLat &&
                longitudeInSpan(coordinate.longitude, explicitBounds.westLon, explicitBounds.eastLon)
        } else {
            RouteGeometry.distanceMeters(center, coordinate) <= radiusMeters
        }
    }

    companion object {
        private const val FULL_TURN = 360.0

        private fun longitudeInSpan(lon: Double, west: Double, east: Double): Boolean = if (west <= east) {
            lon in west..east
        } else {
            lon >= west || lon <= east
        }

        fun fromBounds(bounds: GeoBoundingBox): PlaceSearchArea {
            val center = bounds.center
            val halfLat = RouteGeometry.distanceMeters(
                GeoCoordinate(bounds.southLat, center.longitude),
                GeoCoordinate(bounds.northLat, center.longitude)
            ) / 2.0
            val halfLon = RouteGeometry.distanceMeters(
                GeoCoordinate(center.latitude, bounds.westLon),
                GeoCoordinate(center.latitude, bounds.eastLon)
            ) / 2.0
            return PlaceSearchArea(
                center = center,
                radiusMeters = max(halfLat, halfLon),
                bounds = bounds
            )
        }

        internal fun normalizeLon(lon: Double): Double {
            var value = lon % FULL_TURN
            if (value > HALF_TURN) value -= FULL_TURN
            if (value < -HALF_TURN) value += FULL_TURN
            return value
        }

        private const val HALF_TURN = 180.0
    }
}

/**
 * Reason a remote place search could not complete.
 */
enum class PlaceSearchOutage {
    OFFLINE,
    RATE_LIMITED,
    UNAVAILABLE
}

/**
 * Outcome of querying [TrailPointSource.places].
 */
sealed interface TrailPointSearchResult {
    data class Places(val places: List<TrailPoint>) : TrailPointSearchResult

    data class Failed(val outage: PlaceSearchOutage) : TrailPointSearchResult
}

/**
 * Seam supplying OpenStreetMap places for corridor, area, and nearby searches.
 */
interface TrailPointSource {
    val isAvailable: Boolean

    suspend fun places(area: PlaceSearchArea, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult

    suspend fun cachedPlaces(area: PlaceSearchArea, limit: Int): List<TrailPoint> = emptyList()
}

/**
 * One 1°-by-1° square of OpenHikes's pre-built OpenStreetMap places grid (`PlaceSquareGrid`).
 */
data class PlaceSquare(val row: Int, val column: Int)

/**
 * Global 1° grid contract shared with `tiles.openhikes.app/places-<build>/9/<col>/<row>.bin`.
 */
object PlaceSquareGrid {
    const val GENERATION = 1
    const val SQUARES_PER_DEGREE = 1
    const val ROW_COUNT = 180
    const val COLUMN_COUNT = 360
    const val ARCHIVE_ZOOM = 9

    private const val QUARTER_TURN_DEGREES = 90.0
    private const val HALF_TURN_DEGREES = 180.0
    private const val NAME_PREFIX = "places$GENERATION"
    private const val NAME_PARTS = 3

    fun squaresCovering(box: PlaceSearchBox): List<PlaceSquare> {
        val valid = box.south.isFinite() &&
            box.west.isFinite() &&
            box.north.isFinite() &&
            box.east.isFinite() &&
            box.south <= box.north &&
            box.west <= box.east
        if (!valid) return emptyList()
        val startRow = rowOfLatitude(box.south)
        val endRow = rowOfLatitude(box.north)
        val firstCol = unwrappedColumn(box.west)
        val lastCol = unwrappedColumn(box.east)
        val columns = if (lastCol - firstCol < COLUMN_COUNT) {
            (firstCol..lastCol).map(::wrapColumn)
        } else {
            (0 until COLUMN_COUNT).toList()
        }
        val result = ArrayList<PlaceSquare>((endRow - startRow + 1) * columns.size)
        for (row in startRow..endRow) {
            for (column in columns) {
                result.add(PlaceSquare(row = row, column = column))
            }
        }
        return result
    }

    fun recordName(square: PlaceSquare): String = "${NAME_PREFIX}_${square.row}_${square.column}"

    fun squareNamed(name: String): PlaceSquare? {
        val parts = name.split('_')
        val row = parts.takeIf { it.size == NAME_PARTS && it[0] == NAME_PREFIX }?.get(1)?.toIntOrNull()
        val column = row?.let { parts[2].toIntOrNull() }
        val validRow = row != null && row in 0 until ROW_COUNT
        val validCol = column != null && column in 0 until COLUMN_COUNT
        return if (validRow && validCol) {
            PlaceSquare(row = row, column = column)
        } else {
            null
        }
    }

    private fun rowOfLatitude(latitude: Double): Int {
        val index = floor((latitude + QUARTER_TURN_DEGREES) * SQUARES_PER_DEGREE).toInt()
        return index.coerceIn(0, ROW_COUNT - 1)
    }

    private fun unwrappedColumn(longitude: Double): Int =
        floor((longitude + HALF_TURN_DEGREES) * SQUARES_PER_DEGREE).toInt()

    private fun wrapColumn(column: Int): Int {
        val rem = column % COLUMN_COUNT
        return if (rem < 0) rem + COLUMN_COUNT else rem
    }
}

/**
 * OpenStreetMap place tag table, tie-breaking rules, search-box math, and Overpass query builder.
 */
object TrailPointQuery {
    const val MAXIMUM_RADIUS_METERS = 10_000.0
    const val TIMEOUT_SECONDS = 25
    const val MAXIMUM_RESULTS = 40
    const val MAXIMUM_STORED_RESULTS = MAXIMUM_RESULTS * 5
    const val MAXIMUM_SQUARES_PER_SEARCH = 35

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val MIN_COS_LAT = 0.01
    private const val HALF_TURN = 180.0
    private const val FULL_TURN = 360.0
    private const val MAX_LAT = 85.0

    data class Kind(
        val key: String,
        val value: String,
        val symbol: TrailPlaceSymbol,
        val includesAreas: Boolean = false
    )

    val kinds: List<Kind> = listOf(
        Kind("natural", "peak", TrailPlaceSymbol.SUMMIT),
        Kind("natural", "saddle", TrailPlaceSymbol.SUMMIT),
        Kind("waterway", "waterfall", TrailPlaceSymbol.WATER),
        Kind("natural", "spring", TrailPlaceSymbol.WATER),
        Kind("amenity", "drinking_water", TrailPlaceSymbol.WATER),
        Kind("tourism", "alpine_hut", TrailPlaceSymbol.SHELTER, includesAreas = true),
        Kind("tourism", "wilderness_hut", TrailPlaceSymbol.SHELTER, includesAreas = true),
        Kind("amenity", "shelter", TrailPlaceSymbol.SHELTER, includesAreas = true),
        Kind("tourism", "viewpoint", TrailPlaceSymbol.VIEWPOINT),
        Kind("tourism", "camp_site", TrailPlaceSymbol.CAMP, includesAreas = true),
        Kind("amenity", "parking", TrailPlaceSymbol.PARKING)
    )

    val searchableSymbols: List<TrailPlaceSymbol> = kinds.map { it.symbol }.distinct()

    val keptTagKeys: Set<String> = buildSet {
        kinds.forEach { add(it.key) }
        add("name")
        add("wikidata")
        addAll(TrailPlaceFact.Kind.allTagKeys)
    }

    fun isPlace(isArea: Boolean, tags: Map<String, String>): Boolean = kinds.any { kind ->
        (!isArea || kind.includesAreas) && tags[kind.key] == kind.value
    }

    fun searchBoxes(area: PlaceSearchArea): List<PlaceSearchBox> {
        if (!area.radiusMeters.isFinite() || area.radiusMeters <= 0.0 || area.radiusMeters > MAXIMUM_RADIUS_METERS) {
            return emptyList()
        }
        val explicit = area.bounds
        return if (explicit != null) {
            splitAntimeridian(explicit.southLat, explicit.westLon, explicit.northLat, explicit.eastLon)
        } else {
            val latDelta = area.radiusMeters / METERS_PER_DEGREE_LAT
            val cosLat = cos(area.center.latitude * PI / HALF_TURN).coerceAtLeast(MIN_COS_LAT)
            val lonDelta = (area.radiusMeters / (METERS_PER_DEGREE_LAT * cosLat)).coerceAtMost(HALF_TURN)
            val south = (area.center.latitude - latDelta).coerceIn(-MAX_LAT, MAX_LAT)
            val north = (area.center.latitude + latDelta).coerceIn(-MAX_LAT, MAX_LAT)
            val west = area.center.longitude - lonDelta
            val east = area.center.longitude + lonDelta
            splitAntimeridian(south, west, north, east)
        }
    }

    fun overpassQuery(
        boxes: List<PlaceSearchBox>,
        symbols: Set<TrailPlaceSymbol> = searchableSymbols.toSet()
    ): String? {
        val asked = kinds.filter { it.symbol in symbols }
        if (boxes.isEmpty() || asked.isEmpty()) return null
        val filters = buildString {
            for (box in boxes) {
                for (kind in asked) {
                    val element = if (kind.includesAreas) "nwr" else "node"
                    append("  $element[\"${kind.key}\"=\"${kind.value}\"](${box.overpassLiteral});\n")
                }
            }
        }.trimEnd('\n')
        return "[out:json][timeout:$TIMEOUT_SECONDS];\n(\n$filters\n);\nout tags center;"
    }

    fun symbolFor(
        tags: Map<String, String>,
        among: Set<TrailPlaceSymbol> = TrailPlaceSymbol.entries.toSet(),
        isArea: Boolean = false
    ): TrailPlaceSymbol? = kinds.firstOrNull { kind ->
        kind.symbol in among && (!isArea || kind.includesAreas) && tags[kind.key] == kind.value
    }?.symbol

    private fun splitAntimeridian(south: Double, west: Double, north: Double, east: Double): List<PlaceSearchBox> {
        if (east - west >= FULL_TURN) {
            return listOf(PlaceSearchBox(south, -HALF_TURN, north, HALF_TURN))
        }
        val normWest = PlaceSearchArea.normalizeLon(west)
        val normEast = PlaceSearchArea.normalizeLon(east)
        return if (normWest <= normEast) {
            listOf(PlaceSearchBox(south, normWest, north, normEast))
        } else {
            listOf(
                PlaceSearchBox(south, normWest, north, HALF_TURN),
                PlaceSearchBox(south, -HALF_TURN, north, normEast)
            )
        }
    }
}

/**
 * Ranks candidate places against the route line (or area center when no line exists),
 * excluding already marked places first.
 */
object TrailPointRanking {
    fun excluding(placed: List<TrailPoint>, found: List<TrailPoint>): List<TrailPoint> =
        TrailPlaceHolding.unheld(found, placed)

    fun chosen(
        found: List<TrailPoint>,
        route: List<RouteCoordinate>,
        center: GeoCoordinate?,
        limit: Int = TrailPointQuery.MAXIMUM_RESULTS
    ): List<TrailPoint> {
        if (found.size <= limit) return found
        val anchors = TrailPlaceOrder.anchors(found, route)
        return if (anchors.isEmpty()) {
            if (center == null) {
                found.take(limit)
            } else {
                found.map { it to RouteGeometry.distanceMeters(center, it.coordinate) }
                    .sortedBy { it.second }
                    .take(limit)
                    .map { it.first }
            }
        } else {
            found.map { it to (anchors[it.id]?.offRouteMeters ?: Double.POSITIVE_INFINITY) }
                .sortedBy { it.second }
                .take(limit)
                .map { it.first }
        }
    }

    fun offered(
        found: List<TrailPoint>,
        route: List<RouteCoordinate>,
        area: PlaceSearchArea?,
        excluding: List<TrailPoint>,
        limit: Int = TrailPointQuery.MAXIMUM_RESULTS
    ): List<TrailPoint> = chosen(
        found = excluding(excluding, found),
        route = route,
        center = area?.center,
        limit = limit
    )
}

/**
 * Slices a saved hike's walked runs into bounded corridor search circles and merges/filters
 * OpenStreetMap places along the trail without wasting budget across pause transfers.
 */
object TrailPlaceCorridorSearch {
    const val STRETCH_RADIUS_METERS = 5_000.0
    const val MARGIN_METERS = 150.0
    const val MAXIMUM_AREAS = 12

    data class Outcome(val rows: List<TrailPlaceRow>, val outage: PlaceSearchOutage? = null) {
        val places: List<TrailPoint>
            get() = rows.map { it.place }
    }

    fun marginMeters(reaching: Double): Double = max(reaching, TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS) -
        TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS +
        MARGIN_METERS

    fun areas(
        route: List<RouteCoordinate>,
        reaching: Double = TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS
    ): List<PlaceSearchArea> = areas(
        route = route,
        stretchRadius = STRETCH_RADIUS_METERS - MARGIN_METERS,
        margin = marginMeters(reaching),
        maximumAreas = MAXIMUM_AREAS,
        ceiling = TrailPointQuery.MAXIMUM_RADIUS_METERS
    )

    fun areas(
        route: List<RouteCoordinate>,
        stretchRadius: Double,
        margin: Double,
        maximumAreas: Int,
        ceiling: Double
    ): List<PlaceSearchArea> {
        if (route.size <= 1) return emptyList()
        var radius = stretchRadius + margin
        while (true) {
            val cut = cutAreas(route, radius, margin)
            if (cut.size <= maximumAreas || radius >= ceiling) {
                return cut.take(maximumAreas)
            }
            radius = min(radius * 2.0, ceiling)
        }
    }

    suspend fun search(
        route: List<RouteCoordinate>,
        excluding: List<TrailPoint>,
        source: TrailPointSource,
        symbols: Set<TrailPlaceSymbol>,
        reaching: Double = TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS
    ): Outcome {
        val found = ArrayList<TrailPoint>()
        var firstOutage: PlaceSearchOutage? = null
        for (area in areas(route, reaching)) {
            when (val result = source.places(area, symbols)) {
                is TrailPointSearchResult.Places -> found.addAll(result.places)
                is TrailPointSearchResult.Failed -> {
                    if (firstOutage == null) firstOutage = result.outage
                    found.addAll(source.cachedPlaces(area, TrailPointQuery.MAXIMUM_STORED_RESULTS))
                }
            }
        }
        val wanted = found.filter { place -> place.symbolKind?.let { it in symbols } ?: true }
        return Outcome(
            rows = kept(wanted, route, excluding, reaching),
            outage = firstOutage
        )
    }

    fun kept(
        found: List<TrailPoint>,
        route: List<RouteCoordinate>,
        excluding: List<TrailPoint>,
        reaching: Double = TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS
    ): List<TrailPlaceRow> {
        val seen = HashSet<String>()
        val unique = found.filter { place ->
            val key = place.osm?.elementKey
            key == null || seen.add(key)
        }
        val fresh = TrailPlaceHolding.unheld(unique, excluding)
        val effectiveReach = max(reaching, TrailPlaceAnchor.TOUCHED_OFF_ROUTE_METERS)
        return TrailPlaceOrder.ordered(fresh, route).filter { row ->
            row.offRouteMeters != null && row.offRouteMeters <= effectiveReach
        }
    }

    private fun cutAreas(route: List<RouteCoordinate>, radius: Double, margin: Double): List<PlaceSearchArea> {
        val result = ArrayList<PlaceSearchArea>()
        var box: CorridorBox? = null
        val runs = route.toTrailRuns()
        val step = radius - margin
        for (run in runs.walkedRuns) {
            val points = densified(route.subList(run.first, run.last + 1), step)
            points.forEachIndexed { offset, point ->
                val current = box
                if (current == null) {
                    box = CorridorBox(point)
                } else {
                    val grown = current.including(point)
                    if (grown.radiusMeters + margin <= radius) {
                        box = grown
                    } else {
                        result.add(current.toArea(margin))
                        val seed = if (offset == 0) point else current.last
                        box = CorridorBox(seed).including(point)
                    }
                }
            }
        }
        box?.let { result.add(it.toArea(margin)) }
        return result
    }

    private fun densified(run: List<RouteCoordinate>, step: Double): List<RouteCoordinate> {
        if (step <= 0.0 || run.size < 2) return run
        val result = ArrayList<RouteCoordinate>(run.size)
        result.add(run.first())
        for (i in 0 until run.lastIndex) {
            val from = run[i]
            val to = run[i + 1]
            val length = RouteGeometry.distanceMeters(from.coordinate, to.coordinate)
            val pieces = ceil(length / step).toInt()
            if (pieces > 1) {
                for (index in 1 until pieces) {
                    val interpolated = RouteGeometry.interpolate(
                        from.coordinate,
                        to.coordinate,
                        index.toDouble() / pieces.toDouble()
                    )
                    result.add(
                        RouteCoordinate(
                            latitude = interpolated.latitude,
                            longitude = interpolated.longitude
                        )
                    )
                }
            }
            result.add(to)
        }
        return result
    }

    private class CorridorBox(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
        val last: RouteCoordinate,
        val lastLongitude: Double
    ) {
        constructor(point: RouteCoordinate) : this(
            south = point.latitude,
            west = point.longitude,
            north = point.latitude,
            east = point.longitude,
            last = point,
            lastLongitude = point.longitude
        )

        fun including(point: RouteCoordinate): CorridorBox {
            val unwrappedLon = lastLongitude +
                RouteGeometry.normalizedLongitudeDelta(point.longitude - last.longitude)
            return CorridorBox(
                south = min(south, point.latitude),
                west = min(west, unwrappedLon),
                north = max(north, point.latitude),
                east = max(east, unwrappedLon),
                last = point,
                lastLongitude = unwrappedLon
            )
        }

        val center: GeoCoordinate
            get() = GeoCoordinate(
                latitude = (south + north) / 2.0,
                longitude = RouteGeometry.normalizedLongitude((west + east) / 2.0)
            )

        val radiusMeters: Double
            get() = RouteGeometry.distanceMeters(
                GeoCoordinate(south, RouteGeometry.normalizedLongitude(west)),
                GeoCoordinate(north, RouteGeometry.normalizedLongitude(east))
            ) / 2.0

        fun toArea(margin: Double): PlaceSearchArea = PlaceSearchArea(
            center = center,
            radiusMeters = radiusMeters + margin
        )
    }
}

/**
 * Framing and area math for *Places Nearby* during an active recording.
 */
object PlacesNearbyFrame {
    const val MINIMUM_SPAN_METERS = 2_000.0
    const val PADDING = 1.4
    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val MIN_COS_LAT = 0.01
    private const val HALF_TURN = 180.0
    private const val MAX_LAT = 85.0

    fun region(line: List<GeoCoordinate>, position: GeoCoordinate?): GeoBoundingBox? {
        val points = buildList {
            addAll(line)
            if (position != null) add(position)
        }
        val focus = position ?: points.lastOrNull() ?: return null
        val framed = fitted(points)
        val area = areaOf(framed)
        return if (area.radiusMeters <= TrailPointQuery.MAXIMUM_RADIUS_METERS) {
            framed
        } else {
            fitted(listOf(focus))
        }
    }

    fun areaOf(bounds: GeoBoundingBox): PlaceSearchArea {
        val area = PlaceSearchArea.fromBounds(bounds)
        return PlaceSearchArea(center = area.center, radiusMeters = area.radiusMeters)
    }

    private fun fitted(points: List<GeoCoordinate>): GeoBoundingBox {
        val raw = GeoBoundingBox.from(points) ?: GeoBoundingBox(0.0, 0.0, 0.0, 0.0)
        val center = raw.center
        val cosLat = cos(center.latitude * PI / HALF_TURN).coerceAtLeast(MIN_COS_LAT)
        val latMeters = max((raw.northLat - raw.southLat) * METERS_PER_DEGREE_LAT * PADDING, MINIMUM_SPAN_METERS)
        val lonMeters = max(
            (raw.eastLon - raw.westLon) * METERS_PER_DEGREE_LAT * cosLat * PADDING,
            MINIMUM_SPAN_METERS
        )
        val halfLatDeg = (latMeters / 2.0) / METERS_PER_DEGREE_LAT
        val halfLonDeg = ((lonMeters / 2.0) / (METERS_PER_DEGREE_LAT * cosLat)).coerceAtMost(HALF_TURN)
        return GeoBoundingBox(
            southLat = (center.latitude - halfLatDeg).coerceIn(-MAX_LAT, MAX_LAT),
            westLon = RouteGeometry.normalizedLongitude(center.longitude - halfLonDeg),
            northLat = (center.latitude + halfLatDeg).coerceIn(-MAX_LAT, MAX_LAT),
            eastLon = RouteGeometry.normalizedLongitude(center.longitude + halfLonDeg)
        )
    }
}

/**
 * One row of *Places Nearby* with its direct distance from the hiker's origin.
 */
data class PlacesNearbyEntry(val entry: TrailPlaceAroundEntry, val meters: Double?) {
    val id: String
        get() = entry.id

    companion object {
        fun sorted(
            held: List<TrailPlaceRow>,
            candidates: List<TrailPlaceRow>,
            origin: GeoCoordinate?
        ): List<PlacesNearbyEntry> {
            val combined = held.map { TrailPlaceAroundEntry(row = it, isAdded = true) } +
                candidates.map { TrailPlaceAroundEntry(row = it, isAdded = false) }
            val measured = combined.map { item ->
                PlacesNearbyEntry(
                    entry = item,
                    meters = origin?.let { RouteGeometry.distanceMeters(it, item.row.place.coordinate) }
                )
            }
            return measured.sortedBy { it.meters ?: Double.POSITIVE_INFINITY }
        }
    }
}

/**
 * Nearby OSM place matching offered at the top of *Add Place* while recording.
 */
object NearbyPlaceSuggestions {
    const val MATCH_RADIUS_METERS = 150.0
    const val MAXIMUM_SUGGESTIONS = 8

    fun area(around: GeoCoordinate): PlaceSearchArea = PlaceSearchArea(
        center = around,
        radiusMeters = MATCH_RADIUS_METERS
    )

    fun suggestions(found: List<TrailPoint>, around: GeoCoordinate, excluding: List<TrailPoint>): List<TrailPoint> {
        val heldElements = excluding.mapNotNull { it.osm?.elementKey }.toSet()
        val seen = HashSet<String>()
        val candidates = ArrayList<Pair<TrailPoint, Double>>()
        for (place in found) {
            val elementKey = place.osm?.elementKey
            if (elementKey != null && elementKey !in heldElements && seen.add(elementKey)) {
                val distance = RouteGeometry.distanceMeters(around, place.coordinate)
                if (distance <= MATCH_RADIUS_METERS) {
                    candidates.add(place to distance)
                }
            }
        }
        return candidates
            .sortedBy { it.second }
            .take(MAXIMUM_SUGGESTIONS)
            .map { it.first }
    }
}
