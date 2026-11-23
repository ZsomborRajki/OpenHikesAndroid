package app.openhikes.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * Policy constants governing trail-following coverage, turn deadbands, auto-finish,
 * abandonment, and periodic sidecar persistence.
 */
object TrailWalkPolicy {
    const val GAP_BOUND_METERS = 500.0
    const val MINIMUM_COVERAGE_METERS = 100.0
    const val MINIMUM_TURN_METERS = RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS
    const val REACHED_END_FRACTION = 0.95
    const val REACHED_END_PROXIMITY_METERS = 50.0
    const val ABANDON_AFTER_MILLIS = 6L * 3600L * 1000L
    const val STALE_AT_LAUNCH_AFTER_MILLIS = 24L * 3600L * 1000L
    const val PERSIST_INTERVAL_MILLIS = 45_000L

    fun hasReachedEnd(coveredFraction: Double, distanceToNearestEndMeters: Double): Boolean =
        coveredFraction >= REACHED_END_FRACTION && distanceToNearestEndMeters <= REACHED_END_PROXIMITY_METERS
}

/**
 * Why a walk ended, stored by stable [storedId] for forward-compatible decoding.
 */
@Serializable
enum class TrailWalkEndReason(val storedId: String) {
    ABANDONED("abandoned"),
    ENDED("ended"),
    REACHED_END("reachedEnd"),
    RECORDED("recorded"),
    ROUTE_CHANGED("routeChanged");

    companion object {
        fun fromStoredId(storedId: String?): TrailWalkEndReason? = entries.firstOrNull { it.storedId == storedId }
    }
}

/**
 * Whether an active trail walk is currently accruing coverage or paused.
 */
@Serializable
enum class TrailWalkPhase(val storedId: String) {
    FOLLOWING("following"),
    PAUSED("paused");

    companion object {
        fun fromStoredId(storedId: String?): TrailWalkPhase =
            entries.firstOrNull { it.storedId == storedId } ?: FOLLOWING
    }
}

/**
 * Direction along a stored route's distance axis.
 */
@Serializable
enum class TrailWalkDirection(val storedId: String) {
    TOWARDS_END("towardsEnd"),
    TOWARDS_START("towardsStart");

    companion object {
        fun fromStoredId(storedId: String?): TrailWalkDirection? = entries.firstOrNull { it.storedId == storedId }
    }
}

/**
 * Directed union of along-route stretches covered by a walk.
 *
 * Each pair `[start, end]` in [intervals] encodes direction in its ordering: `start <= end`
 * was walked towards the route's stored end, while `end < start` was walked back towards the start.
 * Merging happens per direction so out-and-back legs preserve climb in both directions while
 * [ranges] and [coveredMeters] measure unique ground covered once.
 */
