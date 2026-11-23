package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Ascent and descent totals over a slice or directed coverage of a [RouteProfile].
 */
data class RouteClimb(val gainMeters: Double, val lossMeters: Double) {
    fun reversed(): RouteClimb = RouteClimb(gainMeters = lossMeters, lossMeters = gainMeters)
}

/**
 * Elevation gain and loss between [fromMeters] and [toMeters] along this route,
 * respecting pause boundaries so gaps never count as climb.
 */
fun RouteProfile.climb(fromMeters: Double, toMeters: Double): RouteClimb? {
    val start = fromMeters.coerceAtLeast(0.0)
    val end = toMeters.coerceAtMost(totalDistanceMeters)
    if (rawElevationSamples.size < 2 || end <= start) return null
    val accumulator = ElevationAccumulator()
    rawElevationSamples
        .asSequence()
        .filter { it.distanceMeters >= start }
        .takeWhile { it.distanceMeters <= end }
        .forEach { sample ->
            if (sample.resumesAfterPause) accumulator.endRun()
            accumulator.record(sample.elevationMeters)
        }
    return if (accumulator.hasChange) {
        RouteClimb(gainMeters = accumulator.gainMeters, lossMeters = accumulator.lossMeters)
    } else {
        null
    }
}

/**
 * Directed elevation gain and loss accrued across [coverage], counting each stretch in the
 * direction it was actually walked.
 */
fun RouteProfile.climb(coverage: TrailWalkCoverage): RouteClimb? {
    var totalGain = 0.0
    var totalLoss = 0.0
    var hasAny = false
    for (range in coverage.walkedTowardsEnd) {
        climb(range.start, range.endInclusive)?.let { slice ->
            totalGain += slice.gainMeters
            totalLoss += slice.lossMeters
            hasAny = true
        }
    }
    for (range in coverage.walkedTowardsStart) {
        climb(range.start, range.endInclusive)?.reversed()?.let { slice ->
            totalGain += slice.gainMeters
            totalLoss += slice.lossMeters
            hasAny = true
        }
    }
    return if (hasAny) RouteClimb(gainMeters = totalGain, lossMeters = totalLoss) else null
}

/**
 * Extracts polyline coordinate runs covering [ranges] along the route, splitting cleanly
 * across pause boundaries so highlighted covered stretches never bridge pauses.
 */
fun RouteProfile.coveredPolylineSegments(ranges: List<ClosedFloatingPointRange<Double>>): List<List<GeoCoordinate>> {
    if (coordinates.size < 2 || ranges.isEmpty()) return emptyList()
    val result = ArrayList<List<GeoCoordinate>>()
    for (range in ranges) {
        val startDist = range.start.coerceIn(0.0, totalDistanceMeters)
        val endDist = range.endInclusive.coerceIn(0.0, totalDistanceMeters)
        if (endDist > startDist) {
            extractRangePolylines(startDist, endDist, result)
        }
    }
    return result
}

private fun RouteProfile.extractRangePolylines(
    startDist: Double,
    endDist: Double,
    target: MutableList<List<GeoCoordinate>>
) {
    val current = ArrayList<GeoCoordinate>()
    for (index in 0 until coordinates.lastIndex) {
        if (pausedSegments.contains(index)) {
            if (current.size >= 2) target.add(current.toList())
            current.clear()
        } else {
            appendClippedSegment(index, startDist, endDist, current)
        }
    }
    if (current.size >= 2) target.add(current.toList())
}

private fun RouteProfile.appendClippedSegment(
    index: Int,
    startDist: Double,
    endDist: Double,
    current: MutableList<GeoCoordinate>
) {
    val segStart = distances[index]
    val segEnd = distances[index + 1]
    if (segEnd <= startDist || segStart >= endDist || segEnd <= segStart) return
    val clipStart = maxOf(segStart, startDist)
    val clipEnd = minOf(segEnd, endDist)
    val span = segEnd - segStart
    val pStart = RouteGeometry.interpolate(
        coordinates[index],
        coordinates[index + 1],
        (clipStart - segStart) / span
    )
    val pEnd = RouteGeometry.interpolate(
        coordinates[index],
        coordinates[index + 1],
        (clipEnd - segStart) / span
    )
    if (current.isEmpty() || current.last() != pStart) {
        current.add(pStart)
    }
    current.add(pEnd)
}

/**
 * DIN 33466 mountain hiking time estimate (4 km/h flat, 300 m/h ascent, 500 m/h descent).
 * The larger of horizontal and vertical times counts in full and the smaller counts at half.
 */
object WalkingTimeEstimate {
    const val FLAT_METERS_PER_HOUR = 4_000.0
    const val ASCENT_METERS_PER_HOUR = 300.0
    const val DESCENT_METERS_PER_HOUR = 500.0
    private const val SECONDS_PER_HOUR = 3_600.0
    private const val HALF_FACTOR = 2.0

