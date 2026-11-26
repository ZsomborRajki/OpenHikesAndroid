package app.openhikes.core.model

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Node in a compact, cacheable OSM walking/connector graph.
 */
@Serializable
data class TrailGraphNode(val id: Long, val latitude: Double, val longitude: Double) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)
}

/**
 * Stable identifier for one segment of an OpenStreetMap way.
 */
@Serializable
data class TrailGraphEdgeId(@SerialName("wayID") val wayId: Long, val segmentIndex: Int)

/**
 * Directed-endpoint undirected edge in an OSM [TrailGraph], retaining highway & trail metadata.
 */
@Serializable
data class TrailGraphEdge(
    val id: TrailGraphEdgeId,
    @SerialName("fromNodeID") val fromNodeId: Long,
    @SerialName("toNodeID") val toNodeId: Long,
    val lengthMeters: Double,
    val name: String? = null,
    val hikingRouteName: String? = null,
    val sacScale: String? = null,
    val trailVisibility: String? = null,
    val access: String? = null,
    val surface: String? = null,
    val tracktype: String? = null,
    val highway: String? = null
) {
    val displayName: String?
        get() = hikingRouteName ?: name

    val isTrail: Boolean
        get() = TrailGraphHighway.isTrail(highway)
}

/**
 * Compact OSM trail and road-connector graph used for trail-maker routing and route editing.
 */
@Serializable
data class TrailGraph(val nodes: List<TrailGraphNode> = emptyList(), val edges: List<TrailGraphEdge> = emptyList()) {
    val isEmpty: Boolean
        get() = nodes.isEmpty() || edges.isEmpty()

    /**
     * Merges [other] into this graph deduplicating nodes and edges by identity, preferring [other].
     */
    fun merging(other: TrailGraph): TrailGraph = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> {
            val nodesById = HashMap<Long, TrailGraphNode>(nodes.size + other.nodes.size)
            for (node in nodes) nodesById[node.id] = node
            for (node in other.nodes) nodesById[node.id] = node

            val edgesById = HashMap<TrailGraphEdgeId, TrailGraphEdge>(edges.size + other.edges.size)
            for (edge in edges) edgesById[edge.id] = edge
            for (edge in other.edges) edgesById[edge.id] = edge

            TrailGraph(
                nodes = nodesById.values.sortedBy { it.id },
                edges = edgesById.values.sortedWith(
                    compareBy<TrailGraphEdge> { it.id.wayId }.thenBy { it.id.segmentIndex }
                )
            )
        }
    }

    companion object {
        val Empty = TrailGraph()
    }
}

/**
 * OpenStreetMap `highway` classifications and relative walking costs for trail-maker routing.
 */
object TrailGraphHighway {
    private const val COST_AS_TRAIL = 1.0
    private const val COST_LANE = 1.2
    private const val COST_STREET = 1.5
    private const val COST_THROUGH_ROAD = 2.0
    private const val COST_MAIN_ROAD = 3.0
    private const val COST_MAJOR_ROAD = 4.0

    val trails: Set<String> = setOf(
        "path",
        "footway",
        "track",
        "bridleway",
        "steps",
        "cycleway",
        "via_ferrata"
    )

    val connectorCosts: Map<String, Double> = mapOf(
        "pedestrian" to COST_AS_TRAIL,
        "living_street" to COST_LANE,
        "service" to COST_LANE,
        "residential" to COST_STREET,
        "unclassified" to COST_STREET,
        "road" to COST_STREET,
        "tertiary" to COST_THROUGH_ROAD,
        "tertiary_link" to COST_THROUGH_ROAD,
        "secondary" to COST_MAIN_ROAD,
        "secondary_link" to COST_MAIN_ROAD,
        "primary" to COST_MAJOR_ROAD,
        "primary_link" to COST_MAJOR_ROAD
    )

    fun isTrail(highway: String?): Boolean = highway == null || trails.contains(highway)

    fun walkingCost(highway: String?): Double = highway?.let { connectorCosts[it] } ?: COST_AS_TRAIL
}

/**
 * Slippy-map z12 region coordinate addressed by [TrailGraphProvider] and `routing` archives.
 */
data class TrailGraphRegion(val zoom: Int, val x: Int, val y: Int) {
    fun toTileAddress(): TileAddress = TileAddress(z = zoom, x = x, y = y)

    fun toGeoBoundingBox(): GeoBoundingBox = toTileAddress().toGeoBoundingBox()
}

/**
 * Slippy-map grid and naming rules shared with `tiles.openhikes.app` `routing` PMTiles archives.
 */