@Serializable
data class TrailWalkCoverage(
    val intervals: List<Double> = emptyList(),
    val furthestDistanceMeters: Double = 0.0,
    val lastMatchedDistance: Double? = null,
    private val directionId: String? = null,
    private val furthestThisWayMeters: Double? = null,
    private val furthestThisWayAccuracyMeters: Double? = null
) {
    val direction: TrailWalkDirection?
        get() = TrailWalkDirection.fromStoredId(directionId)

    val ranges: List<ClosedFloatingPointRange<Double>>
        get() {
            val towardsStart = stretches(intervals, back = true)
            val towardsEnd = stretches(intervals, back = false)
            if (towardsStart.isEmpty()) return towardsEnd
            val combined = (towardsEnd + towardsStart).sortedBy { it.start }
            val union = ArrayList<ClosedFloatingPointRange<Double>>(combined.size)
            for (stretch in combined) {
                val last = union.lastOrNull()
                if (last != null && stretch.start <= last.endInclusive) {
                    union[union.lastIndex] = last.start..max(last.endInclusive, stretch.endInclusive)
                } else {
                    union.add(stretch)
                }
            }
            return union
        }

    val walkedTowardsEnd: List<ClosedFloatingPointRange<Double>>
        get() = filterTurns(
            stretches = stretches(intervals, back = false),
            against = stretches(intervals, back = true)
        )

    val walkedTowardsStart: List<ClosedFloatingPointRange<Double>>
        get() = filterTurns(
            stretches = stretches(intervals, back = true),
            against = stretches(intervals, back = false)
        )

    val coveredMeters: Double
        get() = ranges.sumOf { it.endInclusive - it.start }

    val walkedMeters: Double
        get() = (walkedTowardsEnd + walkedTowardsStart).sumOf { it.endInclusive - it.start }

    val meetsMinimum: Boolean
        get() = coveredMeters >= TrailWalkPolicy.MINIMUM_COVERAGE_METERS

    fun fractionComplete(routeDistanceMeters: Double): Double? {
        if (!routeDistanceMeters.isFinite() || routeDistanceMeters <= 0.0) return null
        return (coveredMeters / routeDistanceMeters).coerceIn(0.0, 1.0)
    }

    fun withRecordedMatch(distanceMeters: Double, accuracyMeters: Double = 0.0): TrailWalkCoverage {
        if (!distanceMeters.isFinite()) return this
        val safeAccuracy = if (accuracyMeters.isFinite()) max(0.0, accuracyMeters) else 0.0
        val nextFurthest = max(furthestDistanceMeters, distanceMeters)
        val (nextDirectionId, nextReached, nextReachedAccuracy) = resolveDirectionUpdate(
            distanceMeters = distanceMeters,
            accuracyMeters = safeAccuracy
        )
        val last = lastMatchedDistance
        val nextIntervals = if (
            last != null &&
            last != distanceMeters &&
            abs(distanceMeters - last) <= TrailWalkPolicy.GAP_BOUND_METERS
        ) {
            insertDirectedStretch(intervals, from = last, to = distanceMeters)
        } else {
            intervals
        }
        return copy(
            intervals = nextIntervals,
            furthestDistanceMeters = nextFurthest,
            lastMatchedDistance = distanceMeters,
            directionId = nextDirectionId,
            furthestThisWayMeters = nextReached,
            furthestThisWayAccuracyMeters = nextReachedAccuracy
        )
    }

    fun withBrokenContinuity(): TrailWalkCoverage = copy(
        lastMatchedDistance = null,
        furthestThisWayMeters = null,
        furthestThisWayAccuracyMeters = null
    )

    private fun resolveDirectionUpdate(
        distanceMeters: Double,
        accuracyMeters: Double
    ): Triple<String?, Double, Double> {
        val reached = furthestThisWayMeters ?: return Triple(directionId, distanceMeters, accuracyMeters)
        val step = distanceMeters - reached
        val way = when {
            step == 0.0 -> direction ?: TrailWalkDirection.TOWARDS_END
            step < 0.0 -> TrailWalkDirection.TOWARDS_START
            else -> TrailWalkDirection.TOWARDS_END
        }
        val requiredTurn = turnMeters(furthestThisWayAccuracyMeters ?: 0.0, accuracyMeters)
        return if (way == direction || abs(step) >= requiredTurn) {
            Triple(way.storedId, distanceMeters, accuracyMeters)
        } else {
            Triple(directionId, reached, furthestThisWayAccuracyMeters ?: 0.0)
        }
    }

    companion object {
        fun fromStored(intervals: List<Double>, furthestDistanceMeters: Double): TrailWalkCoverage {
            var merged: List<Double> = emptyList()
            for ((start, end) in pairs(intervals)) {
                merged = insertDirectedStretch(merged, from = start, to = end)
            }
            val resolvedFurthest = max(furthestDistanceMeters, merged.maxOrNull() ?: 0.0)
            return TrailWalkCoverage(
                intervals = merged,
                furthestDistanceMeters = resolvedFurthest
            )
        }

        private fun turnMeters(reachedAccuracy: Double, accuracy: Double): Double = min(
            TrailWalkPolicy.GAP_BOUND_METERS,
            max(TrailWalkPolicy.MINIMUM_TURN_METERS, reachedAccuracy + accuracy)
        )

        private fun pairs(intervals: List<Double>): List<Pair<Double, Double>> {
            val pairCount = intervals.size / 2
            if (pairCount == 0) return emptyList()
            val result = ArrayList<Pair<Double, Double>>(pairCount)
            for (index in 0 until pairCount) {
                val start = intervals[index * 2]
                val end = intervals[index * 2 + 1]
                if (start.isFinite() && end.isFinite()) {
                    result.add(start to end)
                }
            }
            return result
        }

        private fun stretches(intervals: List<Double>, back: Boolean): List<ClosedFloatingPointRange<Double>> =
            pairs(intervals).mapNotNull { (start, end) ->
                if ((end < start) != back) {
                    null
                } else {
                    min(start, end)..max(start, end)
                }
            }

        private fun filterTurns(
            stretches: List<ClosedFloatingPointRange<Double>>,
            against: List<ClosedFloatingPointRange<Double>>
        ): List<ClosedFloatingPointRange<Double>> {
            val minimum = TrailWalkPolicy.MINIMUM_TURN_METERS
            return stretches.filter { stretch ->
                val length = stretch.endInclusive - stretch.start
                length >= minimum ||
                    against.none { outer ->
                        outer.endInclusive - outer.start >= minimum &&
                            outer.start <= stretch.start &&
                            stretch.endInclusive <= outer.endInclusive
                    }
            }
        }

        private fun insertDirectedStretch(existing: List<Double>, from: Double, to: Double): List<Double> {
            val walksBack = to < from
            var mergedStart = min(from, to)
            var mergedEnd = max(from, to)
            val kept = ArrayList<Double>(existing.size + 2)
            for (back in listOf(false, true)) {
                var placed = back != walksBack
                for (stretch in stretches(existing, back = back)) {
                    when {
                        back != walksBack || stretch.endInclusive < mergedStart -> appendPair(kept, stretch, back)
                        stretch.start > mergedEnd -> {
                            if (!placed) {
                                appendPair(kept, mergedStart..mergedEnd, back)
                                placed = true
                            }
                            appendPair(kept, stretch, back)
                        }
                        else -> {
                            mergedStart = min(mergedStart, stretch.start)
                            mergedEnd = max(mergedEnd, stretch.endInclusive)
                        }
                    }
                }
                if (!placed) {
                    appendPair(kept, mergedStart..mergedEnd, back)
                }
            }
            return kept
        }

        private fun appendPair(target: MutableList<Double>, stretch: ClosedFloatingPointRange<Double>, back: Boolean) {
            if (back) {
                target.add(stretch.endInclusive)
                target.add(stretch.start)
            } else {
                target.add(stretch.start)
                target.add(stretch.endInclusive)
            }
        }
    }
}

