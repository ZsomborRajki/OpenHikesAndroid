package app.openhikes.core.common.routing

import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Mercator
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphEdge
import app.openhikes.core.model.TrailGraphEdgeId
import app.openhikes.core.model.TrailGraphNode
import app.openhikes.core.model.TrailGraphSquareGrid

/**
 * Pure-JVM codec for OpenHikes `OHGS` v1 pre-built OSM routing graph square files served by
 * `tiles.openhikes.app/routing-<build>/12/<x>/<y>.bin`.
 */
data class TrailGraphSquareFile(val ways: List<Way>, val coordinateScale: Int = STANDARD_COORDINATE_SCALE) {
    data class Node(val id: Long, val latitude: Int, val longitude: Int)

    enum class Tag {
        NAME,
        HIKING_ROUTE,
        SAC_SCALE,
        TRAIL_VISIBILITY,
        ACCESS,
        SURFACE,
        TRACKTYPE;

        val bitIndex: Int
            get() = ordinal
    }

    data class Way(val id: Long, val highway: String, val tags: Map<Tag, String> = emptyMap(), val nodes: List<Node>)

    fun encode(): ByteArray = PackedEnvelope.encode(MAGIC, FORMAT_VERSION, encodeBody())

    fun encodeBody(): ByteArray {
        val strings = PackedStringTable()
        val wayBytes = PackedWriter()
        var previousWayId = 0L
        var previousNode = Node(0L, 0, 0)
        for (way in ways.sortedBy { it.id }) {
            wayBytes.writeStep(way.id - previousWayId)
            previousWayId = way.id
            wayBytes.writeVarint(strings.indexOf(way.highway))
            var tagBits = 0L
            val present = Tag.entries.filter { way.tags.containsKey(it) }
            for (tag in present) tagBits = tagBits or (1L shl tag.bitIndex)
            wayBytes.writeVarint(tagBits)
            for (tag in present) wayBytes.writeVarint(strings.indexOf(way.tags[tag].orEmpty()))
            wayBytes.writeVarint(way.nodes.size.toLong())
            for (node in way.nodes) {
                wayBytes.writeStep(node.id - previousNode.id)
                wayBytes.writeStep(node.latitude.toLong() - previousNode.latitude.toLong())
                wayBytes.writeStep(node.longitude.toLong() - previousNode.longitude.toLong())
                previousNode = node
            }
        }
        val body = PackedWriter()
        body.writeVarint(coordinateScale.toLong())
        body.writeStrings(strings)
        body.writeVarint(ways.size.toLong())
        body.writeRaw(wayBytes.toByteArray())
        return body.toByteArray()
    }

    /**
     * Converts every way crossing [bounds] into a [TrailGraph] matching iOS `TrailGraphSquareLibrary`.
     */
    fun toTrailGraphCrossing(bounds: GeoBoundingBox): TrailGraph {
        val scale = coordinateScale.toDouble()
        val nodes = HashMap<Long, TrailGraphNode>()
        val edges = ArrayList<TrailGraphEdge>()
        for (way in ways) {
            if (way.nodes.size >= 2) {
                val coords = way.nodes.map { n ->
                    GeoCoordinate(latitude = n.latitude / scale, longitude = n.longitude / scale)
                }
                if (wayCrossesBounds(coords, bounds)) {
                    appendWayEdges(way, coords, nodes, edges)
                }
            }
        }
        return TrailGraph(
            nodes = nodes.values.sortedBy { it.id },
            edges = edges.sortedWith(compareBy<TrailGraphEdge> { it.id.wayId }.thenBy { it.id.segmentIndex })
        )
    }

    private fun wayCrossesBounds(coords: List<GeoCoordinate>, bounds: GeoBoundingBox): Boolean {
        for (i in 0 until coords.lastIndex) {
            if (segmentCrossesBounds(coords[i], coords[i + 1], bounds)) return true
        }
        return false
    }

    private fun appendWayEdges(
        way: Way,
        coords: List<GeoCoordinate>,
        nodes: MutableMap<Long, TrailGraphNode>,
        edges: MutableList<TrailGraphEdge>
    ) {
        for (index in 0 until way.nodes.lastIndex) {
            val from = way.nodes[index]
            val to = way.nodes[index + 1]
            val fromCoord = coords[index]
            val toCoord = coords[index + 1]
            val length = validSegmentLengthOrNull(from.id, to.id, fromCoord, toCoord)
            if (length != null) {
                nodes[from.id] = TrailGraphNode(from.id, fromCoord.latitude, fromCoord.longitude)
                nodes[to.id] = TrailGraphNode(to.id, toCoord.latitude, toCoord.longitude)
                edges.add(buildWayEdge(way, index, from.id, to.id, length))
            }
        }
    }

    private fun validSegmentLengthOrNull(
        fromId: Long,
        toId: Long,
        fromCoord: GeoCoordinate,
        toCoord: GeoCoordinate
    ): Double? {
        val representable = fromId != toId &&
            Mercator.isRepresentable(fromCoord.latitude, fromCoord.longitude) &&
            Mercator.isRepresentable(toCoord.latitude, toCoord.longitude)
        return if (representable) {
            RouteGeometry.distanceMeters(fromCoord, toCoord).takeIf { it.isFinite() }
        } else {
            null
        }
    }

