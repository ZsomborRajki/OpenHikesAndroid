package app.openhikes.core.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline country coverage of a saved hike route: ordered countries crossed along walked runs
 * and whether any walked portion falls outside all listed countries.
 */
data class OfflineRouteCoverage(val crossed: List<OfflineCountry> = emptyList(), val leavesThem: Boolean = false)

/**
 * Summarized offline map readiness for a hike route.
 */
enum class OfflineRouteSaving {
    UNSAVED,
    PART,
    WHOLE
}

/**
 * Computes which [OfflineCountry] areas a hike route passes through, respecting [RouteBoundary.PAUSED]
 * breaks (never bridging pauses), testing lone fixes as points, splitting antimeridian legs, and
 * cutting walked legs at country outline edges so sparse routes never miss enclaves or bays.
 */
object OfflineRouteCoverageCalculator {
    private const val LEGS_PER_STRETCH = 64
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val INTERSECTION_SLACK = 1e-9
    private const val PARALLEL_THRESHOLD = 1e-12
    private const val MIDPOINT_FACTOR = 0.5

    fun compute(route: List<RouteCoordinate>, countries: List<OfflineCountry>): OfflineRouteCoverage {
        if (route.isEmpty()) return OfflineRouteCoverage()
        val walk = CoverageWalk(countries)
        val points = route.map { Point2D(it.longitude, it.latitude) }
        for (piece in route.toTrailRuns().pieces) {
            if (piece.first == piece.last) {
                walk.stand(points[piece.first])
                continue
            }
            walk.setOff()
            val legStarts = (piece.first until piece.last).toList()
            for (chunk in legStarts.chunked(LEGS_PER_STRETCH)) {
                val spans = chunk.flatMap { index -> buildLegSpans(points[index], points[index + 1]) }
                walk.walk(spans)
            }
        }
        return walk.result()
    }

    fun evaluateSaving(coverage: OfflineRouteCoverage, drawnCountryIds: Set<String>): OfflineRouteSaving {
        if (coverage.crossed.isEmpty()) return OfflineRouteSaving.UNSAVED
        val drawnCount = coverage.crossed.count { it.id in drawnCountryIds }
        return when {
            drawnCount == 0 -> OfflineRouteSaving.UNSAVED
            drawnCount == coverage.crossed.size && !coverage.leavesThem -> OfflineRouteSaving.WHOLE
            else -> OfflineRouteSaving.PART
        }
    }

    private fun buildLegSpans(start: Point2D, end: Point2D): List<CoverageSpan> {
        val width = end.x - start.x
        if (abs(width) <= HALF_CIRCLE_DEGREES) {
            return listOf(CoverageSpan(from = start, to = end, startsAfresh = false))
        }
        val exit = if (width < 0.0) HALF_CIRCLE_DEGREES else -HALF_CIRCLE_DEGREES
        val run = end.x + (if (width < 0.0) FULL_CIRCLE_DEGREES else -FULL_CIRCLE_DEGREES) - start.x
        val fraction = if (run == 0.0) 0.0 else (exit - start.x) / run
        val latitude = start.y + fraction * (end.y - start.y)
        return listOf(
            CoverageSpan(from = start, to = Point2D(exit, latitude), startsAfresh = false),
            CoverageSpan(from = Point2D(-exit, latitude), to = end, startsAfresh = true)
        )
    }

    private data class Point2D(val x: Double, val y: Double)

    private data class Box2D(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        fun union(other: Box2D): Box2D = Box2D(
            minX = min(minX, other.minX),
            minY = min(minY, other.minY),
            maxX = max(maxX, other.maxX),
            maxY = max(maxY, other.maxY)
        )

        fun intersects(other: Box2D): Boolean =
            minX <= other.maxX && other.minX <= maxX && minY <= other.maxY && other.minY <= maxY

        companion object {
            fun from(a: Point2D, b: Point2D): Box2D = Box2D(
                minX = min(a.x, b.x),
                minY = min(a.y, b.y),
                maxX = max(a.x, b.x),
                maxY = max(a.y, b.y)
            )

            fun from(bounds: OfflineBounds): Box2D = Box2D(
                minX = bounds.west,
                minY = bounds.south,
                maxX = bounds.east,
                maxY = bounds.north
            )
        }
    }

    private data class CoverageEdge(val from: Point2D, val to: Point2D) {
        val box: Box2D = Box2D.from(from, to)
    }

    private data class CoverageSpan(val from: Point2D, val to: Point2D, val startsAfresh: Boolean) {
        val box: Box2D = Box2D.from(from, to)

