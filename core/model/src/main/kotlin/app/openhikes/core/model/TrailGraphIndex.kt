package app.openhikes.core.model

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Spatial edge index and weighted Dijkstra router over an OSM [TrailGraph].
 */
class TrailGraphIndex(graph: TrailGraph, network: Network = Network.WALKING) {
    enum class Network {
        TRAILS,
        WALKING
    }

    data class Adjacency(val nodeId: Long, val edgeIndex: Int, val cost: Double)

    data class Endpoint(val nodeId: Long, val cost: Double)

    data class EdgeEndpoints(val start: GeoCoordinate, val end: GeoCoordinate)

    data class NodePath(val nodes: List<Long>, val edgeIndices: List<Int>, val cost: Double)

    private data class NodePair(val lower: Long, val upper: Long) {
        companion object {
            fun of(first: Long, second: Long): NodePair = if (first <= second) {
                NodePair(first, second)
            } else {
                NodePair(second, first)
            }
        }
    }

    private data class HeapEntry(val cost: Double, val nodeId: Long) : Comparable<HeapEntry> {
        override fun compareTo(other: HeapEntry): Int = when {
            cost != other.cost -> cost.compareTo(other.cost)
            else -> nodeId.compareTo(other.nodeId)
        }
    }

    private data class PreviousStep(val nodeId: Long, val edgeIndex: Int)

    val nodes: Map<Long, TrailGraphNode>
    val edges: List<TrailGraphEdge>
    val edgeEndpoints: List<EdgeEndpoints>
    val edgeWeights: DoubleArray
    val adjacency: Map<Long, List<Adjacency>>
    internal val grid: EdgeGrid
    private val shortestPathCache = HashMap<NodePair, NodePath>()

    init {
        val nodeMap = HashMap<Long, TrailGraphNode>(graph.nodes.size)
        for (node in graph.nodes) nodeMap[node.id] = node

        val validEdges = ArrayList<TrailGraphEdge>(graph.edges.size)
        val endpoints = ArrayList<EdgeEndpoints>(graph.edges.size)
        for (edge in graph.edges) {
            val included = network == Network.WALKING || edge.isTrail
            val start = if (included) nodeMap[edge.fromNodeId]?.coordinate else null
            val end = if (included) nodeMap[edge.toNodeId]?.coordinate else null
            if (start != null && end != null) {
                validEdges.add(edge)
                endpoints.add(EdgeEndpoints(start, end))
            }
        }

        nodes = if (network == Network.WALKING) {
            nodeMap
        } else {
            val touched = HashSet<Long>(validEdges.size * 2)
            for (edge in validEdges) {
                touched.add(edge.fromNodeId)
                touched.add(edge.toNodeId)
            }
            nodeMap.filterKeys { touched.contains(it) }
        }
        edges = if (validEdges.size == graph.edges.size) graph.edges else validEdges
        edgeEndpoints = endpoints
        edgeWeights = DoubleArray(edges.size) { index ->
            if (network == Network.WALKING) TrailGraphHighway.walkingCost(edges[index].highway) else 1.0
        }
        grid = EdgeGrid(endpoints)

        val adj = HashMap<Long, MutableList<Adjacency>>(nodes.size)
        for (edgeIndex in edges.indices) {
            val edge = edges[edgeIndex]
            val cost = edge.lengthMeters * edgeWeights[edgeIndex]
            adj.getOrPut(edge.fromNodeId) { ArrayList(2) }.add(Adjacency(edge.toNodeId, edgeIndex, cost))
            adj.getOrPut(edge.toNodeId) { ArrayList(2) }.add(Adjacency(edge.fromNodeId, edgeIndex, cost))
        }
        adjacency = adj
    }

    fun costOfEdge(edgeIndex: Int): Double = edges[edgeIndex].lengthMeters * edgeWeights[edgeIndex]

    fun shortestPath(
        from: Long,
        to: Long,
        maximumCost: Double,
        bannedNodes: Set<Long> = emptySet(),
        bannedEdges: Set<Int> = emptySet()
    ): NodePath? {
        val endpointsValid = nodes.containsKey(from) &&
            nodes.containsKey(to) &&
            !bannedNodes.contains(from) &&
            !bannedNodes.contains(to)
        return when {
            !endpointsValid -> null
            from == to -> NodePath(nodes = listOf(from), edgeIndices = emptyList(), cost = 0.0)
            else -> lookupOrSearchPath(from, to, maximumCost, bannedNodes, bannedEdges)
        }
    }

