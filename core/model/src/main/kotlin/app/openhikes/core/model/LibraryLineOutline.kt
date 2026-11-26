package app.openhikes.core.model

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Thinned background route outline drawn beneath the selected hike on the live map.
 */
data class LibraryLine(
    val hikeId: HikeId,
    val tintHex: String,
    val runs: List<List<GeoCoordinate>>,
    val bounds: GeoBoundingBox? = GeoBoundingBox.from(runs.flatten())
)

/**
 * Two-pass route thinning producing compact map outlines (`<= 256` points) split at up to
 * [PAUSE_BUDGET] longest pause breaks so a whole library renders and hit-tests cleanly.
 */
object LibraryLineOutline {
    const val MAXIMUM_POINTS = 256
    const val PAUSE_BUDGET = 32
    const val MINIMUM_SPACING_METERS = 4.0
    const val TOLERANCE_FLOOR_METERS = 2.0
    private const val MIN_POINTS_PER_RUN = 2
    private const val ENDPOINT_PAIR = 2
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    fun runs(
        route: List<RouteCoordinate>,
        maximumPoints: Int = MAXIMUM_POINTS,
        pauseBudget: Int = PAUSE_BUDGET
    ): List<List<GeoCoordinate>> {
        if (route.size < MIN_POINTS_PER_RUN || maximumPoints < MIN_POINTS_PER_RUN) return emptyList()
        val affordablePauses = max(0, min(pauseBudget, (maximumPoints - ENDPOINT_PAIR) / ENDPOINT_PAIR))
        val spaced = keptRuns(route, affordablePauses).mapNotNull { range ->
            val points = spacedOut(route.subList(range.first, range.last + 1))
            points.takeIf { it.size >= MIN_POINTS_PER_RUN }
        }
        return fitted(spaced, maximumPoints)
    }

    fun keptRuns(route: List<RouteCoordinate>, pauseBudget: Int): List<IntRange> {
        val trailRuns = route.toTrailRuns()
        val pausedLegs = trailRuns.pausedLegs
        val limit = max(0, pauseBudget)
        return when {
            pausedLegs.size <= pauseBudget -> trailRuns.walkedRuns
            limit == 0 -> TrailRuns(pointCount = route.size, pauseIndices = emptyList()).walkedRuns
            else -> {
                val gaps = DoubleArray(pausedLegs.size) { index ->
                    val leg = pausedLegs[index]
                    val gap = RouteGeometry.distanceMeters(route[leg].coordinate, route[leg + 1].coordinate)
                    if (gap.isFinite()) gap else 0.0
                }
                val keptPauseIndices = pausedLegs.indices
                    .sortedWith { left, right ->
                        val gapOrder = gaps[right].compareTo(gaps[left])
                        if (gapOrder != 0) gapOrder else left.compareTo(right)
                    }
                    .take(limit)
                    .map { pausedLegs[it] + 1 }
                TrailRuns(pointCount = route.size, pauseIndices = keptPauseIndices).walkedRuns
            }
        }
    }

    fun spacedOut(points: List<RouteCoordinate>): List<GeoCoordinate> {
        val spacingSquared = MINIMUM_SPACING_METERS * MINIMUM_SPACING_METERS
        val kept = ArrayList<GeoCoordinate>()
        var dropped: GeoCoordinate? = null
        for (point in points) {
            val coord = point.coordinate
            if (isValidCoordinate(coord)) {
                val last = kept.lastOrNull()
                val tooClose = last != null &&
                    run {
                        val (dx, dy) = RouteGeometry.localOffset(last, coord)
                        dx * dx + dy * dy < spacingSquared
                    }
                if (tooClose) {
                    dropped = coord
                } else {
                    kept.add(coord)
                    dropped = null
                }
            }
        }
        if (dropped != null) kept.add(dropped)
        return kept
    }

    fun fitted(runs: List<List<GeoCoordinate>>, maximumPoints: Int): List<List<GeoCoordinate>> {
        if (runs.isEmpty() || runs.sumOf { it.size } <= maximumPoints) return runs

        val planes = runs.map { run ->
            val origin = run[0]
            run.map { coord ->
                val (east, north) = RouteGeometry.localOffset(origin, coord)
                PlanePoint(east, north)
            }
        }
        val keptFlags = runs.map { run ->
            BooleanArray(run.size) { idx -> idx == 0 || idx == run.lastIndex }
        }
        var spent = runs.size * ENDPOINT_PAIR
        val spans = PriorityQueue<Span>()
        planes.forEachIndexed { runIndex, plane ->
            Span.create(runIndex, 0, plane.lastIndex, plane)?.let(spans::add)
        }
        while (spent < maximumPoints && spans.peek()?.let { it.error > TOLERANCE_FLOOR_METERS } == true) {
            val span = spans.poll() ?: break
            keptFlags[span.run][span.farthest] = true
            spent += 1
            val plane = planes[span.run]
            Span.create(span.run, span.first, span.farthest, plane)?.let(spans::add)
            Span.create(span.run, span.farthest, span.last, plane)?.let(spans::add)
        }
        return runs.mapIndexed { runIdx, run ->
            val flags = keptFlags[runIdx]
            run.filterIndexed { pointIdx, _ -> flags[pointIdx] }
        }
    }