    private fun buildWayEdge(way: Way, segmentIndex: Int, fromId: Long, toId: Long, length: Double): TrailGraphEdge =
        TrailGraphEdge(
            id = TrailGraphEdgeId(wayId = way.id, segmentIndex = segmentIndex),
            fromNodeId = fromId,
            toNodeId = toId,
            lengthMeters = length,
            name = way.tags[Tag.NAME],
            hikingRouteName = way.tags[Tag.HIKING_ROUTE],
            sacScale = way.tags[Tag.SAC_SCALE],
            trailVisibility = way.tags[Tag.TRAIL_VISIBILITY],
            access = way.tags[Tag.ACCESS],
            surface = way.tags[Tag.SURFACE],
            tracktype = way.tags[Tag.TRACKTYPE],
            highway = way.highway
        )

    companion object {
        const val FORMAT_VERSION = 1
        const val STANDARD_COORDINATE_SCALE = 1_000_000
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'H'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte())
        private val TAG_MASK = (1L shl Tag.entries.size) - 1L
        private const val POLE_DEGREES = 90L
        private const val ANTIMERIDIAN_DEGREES = 180L

        fun decode(bytes: ByteArray): TrailGraphSquareFile {
            val body = PackedEnvelope.decode(bytes, MAGIC, FORMAT_VERSION)
            val reader = PackedReader(body)
            val scaleLong = reader.readVarint()
            if (scaleLong <= 0L || scaleLong > Int.MAX_VALUE.toLong()) throw PackedFileException.Corrupt
            val scale = scaleLong.toInt()
            val strings = reader.readStrings()
            val wayCount = reader.readCount()
            val cursor = GraphCursor(scaleLong)
            val ways = List(wayCount) { readWay(reader, strings, cursor) }
            if (!reader.isAtEnd) throw PackedFileException.Corrupt
            return TrailGraphSquareFile(ways = ways, coordinateScale = scale)
        }

        internal fun segmentCrossesBounds(start: GeoCoordinate, end: GeoCoordinate, bounds: GeoBoundingBox): Boolean {
            val deltaY = end.latitude - start.latitude
            val turn = TrailGraphSquareGrid.turn(start.longitude, end.longitude)
            return if (turn == 0.0) {
                clips(start, end.longitude - start.longitude, deltaY, bounds)
            } else {
                val deltaX = end.longitude + turn - start.longitude
                clips(start, deltaX, deltaY, bounds) || clips(end, -deltaX, -deltaY, bounds)
            }
        }

        private fun clips(start: GeoCoordinate, deltaX: Double, deltaY: Double, bounds: GeoBoundingBox): Boolean {
            var entering = 0.0
            var leaving = 1.0
            var inside = true
            val directions = doubleArrayOf(-deltaX, deltaX, -deltaY, deltaY)
            val distances = doubleArrayOf(
                start.longitude - bounds.westLon,
                bounds.eastLon - start.longitude,
                start.latitude - bounds.southLat,
                bounds.northLat - start.latitude
            )
            var i = 0
            while (i < directions.size && inside) {
                val direction = directions[i]
                val distance = distances[i]
                if (direction == 0.0) {
                    if (distance < 0.0) inside = false
                } else {
                    val crossing = distance / direction
                    if (direction < 0.0 && crossing > entering) entering = crossing
                    if (direction > 0.0 && crossing < leaving) leaving = crossing
                    if (entering > leaving) inside = false
                }
                i += 1
            }
            return inside
        }

        private fun readWay(reader: PackedReader, strings: List<String>, cursor: GraphCursor): Way {
            cursor.wayId += reader.readStep()
            val highway = reader.readIndexedString(strings)
            val bits = reader.readVarint()
            if ((bits and TAG_MASK.inv()) != 0L) throw PackedFileException.Corrupt
            val tags = LinkedHashMap<Tag, String>()
            for (tag in Tag.entries) {
                if ((bits and (1L shl tag.bitIndex)) != 0L) {
                    tags[tag] = reader.readIndexedString(strings)
                }
            }
            val nodeCount = reader.readCount()
            val nodes = List(nodeCount) {
                cursor.nodeId += reader.readStep()
                cursor.latitude += reader.readStep()
                cursor.longitude += reader.readStep()
                cursor.toNodeOrNull() ?: throw PackedFileException.Corrupt
            }
            return Way(id = cursor.wayId, highway = highway, tags = tags, nodes = nodes)
        }
    }

    private class GraphCursor(private val unitsPerDegree: Long) {
        var wayId: Long = 0L
        var nodeId: Long = 0L
        var latitude: Long = 0L
        var longitude: Long = 0L

        fun toNodeOrNull(): Node? {
            val pole = POLE_DEGREES * unitsPerDegree
            val antimeridian = ANTIMERIDIAN_DEGREES * unitsPerDegree
            val inGeoBounds = latitude in -pole..pole && longitude in -antimeridian..antimeridian
            val inIntBounds = latitude in Int.MIN_VALUE..Int.MAX_VALUE && longitude in Int.MIN_VALUE..Int.MAX_VALUE
            return if (inGeoBounds && inIntBounds) {
                Node(id = nodeId, latitude = latitude.toInt(), longitude = longitude.toInt())
            } else {
                null
            }
        }
    }
}
