package app.openhikes.core.model

/**
 * Resolved coordinate along a recorded route timeline and its time gap to the nearest recorded fix.
 */
data class TimelinePosition(val coordinate: GeoCoordinate, val secondsFromFix: Double)

/**
 * Nearest projection onto a measured (non-paused, non-inferred) recorded route leg or lone fix.
 */
data class RouteLegProjection(val coordinate: GeoCoordinate, val offRouteMeters: Double)

/**
 * Time-to-place index over a recorded route's timestamped fixes, respecting pause boundaries,
 * stationary stops, and GPS gaps.
 */
class HikePhotoTimeline private constructor(private val fixes: List<TimedFix>) {
    private data class TimedFix(val coordinate: GeoCoordinate, val timestampEpochMillis: Long, val segment: Int)

    val startEpochMillis: Long
        get() = fixes.first().timestampEpochMillis

    val endEpochMillis: Long
        get() = fixes.last().timestampEpochMillis

    val searchWindow: LongRange
        get() = (startEpochMillis - GRACE_INTERVAL_MILLIS)..(endEpochMillis + GRACE_INTERVAL_MILLIS)

    fun positionAt(epochMillis: Long): TimelinePosition? {
        val first = fixes.first()
        val last = fixes.last()
        return when {
            epochMillis <= first.timestampEpochMillis -> {
                val deltaMillis = first.timestampEpochMillis - epochMillis
                if (deltaMillis <= GRACE_INTERVAL_MILLIS) {
                    TimelinePosition(first.coordinate, deltaMillis / MILLIS_PER_SECOND)
                } else {
                    null
                }
            }
            epochMillis >= last.timestampEpochMillis -> {
                val deltaMillis = epochMillis - last.timestampEpochMillis
                if (deltaMillis <= GRACE_INTERVAL_MILLIS) {
                    TimelinePosition(last.coordinate, deltaMillis / MILLIS_PER_SECOND)
                } else {
                    null
                }
            }
            else -> positionBetweenFixes(epochMillis)
        }
    }

    private fun positionBetweenFixes(epochMillis: Long): TimelinePosition? {
        val index = findFloorIndex(epochMillis)
        val earlier = fixes[index]
        val later = fixes[index + 1]
        val sinceEarlier = epochMillis - earlier.timestampEpochMillis
        val untilLater = later.timestampEpochMillis - epochMillis
        val nearestMillis = minOf(sinceEarlier, untilLater)
        return when {
            earlier.segment != later.segment ->
                positionAcrossPause(earlier, later, sinceEarlier, untilLater, nearestMillis)
            nearestMillis > GRACE_INTERVAL_MILLIS -> null
            else -> {
                val span = (later.timestampEpochMillis - earlier.timestampEpochMillis).toDouble()
                val fraction = if (span > 0.0) sinceEarlier.toDouble() / span else 0.0
                TimelinePosition(
                    coordinate = RouteGeometry.interpolate(earlier.coordinate, later.coordinate, fraction),
                    secondsFromFix = nearestMillis / MILLIS_PER_SECOND
                )
            }
        }
    }

    private fun positionAcrossPause(
        earlier: TimedFix,
        later: TimedFix,
        sinceEarlier: Long,
        untilLater: Long,
        nearestMillis: Long
    ): TimelinePosition? {
        val nearer = if (sinceEarlier <= untilLater) earlier else later
        val spanMeters = RouteGeometry.distanceMeters(earlier.coordinate, later.coordinate)
        return if (spanMeters <= STOP_SPAN_METERS || nearestMillis <= GRACE_INTERVAL_MILLIS) {
            TimelinePosition(
                coordinate = nearer.coordinate,
                secondsFromFix = nearestMillis / MILLIS_PER_SECOND
            )
        } else {
            null
        }
    }

    private fun findFloorIndex(targetEpochMillis: Long): Int {
        var low = 0
        var high = fixes.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (fixes[mid].timestampEpochMillis <= targetEpochMillis) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return low
    }

    companion object {
        const val GRACE_INTERVAL_MILLIS = 300_000L
        const val STOP_SPAN_METERS = 100.0
        private const val MILLIS_PER_SECOND = 1000.0

        /**
         * Partitions [route] at pause boundaries before filtering out untimestamped points so a
         * pause marked on an untimestamped point still separates the timestamped legs on each side.
         */
        fun from(route: List<RouteCoordinate>): HikePhotoTimeline? {
            if (route.isEmpty()) return null
            val runs = route.toTrailRuns()
            val timed = ArrayList<TimedFix>(route.size)
            runs.pieces.forEachIndexed { segmentIndex, range ->
                for (index in range) {
                    val point = route[index]
                    val timestamp = point.timestampEpochMillis ?: continue
                    timed.add(TimedFix(point.coordinate, timestamp, segmentIndex))
                }
            }
            return if (timed.isEmpty()) {
                null
            } else {
                timed.sortBy { it.timestampEpochMillis }
                val collapsed = ArrayList<TimedFix>(timed.size)
                for (fix in timed) {
                    val previous = collapsed.lastOrNull()
                    if (previous == null || previous.timestampEpochMillis != fix.timestampEpochMillis) {
                        collapsed.add(fix)
                    }
                }
                HikePhotoTimeline(collapsed)
            }
        }
    }
}

/**
 * Measured route legs excluding paused jumps and inferred gap bridges, while retaining their
 * measured endpoint fixes as lone points.
 */
class RecordedRouteLegs private constructor(private val pieces: List<List<GeoCoordinate>>) {
    val isEmpty: Boolean
        get() = pieces.isEmpty()

    fun nearestPoint(to: GeoCoordinate): RouteLegProjection? {
        var best: RouteLegProjection? = null
        for (piece in pieces) {
            best = evaluatePiece(piece, to, best)
        }
        return best
    }

    private fun evaluatePiece(
        piece: List<GeoCoordinate>,
        target: GeoCoordinate,
        currentBest: RouteLegProjection?
    ): RouteLegProjection? {
        if (piece.size == 1) {
            val only = piece[0]
            val distance = RouteGeometry.distanceMeters(target, only)
            return if (currentBest == null || distance < currentBest.offRouteMeters) {
                RouteLegProjection(coordinate = only, offRouteMeters = distance)
            } else {
                currentBest
            }
        }
        var best = currentBest
        for (index in 0 until piece.lastIndex) {
            val start = piece[index]
            val end = piece[index + 1]
            val projection = RouteGeometry.project(target, start, end)
            if (best == null || projection.offRouteMeters < best.offRouteMeters) {
                best = RouteLegProjection(
                    coordinate = RouteGeometry.interpolate(start, end, projection.fraction),
                    offRouteMeters = projection.offRouteMeters
                )
            }
        }
        return best
    }

    companion object {
        fun from(route: List<RouteCoordinate>): RecordedRouteLegs {
            if (route.isEmpty()) return RecordedRouteLegs(emptyList())
            val cutIndices = route.indices.filter { index ->
                index > 0 && (route[index].isPauseBoundary || route[index].isInferred)
            }
            val runs = TrailRuns(pointCount = route.size, pauseIndices = cutIndices)
            val pieces = runs.pieces.map { range ->
                route.subList(range.first, range.last + 1).map { it.coordinate }
            }
            return RecordedRouteLegs(pieces)
        }
    }
}