    private fun isValidCoordinate(coord: GeoCoordinate): Boolean = coord.latitude.isFinite() &&
        coord.longitude.isFinite() &&
        abs(coord.latitude) <= MAX_LATITUDE &&
        abs(coord.longitude) <= MAX_LONGITUDE

    private data class PlanePoint(val east: Double, val north: Double)

    private class Span(val error: Double, val run: Int, val first: Int, val last: Int, val farthest: Int) :
        Comparable<Span> {
        override fun compareTo(other: Span): Int {
            val errorOrder = other.error.compareTo(error)
            val runOrder = run.compareTo(other.run)
            return when {
                errorOrder != 0 -> errorOrder
                runOrder != 0 -> runOrder
                else -> first.compareTo(other.first)
            }
        }

        companion object {
            fun create(run: Int, first: Int, last: Int, plane: List<PlanePoint>): Span? {
                if (last <= first + 1) return null
                var farthestIndex = first + 1
                var largest = -1.0
                val start = plane[first]
                val end = plane[last]
                for (index in (first + 1) until last) {
                    val distance = segmentDistance(plane[index], start, end)
                    if (distance > largest) {
                        largest = distance
                        farthestIndex = index
                    }
                }
                return Span(error = largest, run = run, first = first, last = last, farthest = farthestIndex)
            }

            private fun segmentDistance(point: PlanePoint, start: PlanePoint, end: PlanePoint): Double {
                val east = end.east - start.east
                val north = end.north - start.north
                val lengthSquared = east * east + north * north
                val projection = (point.east - start.east) * east + (point.north - start.north) * north
                val along = if (lengthSquared > 0.0) (projection / lengthSquared).coerceIn(0.0, 1.0) else 0.0
                return hypot(point.east - (start.east + along * east), point.north - (start.north + along * north))
            }
        }
    }
}

/**
 * Pure-JVM hit testing for tapping a faint [LibraryLine] on the map.
 */
object LibraryLineHitTest {
    const val DEFAULT_TAP_TOLERANCE_DP = 22.0
    private const val EQUATOR_METERS_PER_DP_ZOOM_0 = 156_543.03392
    private const val HALF_TURN_DEGREES = 180.0
    private const val MIN_COSINE = 0.01
    private const val ZOOM_BASE = 2.0

    fun toleranceMeters(latitude: Double, zoom: Double, toleranceDp: Double = DEFAULT_TAP_TOLERANCE_DP): Double {
        val cosLat = max(MIN_COSINE, abs(cos(latitude * PI / HALF_TURN_DEGREES)))
        val metersPerDp = EQUATOR_METERS_PER_DP_ZOOM_0 * cosLat / ZOOM_BASE.pow(zoom)
        return metersPerDp * toleranceDp
    }

    /**
     * Returns the nearest [LibraryLine.hikeId] within [toleranceDp] screen dp of [tap] at [zoom],
     * or `null` if no line is close enough. Equal distances prefer the earlier (newest) line.
     */
    fun nearestHikeId(
        tap: GeoCoordinate,
        zoom: Double,
        lines: List<LibraryLine>,
        toleranceDp: Double = DEFAULT_TAP_TOLERANCE_DP
    ): HikeId? {
        if (lines.isEmpty()) return null
        val maxDistanceMeters = toleranceMeters(tap.latitude, zoom, toleranceDp)
        val latPad = maxDistanceMeters / RouteGeometry.METERS_PER_DEGREE_LATITUDE
        val cosLat = max(MIN_COSINE, abs(cos(tap.latitude * PI / HALF_TURN_DEGREES)))
        val lonPad = latPad / cosLat

        var bestHikeId: HikeId? = null
        var bestDistance = maxDistanceMeters
        for (line in lines) {
            val bounds = line.bounds
            if (bounds != null && !containsPadded(bounds, tap, latPad, lonPad)) continue
            val distance = distanceToRuns(tap, line.runs)
            if (distance < bestDistance) {
                bestDistance = distance
                bestHikeId = line.hikeId
            }
        }
        return bestHikeId
    }

    private fun containsPadded(bounds: GeoBoundingBox, tap: GeoCoordinate, latPad: Double, lonPad: Double): Boolean =
        tap.latitude >= bounds.southLat - latPad &&
            tap.latitude <= bounds.northLat + latPad &&
            tap.longitude >= bounds.westLon - lonPad &&
            tap.longitude <= bounds.eastLon + lonPad

    private fun distanceToRuns(tap: GeoCoordinate, runs: List<List<GeoCoordinate>>): Double {
        var best = Double.POSITIVE_INFINITY
        for (run in runs) {
            for (index in 0 until run.lastIndex) {
                val off = RouteGeometry.project(tap, run[index], run[index + 1]).offRouteMeters
                if (off < best) best = off
            }
        }
        return best
    }
}