    private fun lookupOrSearchPath(
        from: Long,
        to: Long,
        maximumCost: Double,
        bannedNodes: Set<Long>,
        bannedEdges: Set<Int>
    ): NodePath? {
        val mayUseCache = bannedNodes.isEmpty() && bannedEdges.isEmpty()
        val cacheKey = NodePair.of(from, to)
        val cached = if (mayUseCache) shortestPathCache[cacheKey] else null
        return if (cached != null) {
            orientCachedPath(cached, from, maximumCost)
        } else {
            val found = runDijkstra(from, to, maximumCost, bannedNodes, bannedEdges)
            if (mayUseCache && found != null) shortestPathCache[cacheKey] = found
            found
        }
    }

    private fun orientCachedPath(cached: NodePath, from: Long, maximumCost: Double): NodePath? = when {
        cached.cost > maximumCost -> null
        cached.nodes.firstOrNull() == from -> cached
        else -> NodePath(
            nodes = cached.nodes.asReversed(),
            edgeIndices = cached.edgeIndices.asReversed(),
            cost = cached.cost
        )
    }

    private fun runDijkstra(
        start: Long,
        end: Long,
        maximumCost: Double,
        bannedNodes: Set<Long>,
        bannedEdges: Set<Int>
    ): NodePath? {
        val distances = HashMap<Long, Double>()
        val previous = HashMap<Long, PreviousStep>()
        val frontier = PriorityQueue<HeapEntry>()
        distances[start] = 0.0
        frontier.add(HeapEntry(0.0, start))
        var reachedEnd = false

        while (frontier.isNotEmpty() && !reachedEnd) {
            val current = frontier.poll()
            val isStale = current == null || current.cost > maximumCost || current.cost != distances[current.nodeId]
            if (!isStale) {
                if (current.nodeId == end) {
                    reachedEnd = true
                } else {
                    relaxNeighbors(current, maximumCost, bannedNodes, bannedEdges, distances, previous, frontier)
                }
            }
        }
        val finalCost = distances[end] ?: return null
        return reconstructPath(start, end, previous, finalCost)
    }

    @Suppress("LongParameterList")
    private fun relaxNeighbors(
        current: HeapEntry,
        maximumCost: Double,
        bannedNodes: Set<Long>,
        bannedEdges: Set<Int>,
        distances: MutableMap<Long, Double>,
        previous: MutableMap<Long, PreviousStep>,
        frontier: PriorityQueue<HeapEntry>
    ) {
        val neighbors = adjacency[current.nodeId] ?: return
        for (neighbor in neighbors) {
            val allowed = !bannedNodes.contains(neighbor.nodeId) && !bannedEdges.contains(neighbor.edgeIndex)
            if (allowed) {
                val nextCost = current.cost + neighbor.cost
                val existing = distances[neighbor.nodeId] ?: Double.POSITIVE_INFINITY
                if (nextCost <= maximumCost && nextCost < existing) {
                    distances[neighbor.nodeId] = nextCost
                    previous[neighbor.nodeId] = PreviousStep(current.nodeId, neighbor.edgeIndex)
                    frontier.add(HeapEntry(nextCost, neighbor.nodeId))
                }
            }
        }
    }

    private fun reconstructPath(start: Long, end: Long, previous: Map<Long, PreviousStep>, cost: Double): NodePath? {
        val pathNodes = ArrayList<Long>()
        val pathEdges = ArrayList<Int>()
        var cursor = end
        pathNodes.add(cursor)
        while (cursor != start) {
            val step = previous[cursor] ?: return null
            pathEdges.add(step.edgeIndex)
            cursor = step.nodeId
            pathNodes.add(cursor)
        }
        pathNodes.reverse()
        pathEdges.reverse()
        return NodePath(nodes = pathNodes, edgeIndices = pathEdges, cost = cost)
    }

    internal class EdgeGrid(endpoints: List<EdgeEndpoints>) {
        private data class Cell(val x: Int, val y: Int)

        private val origin: GeoCoordinate
        private val cells: Map<Cell, List<Int>>
        private val reachMeters: Double
        private val alwaysScanned: List<Int>