/**
 * Device-local snapshot of a trail walk still in progress, persisted to `HikeLocalState` at
 * milestones and periodic cadence so process death never loses progress.
 */
@Serializable
data class TrailWalkRecord(
    val hikeId: String,
    val startedAtEpochMillis: Long,
    val coverage: TrailWalkCoverage = TrailWalkCoverage(),
    val bankedActiveSeconds: Double = 0.0,
    val phaseId: String = TrailWalkPhase.FOLLOWING.storedId,
    val phaseChangedAtEpochMillis: Long = startedAtEpochMillis,
    val lastMatchedAtEpochMillis: Long? = startedAtEpochMillis,
    val lastFollowedDistanceMeters: Double? = null,
    val routeDistanceMeters: Double,
    val routeRevision: String? = null
) {
    val phase: TrailWalkPhase
        get() = TrailWalkPhase.fromStoredId(phaseId)

    val lastActivityAtEpochMillis: Long
        get() = lastMatchedAtEpochMillis ?: startedAtEpochMillis

    val coveredFraction: Double
        get() = coverage.fractionComplete(routeDistanceMeters) ?: 0.0

    fun activeSeconds(nowEpochMillis: Long): Double {
        if (phase != TrailWalkPhase.FOLLOWING) return bankedActiveSeconds
        val deltaSeconds = max(0L, nowEpochMillis - phaseChangedAtEpochMillis) / MILLIS_PER_SECOND
        return bankedActiveSeconds + deltaSeconds
    }

    fun pausedAt(nowEpochMillis: Long): TrailWalkRecord {
        if (phase != TrailWalkPhase.FOLLOWING) return this
        return copy(
            bankedActiveSeconds = activeSeconds(nowEpochMillis),
            phaseId = TrailWalkPhase.PAUSED.storedId,
            phaseChangedAtEpochMillis = nowEpochMillis,
            coverage = coverage.withBrokenContinuity()
        )
    }

    fun resumedAt(nowEpochMillis: Long): TrailWalkRecord {
        if (phase != TrailWalkPhase.PAUSED) return this
        return copy(
            phaseId = TrailWalkPhase.FOLLOWING.storedId,
            phaseChangedAtEpochMillis = nowEpochMillis,
            lastMatchedAtEpochMillis = nowEpochMillis
        )
    }

    fun reachesEnd(atMatchDistanceMeters: Double): Boolean {
        val distanceToNearestEnd = max(0.0, min(atMatchDistanceMeters, routeDistanceMeters - atMatchDistanceMeters))
        return TrailWalkPolicy.hasReachedEnd(
            coveredFraction = coveredFraction,
            distanceToNearestEndMeters = distanceToNearestEnd
        )
    }

    fun isAbandoned(nowEpochMillis: Long): Boolean = phase == TrailWalkPhase.FOLLOWING &&
        (nowEpochMillis - lastActivityAtEpochMillis) > TrailWalkPolicy.ABANDON_AFTER_MILLIS

    fun isStaleAtLaunch(nowEpochMillis: Long): Boolean =
        (nowEpochMillis - lastActivityAtEpochMillis) > TrailWalkPolicy.STALE_AT_LAUNCH_AFTER_MILLIS

    fun isAlong(route: List<RouteCoordinate>): Boolean = routeRevision == null || isAlongRevision(routeRevision(route))

    fun isAlongRevision(revision: String): Boolean = routeRevision == null || routeRevision == revision

    fun stampedAlong(route: List<RouteCoordinate>): TrailWalkRecord =
        if (routeRevision != null) this else copy(routeRevision = routeRevision(route))

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0
        private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L // 0x100000001b3
        private const val REVISION_RADIX = 16

        /**
         * Stable 64-bit hash over route coordinates and pause boundaries (excluding elevation/time)
         * so edits that shift distances along the route invalidate stale walks cleanly.
         */
        fun routeRevision(route: List<RouteCoordinate>): String {
            var hash = FNV_OFFSET_BASIS
            route.forEachIndexed { index, point ->
                hash = mixLong(hash, point.latitude.toRawBits())
                hash = mixLong(hash, point.longitude.toRawBits())
                if (index > 0 && point.isPauseBoundary) {
                    val boundaryId = point.boundary?.name ?: "PAUSED"
                    for (ch in boundaryId) {
                        hash = (hash xor ch.code.toLong()) * FNV_PRIME
                    }
                }
            }
            return hash.toULong().toString(REVISION_RADIX)
        }

        private fun mixLong(seed: Long, value: Long): Long {
            var hash = seed
            for (shift in 0 until Long.SIZE_BITS step Byte.SIZE_BITS) {
                hash = (hash xor ((value ushr shift) and BYTE_MASK)) * FNV_PRIME
            }
            return hash
        }

        private const val BYTE_MASK = 0xFFL
    }
}