        fun pointAt(fraction: Double): Point2D = Point2D(
            x = from.x + (to.x - from.x) * fraction,
            y = from.y + (to.y - from.y) * fraction
        )

        fun cuts(edges: List<CoverageEdge>): List<Double> = edges.mapNotNull { edge ->
            if (edge.box.intersects(box)) intersectionFraction(edge) else null
        }

        private fun intersectionFraction(edge: CoverageEdge): Double? {
            val alongX = to.x - from.x
            val alongY = to.y - from.y
            val sideX = edge.to.x - edge.from.x
            val sideY = edge.to.y - edge.from.y
            val offsetX = edge.from.x - from.x
            val offsetY = edge.from.y - from.y
            val denominator = alongX * sideY - alongY * sideX
            val scale = hypot(alongX, alongY) * hypot(sideX, sideY)
            if (abs(denominator) <= PARALLEL_THRESHOLD * scale) return 0.0
            val fraction = (offsetX * sideY - offsetY * sideX) / denominator
            val onEdge = (offsetX * alongY - offsetY * alongX) / denominator
            val lower = -INTERSECTION_SLACK
            val upper = 1.0 + INTERSECTION_SLACK
            return if (fraction in lower..upper && onEdge in lower..upper) {
                fraction.coerceIn(0.0, 1.0)
            } else {
                null
            }
        }
    }

    private class CoverageWalk(private val countries: List<OfflineCountry>) {
        private val crossed = ArrayList<OfflineCountry>()
        private val seenIds = HashSet<String>()
        private var leavesThem = false
        private var knowsWhere = false
        private val edgeCache = HashMap<Int, List<CoverageEdge>>()

        fun result(): OfflineRouteCoverage = OfflineRouteCoverage(
            crossed = crossed.toList(),
            leavesThem = leavesThem
        )

        fun setOff() {
            knowsWhere = false
        }

        fun stand(point: Point2D) {
            record(countries.indices.toList(), point)
            knowsWhere = false
        }

        fun walk(spans: List<CoverageSpan>) {
            if (spans.isEmpty()) return
            var stretchBox = spans.first().box
            for (index in 1 until spans.size) {
                stretchBox = stretchBox.union(spans[index].box)
            }
            val near = countries.indices.filter { stretchBox.intersects(Box2D.from(countries[it].bounds)) }
            val nearEdges = near.flatMap { idx -> edgesOf(idx).filter { it.box.intersects(stretchBox) } }
            for (span in spans) {
                if (span.startsAfresh) knowsWhere = false
                val cuts = span.cuts(nearEdges)
                if (cuts.isNotEmpty()) {
                    val sorted = (listOf(0.0) + cuts + listOf(1.0)).sorted()
                    for (i in 0 until sorted.lastIndex) {
                        val start = sorted[i]
                        val end = sorted[i + 1]
                        if (end > start) {
                            record(near, span.pointAt((start + end) * MIDPOINT_FACTOR))
                        }
                    }
                } else if (!knowsWhere) {
                    record(near, span.pointAt(MIDPOINT_FACTOR))
                }
            }
        }

        private fun record(candidates: List<Int>, point: Point2D) {
            val inside = candidates.filter { countries[it].contains(longitude = point.x, latitude = point.y) }
            knowsWhere = true
            if (inside.isEmpty()) leavesThem = true
            for (index in inside) {
                val country = countries[index]
                if (seenIds.add(country.id)) {
                    crossed.add(country)
                }
            }
        }

        private fun edgesOf(index: Int): List<CoverageEdge> = edgeCache.getOrPut(index) {
            val country = countries[index]
            val rings = ArrayList<List<Point2D>>(country.outline.size + 1)
            for (rawRing in country.outline) {
                val pts = rawRing.mapNotNull { if (it.size >= 2) Point2D(it[0], it[1]) else null }
                if (pts.isNotEmpty()) rings.add(pts)
            }
            val b = country.bounds
            rings.add(
                listOf(
                    Point2D(b.west, b.south),
                    Point2D(b.east, b.south),
                    Point2D(b.east, b.north),
                    Point2D(b.west, b.north)
                )
            )
            rings.flatMap { ring ->
                val result = ArrayList<CoverageEdge>(ring.size)
                var prev = ring.last()
                for (pt in ring) {
                    if (prev != pt) result.add(CoverageEdge(prev, pt))
                    prev = pt
                }
                result
            }
        }
    }
}