    fun seconds(distanceMeters: Double, ascentMeters: Double, descentMeters: Double): Double {
        val horizontal = sanitized(distanceMeters) / FLAT_METERS_PER_HOUR
        val vertical = sanitized(ascentMeters) / ASCENT_METERS_PER_HOUR +
            sanitized(descentMeters) / DESCENT_METERS_PER_HOUR
        val hours = max(horizontal, vertical) + min(horizontal, vertical) / HALF_FACTOR
        return hours * SECONDS_PER_HOUR
    }

    private fun sanitized(value: Double): Double = if (value.isFinite()) max(0.0, value) else 0.0
}

/**
 * Remaining distance, time-left estimate, and midway point ahead for an active trail walk.
 *
 * Uses DIN 33466 signposts scaled by the hiker's own pace once calibrated (>= 1 km and >= 15 min),
 * clamped to 0.5x–2.0x to avoid erratic swings on short stretches.
 */
object WalkTimeLeft {
    const val CALIBRATION_METERS = 1_000.0
    const val CALIBRATION_SECONDS = 15.0 * 60.0
    const val MIN_PACE_FACTOR = 0.5
    const val MAX_PACE_FACTOR = 2.0
    private const val HALF_FACTOR = 2.0

    fun seconds(
        profile: RouteProfile,
        positionMeters: Double,
        coverage: TrailWalkCoverage,
        activeSeconds: Double
    ): Double? {
        val remainingMeters = metersAhead(
            positionMeters = positionMeters,
            coverage = coverage,
            routeMeters = profile.totalDistanceMeters
        )
        if (!remainingMeters.isFinite() || remainingMeters <= 0.0) return null
        val ahead = climbAhead(profile = profile, positionMeters = positionMeters, coverage = coverage)
        val pace = paceFactor(profile = profile, coverage = coverage, activeSeconds = activeSeconds)
        return if (ahead == null && pace == null) {
            null
        } else {
            signpostSeconds(remainingMeters, ahead) * (pace ?: 1.0)
        }
    }

    fun midwayAhead(positionMeters: Double, coverage: TrailWalkCoverage, routeMeters: Double): Double {
        val ahead = metersAhead(positionMeters, coverage, routeMeters)
        return if (isGoingToStart(positionMeters, coverage, routeMeters)) {
            positionMeters - ahead / HALF_FACTOR
        } else {
            positionMeters + ahead / HALF_FACTOR
        }
    }

    fun climbAhead(profile: RouteProfile, positionMeters: Double, coverage: TrailWalkCoverage): RouteClimb? =
        if (isGoingToStart(positionMeters, coverage, profile.totalDistanceMeters)) {
            profile.climb(fromMeters = 0.0, toMeters = positionMeters)?.reversed()
        } else {
            profile.climb(fromMeters = positionMeters, toMeters = profile.totalDistanceMeters)
        }

    fun metersAhead(positionMeters: Double, coverage: TrailWalkCoverage, routeMeters: Double): Double = max(
        0.0,
        if (isGoingToStart(positionMeters, coverage, routeMeters)) {
            positionMeters
        } else {
            routeMeters - positionMeters
        }
    )

    fun isGoingToStart(positionMeters: Double, coverage: TrailWalkCoverage, routeMeters: Double): Boolean =
        coverage.direction?.let { it == TrailWalkDirection.TOWARDS_START }
            ?: isReversed(positionMeters, coverage.ranges, routeMeters)

    fun paceFactor(profile: RouteProfile, coverage: TrailWalkCoverage, activeSeconds: Double): Double? {
        val walkedMeters = coverage.walkedMeters
        if (walkedMeters < CALIBRATION_METERS || activeSeconds < CALIBRATION_SECONDS) return null
        val expected = signpostSeconds(walkedMeters, profile.climb(coverage))
        return if (expected <= 0.0) {
            null
        } else {
            (activeSeconds / expected).coerceIn(MIN_PACE_FACTOR, MAX_PACE_FACTOR)
        }
    }

    fun isReversed(
        positionMeters: Double,
        coveredRanges: List<ClosedFloatingPointRange<Double>>,
        routeMeters: Double
    ): Boolean {
        var behind = 0.0
        var beyond = 0.0
        for (stretch in coveredRanges) {
            behind += max(0.0, min(stretch.endInclusive, positionMeters) - stretch.start)
            beyond += max(0.0, stretch.endInclusive - max(stretch.start, positionMeters))
        }
        return if (behind != beyond) {
            beyond > behind
        } else {
            positionMeters > routeMeters / HALF_FACTOR
        }
    }

    private fun signpostSeconds(meters: Double, climb: RouteClimb?): Double = WalkingTimeEstimate.seconds(
        distanceMeters = meters,
        ascentMeters = climb?.gainMeters ?: 0.0,
        descentMeters = climb?.lossMeters ?: 0.0
    )
}