/**
 * Proposed automatic walk awaiting confirmed outward movement along the route before starting.
 */
data class PendingWalkStart(
    val record: TrailWalkRecord,
    private val originMeters: Double,
    private val gapThroughEndsMeters: Double?,
    private val setOffAtEpochMillis: Long,
    private val straightAlong: Departure = Departure(),
    private val shorterWay: Departure = Departure()
) {
    val hikeId: String
        get() = record.hikeId

    val isConfirmed: Boolean
        get() = (straightAlong.isConfirmed || shorterWay.isConfirmed) && record.coverage.meetsMinimum

    val confirmedRecord: TrailWalkRecord
        get() = record.copy(
            startedAtEpochMillis = setOffAtEpochMillis,
            phaseChangedAtEpochMillis = setOffAtEpochMillis
        )

    fun isExpired(nowEpochMillis: Long): Boolean =
        (nowEpochMillis - record.lastActivityAtEpochMillis) > EXPIRES_AFTER_MILLIS

    fun withRecordedMatch(distanceMeters: Double, atEpochMillis: Long): PendingWalkStart {
        if (atEpochMillis < record.lastActivityAtEpochMillis) return this
        val nextRecord = record.copy(
            coverage = record.coverage.withRecordedMatch(distanceMeters),
            lastMatchedAtEpochMillis = atEpochMillis,
            lastFollowedDistanceMeters = distanceMeters
        )
        val along = abs(distanceMeters - originMeters)
        val shorter = gapThroughEndsMeters?.let { gap ->
            min(along, record.routeDistanceMeters - along + gap)
        } ?: along
        return if (shorter <= STILL_RADIUS_METERS) {
            copy(
                record = nextRecord,
                setOffAtEpochMillis = atEpochMillis,
                straightAlong = Departure(),
                shorterWay = Departure()
            )
        } else {
            copy(
                record = nextRecord,
                straightAlong = straightAlong.withRecorded(along),
                shorterWay = shorterWay.withRecorded(shorter)
            )
        }
    }

    data class Departure(val meters: Double = 0.0, val steps: Int = 0) {
        val isConfirmed: Boolean
            get() = meters >= CONFIRMING_METERS && steps >= CONFIRMING_STEPS

        fun withRecorded(fromOriginMeters: Double): Departure =
            if (fromOriginMeters > meters) Departure(meters = fromOriginMeters, steps = steps + 1) else this
    }

    companion object {
        const val CONFIRMING_METERS = TrailWalkPolicy.MINIMUM_COVERAGE_METERS
        const val STILL_RADIUS_METERS = CONFIRMING_METERS / 2.0
        const val CONFIRMING_STEPS = 2
        const val EXPIRES_AFTER_MILLIS = 30L * 60L * 1000L

        fun start(
            hikeId: String,
            routeDistanceMeters: Double,
            endsApartMeters: Double,
            routeRevision: String?,
            distanceMeters: Double,
            atEpochMillis: Long
        ): PendingWalkStart {
            val initialRecord = TrailWalkRecord(
                hikeId = hikeId,
                routeDistanceMeters = routeDistanceMeters,
                startedAtEpochMillis = atEpochMillis,
                routeRevision = routeRevision,
                coverage = TrailWalkCoverage().withRecordedMatch(distanceMeters),
                lastFollowedDistanceMeters = distanceMeters
            )
            val fromNearerEnd = min(distanceMeters, routeDistanceMeters - distanceMeters)
            val gap = if (fromNearerEnd <= STILL_RADIUS_METERS) endsApartMeters else null
            return PendingWalkStart(
                record = initialRecord,
                originMeters = distanceMeters,
                gapThroughEndsMeters = gap,
                setOffAtEpochMillis = atEpochMillis
            )
        }

        fun endsApartMeters(profile: RouteProfile): Double {
            val start = profile.coordinateAt(0.0)
            val end = profile.coordinateAt(profile.totalDistanceMeters)
            return if (start == null || end == null) {
                Double.POSITIVE_INFINITY
            } else {
                RouteGeometry.distanceMeters(start, end)
            }
        }
    }
}

/**
 * Remaining-time annotation placed halfway along the remaining segment of a walked route.
 */
data class WalkTimeLeftMark(val hikeId: HikeId, val coordinate: GeoCoordinate, val remainingSeconds: Double)