        init {
            if (endpoints.isEmpty()) {
                origin = GeoCoordinate(0.0, 0.0)
                cells = emptyMap()
                reachMeters = 0.0
                alwaysScanned = emptyList()
            } else {
                val gridOrigin = endpoints.first().start
                val cellMap = HashMap<Cell, MutableList<Int>>()
                val longEdges = ArrayList<Int>()
                var maxReach = 0.0
                for (edgeIndex in endpoints.indices) {
                    val ep = endpoints[edgeIndex]
                    val (sx, sy) = RouteGeometry.localOffset(gridOrigin, ep.start)
                    val (ex, ey) = RouteGeometry.localOffset(gridOrigin, ep.end)
                    val halfLength = hypot(ex - sx, ey - sy) / HALF_DIVISOR
                    val cell = cellFor((sx + ex) / HALF_DIVISOR, (sy + ey) / HALF_DIVISOR)
                    if (halfLength > MAXIMUM_EDGE_REACH_METERS || cell == null) {
                        longEdges.add(edgeIndex)
                    } else {
                        maxReach = max(maxReach, halfLength)
                        cellMap.getOrPut(cell) { ArrayList(2) }.add(edgeIndex)
                    }
                }
                origin = gridOrigin
                cells = cellMap
                alwaysScanned = longEdges
                reachMeters = maxReach
            }
        }

        fun forEachEdge(near: GeoCoordinate, radiusMeters: Double, action: (Int) -> Unit) {
            for (index in alwaysScanned) action(index)
            if (cells.isEmpty()) return
            val (cx, cy) = RouteGeometry.localOffset(origin, near)
            val expanded = radiusMeters + reachMeters + GRID_MARGIN_METERS
            val minCell = cellFor(cx - expanded, cy - expanded)
            val maxCell = cellFor(cx + expanded, cy + expanded)
            if (minCell != null && maxCell != null) {
                for (x in minCell.x..maxCell.x) {
                    for (y in minCell.y..maxCell.y) {
                        val bucket = cells[Cell(x, y)] ?: continue
                        for (index in bucket) action(index)
                    }
                }
            }
        }

        private fun cellFor(x: Double, y: Double): Cell? {
            val cx = floor(x / CELL_SIZE_METERS)
            val cy = floor(y / CELL_SIZE_METERS)
            val limit = Int.MAX_VALUE.toDouble()
            val isFinite = cx.isFinite() && cy.isFinite()
            val inIntRange = abs(cx) < limit && abs(cy) < limit
            return if (isFinite && inIntRange) Cell(cx.toInt(), cy.toInt()) else null
        }

        private companion object {
            const val CELL_SIZE_METERS = 200.0
            const val GRID_MARGIN_METERS = 2.0
            const val MAXIMUM_EDGE_REACH_METERS = 400.0
            const val HALF_DIVISOR = 2.0
        }
    }
}

/**
 * Pure-JVM trail leg routing algorithm over a [TrailGraphIndex]: endpoint snapping, shortest-path
 * walking search, and up to two genuinely distinct alternative paths.
 */
object TrailGraphLegRouter {
    const val SNAP_RADIUS_METERS = 100.0
    const val MAXIMUM_DETOUR_FACTOR = 8.0
    const val MINIMUM_DETOUR_ALLOWANCE_METERS = 1_000.0
    const val MAXIMUM_LEG_METERS = 20_000.0
    const val MAXIMUM_ALTERNATIVES = 2
    const val MAXIMUM_ALTERNATIVE_STRETCH = 1.5
    const val MAXIMUM_SHARED_FRACTION = 0.7
    private const val QUARTER = 0.25
    private const val THREE_QUARTERS = 0.75

    internal data class SnapPoint(val edgeIndex: Int, val coordinate: GeoCoordinate, val offsetMeters: Double)

    private data class FoundPath(
        val path: TrailGraphIndex.NodePath,
        val fullEdgeIndices: List<Int>,
        val totalCost: Double
    )