object TrailGraphSquareGrid {
    const val GENERATION = 1
    const val REGION_ZOOM = 12
    const val SQUARE_PREFIX = "routing$GENERATION"
    private const val NAME_SEPARATOR = '_'
    private const val NAME_PART_COUNT = 4
    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360.0
    private const val FOUR_PI = 4.0 * kotlin.math.PI
    private const val HALF = 0.5

    fun tilesPerSide(zoom: Int = REGION_ZOOM): Int = 1 shl zoom

    fun regionContaining(coordinate: GeoCoordinate, zoom: Int = REGION_ZOOM): TrailGraphRegion = TrailGraphRegion(
        zoom = zoom,
        x = tileX(coordinate.longitude, zoom),
        y = tileY(coordinate.latitude, zoom)
    )

    fun tileX(longitude: Double, zoom: Int = REGION_ZOOM): Int {
        val count = tilesPerSide(zoom)
        val raw = floor((longitude + HALF_TURN_DEGREES) / FULL_TURN_DEGREES * count).toInt()
        return raw.coerceIn(0, count - 1)
    }

    fun tileY(latitude: Double, zoom: Int = REGION_ZOOM): Int {
        val count = tilesPerSide(zoom)
        val clamped = latitude.coerceIn(-Mercator.LATITUDE_LIMIT, Mercator.LATITUDE_LIMIT)
        val sine = sin(clamped * kotlin.math.PI / HALF_TURN_DEGREES)
        val unit = HALF - ln((1.0 + sine) / (1.0 - sine)) / FOUR_PI
        return floor(unit * count).toInt().coerceIn(0, count - 1)
    }

    fun squareName(region: TrailGraphRegion): String =
        "$SQUARE_PREFIX${NAME_SEPARATOR}z${region.zoom}$NAME_SEPARATOR${region.x}$NAME_SEPARATOR${region.y}"

    fun regionFromSquareName(name: String): TrailGraphRegion? {
        val parts = name.split(NAME_SEPARATOR)
        val validHeader = parts.size == NAME_PART_COUNT && parts[0] == SQUARE_PREFIX && parts[1] == "z$REGION_ZOOM"
        val validRange = 0 until tilesPerSide(REGION_ZOOM)
        val x = if (validHeader) parts[2].toIntOrNull()?.takeIf { it in validRange } else null
        val y = if (validHeader) parts[3].toIntOrNull()?.takeIf { it in validRange } else null
        return if (x != null && y != null) {
            TrailGraphRegion(zoom = REGION_ZOOM, x = x, y = y)
        } else {
            null
        }
    }

    /**
     * Shortest antimeridian longitude shift (`+360`, `-360`, or `0`) from [startLon] to [endLon].
     */
    fun turn(startLon: Double, endLon: Double): Double {
        val step = endLon - startLon
        return when {
            step > HALF_TURN_DEGREES -> -FULL_TURN_DEGREES
            step < -HALF_TURN_DEGREES -> FULL_TURN_DEGREES
            else -> 0.0
        }
    }
}

/**
 * Samples interior coordinates along a straight leg so long legs never skip over an intermediate
 * z12 routing graph tile.
 */
object TrailGraphCorridor {
    const val SAMPLING_INTERVAL_METERS = 2_000.0
    const val MAXIMUM_SAMPLES_PER_GAP = 32

    fun samples(from: GeoCoordinate, to: GeoCoordinate): List<GeoCoordinate> {
        val distance = RouteGeometry.distanceMeters(from, to)
        val steps = if (distance.isFinite() && distance > SAMPLING_INTERVAL_METERS) {
            min(MAXIMUM_SAMPLES_PER_GAP, (distance / SAMPLING_INTERVAL_METERS).toInt())
        } else {
            0
        }
        return if (steps <= 0) {
            emptyList()
        } else {
            List(steps) { idx ->
                RouteGeometry.interpolate(from, to, (idx + 1).toDouble() / (steps + 1).toDouble())
            }
        }
    }

    fun corridorAlong(ends: TrailLegEnds): List<GeoCoordinate> = buildList {
        add(ends.start)
        addAll(samples(ends.start, ends.end))
        add(ends.end)
    }
}

/**
 * Combined routing graph covering a corridor together with any partial outage encountered.
 */
data class AssembledTrailGraph(val graph: TrailGraph?, val outage: TrailLegOutage? = null)

/**
 * Seam providing [TrailGraph] regions covering a corridor of coordinates.
 */
interface TrailGraphProvider {
    val isAvailable: Boolean
        get() = true

    suspend fun assembledGraph(covering: List<GeoCoordinate>): AssembledTrailGraph
}
