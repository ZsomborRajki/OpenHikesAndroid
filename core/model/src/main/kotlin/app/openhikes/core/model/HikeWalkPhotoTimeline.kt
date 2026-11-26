package app.openhikes.core.model

/**
 * Time-and-coverage projection over a single finished [HikeWalk] matching a hike's current route
 * revision, used to match photos taken while following an untimestamped or re-walked trail.
 */
class HikeWalkPhotoTimeline private constructor(
    val walkId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val ranges: List<ClosedFloatingPointRange<Double>>,
    val walkedTowardsStart: Boolean
) {
    private val coveredMeters: Double = ranges.sumOf { it.endInclusive - it.start }

    val searchWindow: LongRange
        get() {
            val grace = HikePhotoTimeline.GRACE_INTERVAL_MILLIS
            return (startedAtEpochMillis - grace)..(endedAtEpochMillis + grace)
        }

    fun distanceAlongRoute(atEpochMillis: Long): Double? {
        if (atEpochMillis !in searchWindow || ranges.isEmpty()) return null
        val span = (endedAtEpochMillis - startedAtEpochMillis).toDouble()
        val fraction = if (span > 0.0) {
            ((atEpochMillis - startedAtEpochMillis).toDouble() / span).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val walkedMeters = coveredMeters * fraction
        return if (walkedTowardsStart) {
            distanceDescending(walkedMeters)
        } else {
            distanceAscending(walkedMeters)
        }
    }

    fun covers(distanceAlongRouteMeters: Double): Boolean {
        if (!distanceAlongRouteMeters.isFinite()) return false
        return ranges.any { range ->
            distanceAlongRouteMeters >= range.start - COVERAGE_SLACK_METERS &&
                distanceAlongRouteMeters <= range.endInclusive + COVERAGE_SLACK_METERS
        }
    }

    private fun distanceAscending(walkedMeters: Double): Double {
        var remaining = walkedMeters
        for (range in ranges) {
            val length = range.endInclusive - range.start
            if (remaining <= length) {
                return range.start + remaining
            }
            remaining -= length
        }
        return ranges.last().endInclusive
    }

    private fun distanceDescending(walkedMeters: Double): Double {
        var remaining = walkedMeters
        for (range in ranges.asReversed()) {
            val length = range.endInclusive - range.start
            if (remaining <= length) {
                return range.endInclusive - remaining
            }
            remaining -= length
        }
        return ranges.first().start
    }

    companion object {
        const val COVERAGE_SLACK_METERS = 100.0

        private val WalkComparator: Comparator<HikeWalk> =
            compareBy<HikeWalk> { it.startedAtEpochMillis }.thenBy { it.id }

        fun from(walk: HikeWalk, routeRevision: String): HikeWalkPhotoTimeline? {
            val isEligible = walk.isAlong(routeRevision) && walk.endedAtEpochMillis >= walk.startedAtEpochMillis
            val coverage = if (isEligible) walk.coverage else null
            val validRanges = coverage?.ranges?.filter { it.endInclusive > it.start }.orEmpty()
            return if (coverage == null || validRanges.isEmpty()) {
                null
            } else {
                val towardsStart = coverage.walkedTowardsEnd.isEmpty() && coverage.walkedTowardsStart.isNotEmpty()
                HikeWalkPhotoTimeline(
                    walkId = walk.id,
                    startedAtEpochMillis = walk.startedAtEpochMillis,
                    endedAtEpochMillis = walk.endedAtEpochMillis,
                    ranges = validRanges,
                    walkedTowardsStart = towardsStart
                )
            }
        }

        fun allMatching(walks: List<HikeWalk>, routeRevision: String): List<HikeWalkPhotoTimeline> =
            walks.sortedWith(WalkComparator).mapNotNull { from(it, routeRevision) }
    }
}