    fun route(ends: TrailLegEnds, index: TrailGraphIndex): TrailLegRoute {
        if (ends.straightDistanceMeters > MAXIMUM_LEG_METERS) {
            return TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.TOO_FAR_APART))
        }
        val start = snapPoint(ends.start, index)
        val end = snapPoint(ends.end, index)
        return when {
            start == null || end == null ->
                TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))
            start.edgeIndex == end.edgeIndex -> routeSameEdge(ends, start, end, index)
            else -> routeAcrossEdges(ends, start, end, index)
        }
    }

    private fun routeSameEdge(
        ends: TrailLegEnds,
        start: SnapPoint,
        end: SnapPoint,
        index: TrailGraphIndex
    ): TrailLegRoute {
        val weight = index.edgeWeights[start.edgeIndex]
        val directCost = abs(end.offsetMeters - start.offsetMeters) * weight
        val betterOffRoad = if (weight > 1.0) {
            findCheapestPath(
                start = start,
                end = end,
                index = index,
                ceiling = min(budget(ends), directCost),
                bannedEdges = setOf(start.edgeIndex)
            )?.takeIf { it.totalCost < directCost }
        } else {
            null
        }
        return if (betterOffRoad != null) {
            val altPaths = findAlternatives(betterOffRoad, start, end, index)
            val shapes = (listOf(betterOffRoad.path) + altPaths).map { nodePath ->
                buildLegShape(nodePath, start, end, ends, index)
            }
            buildLegRoute(shapes)
        } else {
            buildLegRoute(listOf(buildLegShape(path = null, start = start, end = end, ends = ends, index = index)))
        }
    }

    private fun routeAcrossEdges(
        ends: TrailLegEnds,
        start: SnapPoint,
        end: SnapPoint,
        index: TrailGraphIndex
    ): TrailLegRoute {
        val best = findCheapestPath(start, end, index, budget(ends))
        return if (best == null) {
            TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))
        } else {
            val altPaths = findAlternatives(best, start, end, index)
            val shapes = (listOf(best.path) + altPaths).map { nodePath ->
                buildLegShape(nodePath, start, end, ends, index)
            }
            buildLegRoute(shapes)
        }
    }

    internal fun budget(ends: TrailLegEnds): Double =
        min(MAXIMUM_LEG_METERS, ends.straightDistanceMeters * MAXIMUM_DETOUR_FACTOR + MINIMUM_DETOUR_ALLOWANCE_METERS)

    private fun findCheapestPath(
        start: SnapPoint,
        end: SnapPoint,
        index: TrailGraphIndex,
        ceiling: Double,
        bannedEdges: Set<Int> = emptySet()
    ): FoundPath? {
        var best: FoundPath? = null
        val startEnds = endpoints(start, index).filter { it.cost == 0.0 || !bannedEdges.contains(start.edgeIndex) }
        val endEnds = endpoints(end, index).filter { it.cost == 0.0 || !bannedEdges.contains(end.edgeIndex) }
        for (from in startEnds) {
            for (to in endEnds) {
                val found = evaluateEndpointPair(start, end, from, to, index, ceiling, bannedEdges)
                if (found != null && (best == null || found.totalCost < best.totalCost)) {
                    best = found
                }
            }
        }
        return best
    }

    @Suppress("LongParameterList")
    private fun evaluateEndpointPair(
        start: SnapPoint,
        end: SnapPoint,
        from: TrailGraphIndex.Endpoint,
        to: TrailGraphIndex.Endpoint,
        index: TrailGraphIndex,
        ceiling: Double,
        bannedEdges: Set<Int>
    ): FoundPath? {
        val available = ceiling - from.cost - to.cost
        val candidate = if (available >= 0.0) {
            index.shortestPath(
                from = from.nodeId,
                to = to.nodeId,
                maximumCost = available,
                bannedEdges = bannedEdges
            )
        } else {
            null
        }
        return candidate?.let { path ->
            val fullEdges = buildList {
                if (from.cost > 0.0) add(start.edgeIndex)
                addAll(path.edgeIndices)
                if (to.cost > 0.0 && (isEmpty() || last() != end.edgeIndex)) add(end.edgeIndex)
            }
            FoundPath(path, fullEdges, from.cost + path.cost + to.cost)
        }
    }

    private fun findAlternatives(
        best: FoundPath,
        start: SnapPoint,
        end: SnapPoint,
        index: TrailGraphIndex
    ): List<TrailGraphIndex.NodePath> {
        val ceiling = best.totalCost * MAXIMUM_ALTERNATIVE_STRETCH
        val offered = mutableListOf(best)
        var keepSearching = true
        while (offered.size <= MAXIMUM_ALTERNATIVES && keepSearching) {
            val banned = HashSet<Int>()
            for (existing in offered) banned.addAll(middleEdges(existing, index))
            val candidate = if (banned.isNotEmpty()) {
                findCheapestPath(start, end, index, ceiling, bannedEdges = banned)
            } else {
                null
            }
            if (candidate != null && isDistinct(candidate, offered, index)) {
                offered.add(candidate)
            } else {
                keepSearching = false
            }
        }
        return offered.drop(1).map { it.path }
    }

    private fun middleEdges(found: FoundPath, index: TrailGraphIndex): Set<Int> {
        val edgeList = found.fullEdgeIndices
        val total = edgeList.sumOf { index.costOfEdge(it) }
        if (total <= 0.0) return emptySet()
        val low = total * QUARTER
        val high = total * THREE_QUARTERS
        var traveled = 0.0
        val banned = HashSet<Int>()
        for (edgeIdx in edgeList) {
            val edgeCost = index.costOfEdge(edgeIdx)
            val edgeEnd = traveled + edgeCost
            if (edgeEnd >= low && traveled <= high) banned.add(edgeIdx)
            traveled = edgeEnd
        }
        return banned
    }

    private fun isDistinct(candidate: FoundPath, offered: List<FoundPath>, index: TrailGraphIndex): Boolean {
        val candidateTotal = candidate.fullEdgeIndices.sumOf { index.costOfEdge(it) }
        if (candidateTotal <= 0.0) return false
        val own = candidate.fullEdgeIndices.toHashSet()
        return offered.all { existing ->
            var shared = 0.0
            for (edgeIdx in existing.fullEdgeIndices) {
                if (own.contains(edgeIdx)) shared += index.costOfEdge(edgeIdx)
            }
            shared <= candidateTotal * MAXIMUM_SHARED_FRACTION
        }
    }

    private fun endpoints(point: SnapPoint, index: TrailGraphIndex): List<TrailGraphIndex.Endpoint> {
        val edge = index.edges[point.edgeIndex]
        val weight = index.edgeWeights[point.edgeIndex]
        return listOf(
            TrailGraphIndex.Endpoint(edge.fromNodeId, point.offsetMeters * weight),
            TrailGraphIndex.Endpoint(edge.toNodeId, max(0.0, edge.lengthMeters - point.offsetMeters) * weight)
        )
    }

    private fun snapPoint(coordinate: GeoCoordinate, index: TrailGraphIndex): SnapPoint? {
        var best: SnapPoint? = null
        var bestOffRoute = Double.POSITIVE_INFINITY
        index.grid.forEachEdge(near = coordinate, radiusMeters = SNAP_RADIUS_METERS) { edgeIndex ->
            val ep = index.edgeEndpoints[edgeIndex]
            val projection = RouteGeometry.project(coordinate, ep.start, ep.end)
            if (projection.offRouteMeters <= SNAP_RADIUS_METERS && projection.offRouteMeters < bestOffRoute) {
                bestOffRoute = projection.offRouteMeters
                best = SnapPoint(
                    edgeIndex = edgeIndex,
                    coordinate = RouteGeometry.interpolate(ep.start, ep.end, projection.fraction),
                    offsetMeters = index.edges[edgeIndex].lengthMeters * projection.fraction
                )
            }
        }
        return best
    }
}

