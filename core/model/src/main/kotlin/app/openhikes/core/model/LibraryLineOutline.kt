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
) {
    internal val hitBounds: LibraryLineHitBounds? = LibraryLineHitBounds.fromRuns(runs)
}

/**
 * Antimeridian-safe geographic bounds precomputed for [LibraryLineHitTest].
 */
internal data class LibraryLineHitBounds(
    val southLat: Double,
    val northLat: Double,
    val westLonDegrees: Double,
    val lonSpanDegrees: Double
) {
    companion object {
        private const val HALF_TURN_DEGREES = 180.0
        private const val FULL_TURN_DEGREES = 360.0

        fun fromRuns(runs: List<List<GeoCoordinate>>): LibraryLineHitBounds? {
            var south = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var mergedWest: Double? = null
            var mergedSpan = 0.0
            for (run in runs) {
                if (run.isEmpty()) continue
                var runMinLon = run[0].longitude
                var runMaxLon = run[0].longitude
                var unwrapped = run[0].longitude
                south = min(south, run[0].latitude)
                north = max(north, run[0].latitude)
                for (index in 1 until run.size) {
                    val coord = run[index]
                    south = min(south, coord.latitude)
                    north = max(north, coord.latitude)
                    unwrapped += wrapDeltaDegrees(coord.longitude - run[index - 1].longitude)
                    if (unwrapped < runMinLon) runMinLon = unwrapped
                    if (unwrapped > runMaxLon) runMaxLon = unwrapped
                }
                val runWest = normalizeAngle(runMinLon)
                val runSpan = min(FULL_TURN_DEGREES, runMaxLon - runMinLon)
                val currentWest = mergedWest
                if (currentWest == null) {
                    mergedWest = runWest
                    mergedSpan = runSpan
                } else {
                    val (nextWest, nextSpan) = mergeArcs(currentWest, mergedSpan, runWest, runSpan)
                    mergedWest = nextWest
                    mergedSpan = nextSpan
                }
            }
            val finalWest = mergedWest ?: return null
            return LibraryLineHitBounds(
                southLat = south,
                northLat = north,
                westLonDegrees = finalWest,
                lonSpanDegrees = mergedSpan
            )
        }

        internal fun normalizeAngle(degrees: Double): Double {
            val wrapped = degrees % FULL_TURN_DEGREES
            return if (wrapped < 0.0) wrapped + FULL_TURN_DEGREES else wrapped
        }

        private fun wrapDeltaDegrees(delta: Double): Double {
            var d = delta % FULL_TURN_DEGREES
            if (d > HALF_TURN_DEGREES) d -= FULL_TURN_DEGREES
            if (d < -HALF_TURN_DEGREES) d += FULL_TURN_DEGREES
            return d
        }

        private fun mergeArcs(westA: Double, spanA: Double, westB: Double, spanB: Double): Pair<Double, Double> {
            val distAB = normalizeAngle(westB - westA)
            val distBA = normalizeAngle(westA - westB)
            return when {
                spanA >= FULL_TURN_DEGREES || spanB >= FULL_TURN_DEGREES -> 0.0 to FULL_TURN_DEGREES
                distAB + spanB <= spanA -> westA to spanA
                distBA + spanA <= spanB -> westB to spanB
                distAB + spanB <= distBA + spanA -> westA to min(FULL_TURN_DEGREES, distAB + spanB)
                else -> westB to min(FULL_TURN_DEGREES, distBA + spanA)
            }
        }
    }
}

/**
 * Two-pass route thinning producing compact map outlines (`<= 256` points) split at pause
 * boundaries without ever bridging across a pause or GPX `<trkseg>` break. When a route has
 * more walked pieces than can fit under [pauseBudget] or [maximumPoints], the longest walked
 * pieces are retained in route order and shorter pieces are omitted.
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

    /**
     * Returns at most `pauseBudget + 1` contiguous walked pieces from [route] without ever
     * bridging across a pause boundary. Excess shorter pieces are omitted rather than merged.
     */
    fun keptRuns(route: List<RouteCoordinate>, pauseBudget: Int): List<IntRange> {
        val walkedRuns = route.toTrailRuns().walkedRuns
        val maxPieces = max(0, pauseBudget) + 1
        if (walkedRuns.size <= maxPieces) return walkedRuns
        val lengths = DoubleArray(walkedRuns.size) { index -> runLengthMeters(route, walkedRuns[index]) }
        val keptIndices = walkedRuns.indices
            .sortedWith { left, right ->
                val lengthOrder = lengths[right].compareTo(lengths[left])
                if (lengthOrder != 0) lengthOrder else left.compareTo(right)
            }
            .take(maxPieces)
            .sorted()
        return keptIndices.map { walkedRuns[it] }
    }

    private fun runLengthMeters(route: List<RouteCoordinate>, range: IntRange): Double {
        var total = 0.0
        for (index in range.first until range.last) {
            val leg = RouteGeometry.distanceMeters(route[index].coordinate, route[index + 1].coordinate)
            if (leg.isFinite()) total += leg
        }
        return total
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
    private const val FULL_TURN_DEGREES = 360.0
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
            val hitBounds = line.hitBounds
            if (hitBounds != null && !containsPadded(hitBounds, tap, latPad, lonPad)) continue
            val distance = distanceToRuns(tap, line.runs)
            if (distance < bestDistance) {
                bestDistance = distance
                bestHikeId = line.hikeId
            }
        }
        return bestHikeId
    }

    private fun containsPadded(
        bounds: LibraryLineHitBounds,
        tap: GeoCoordinate,
        latPad: Double,
        lonPad: Double
    ): Boolean {
        val inLat = tap.latitude >= bounds.southLat - latPad && tap.latitude <= bounds.northLat + latPad
        val paddedSpan = bounds.lonSpanDegrees + 2.0 * lonPad
        return when {
            !inLat -> false
            paddedSpan >= FULL_TURN_DEGREES -> true
            else -> {
                val paddedStart = LibraryLineHitBounds.normalizeAngle(bounds.westLonDegrees - lonPad)
                LibraryLineHitBounds.normalizeAngle(tap.longitude - paddedStart) <= paddedSpan
            }
        }
    }

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