private fun buildLegRoute(shapes: List<List<GeoCoordinate>>): TrailLegRoute {
    val paths = shapes.map { shape ->
        TrailLegPath(
            coordinates = shape,
            distanceMeters = RouteGeometry.lengthMeters(shape)
        )
    }
    val primary = paths.first()
    return TrailLegRoute(
        coordinates = primary.coordinates,
        distanceMeters = primary.distanceMeters,
        snap = TrailLegSnap.Snapped,
        alternatives = paths.drop(1)
    )
}

private fun buildLegShape(
    path: TrailGraphIndex.NodePath?,
    start: TrailGraphLegRouter.SnapPoint,
    end: TrailGraphLegRouter.SnapPoint,
    ends: TrailLegEnds,
    index: TrailGraphIndex
): List<GeoCoordinate> {
    val raw = ArrayList<GeoCoordinate>((path?.nodes?.size ?: 0) + 4)
    raw.add(ends.start)
    raw.add(start.coordinate)
    if (path != null) {
        for (nodeId in path.nodes) {
            index.nodes[nodeId]?.coordinate?.let(raw::add)
        }
    }
    raw.add(end.coordinate)
    raw.add(ends.end)
    return deduplicateCoordinates(raw)
}

private fun deduplicateCoordinates(coordinates: List<GeoCoordinate>): List<GeoCoordinate> {
    if (coordinates.size <= 1) return coordinates
    val result = ArrayList<GeoCoordinate>(coordinates.size)
    for (coord in coordinates) {
        if (result.isEmpty() || result.last() != coord) result.add(coord)
    }
    return result
}
