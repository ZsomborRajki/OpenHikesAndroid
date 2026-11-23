package app.openhikes.core.model

import kotlin.math.max

/**
 * Live segment breakdown built incrementally from accepted points using the exact same rules
 * as saved routes.
 */
class RecordingSegmentLedger {
    private val builder = RouteSegments.Builder()
    private var previous: RouteCoordinate? = null
    private var distanceMeters: Double = 0.0

    val shown: List<RouteSegment>
        get() = builder.finish(distanceMeters)

    fun record(point: RecordingPoint, distanceMeters: Double) {
        val coordinate = point.toRouteCoordinate()
        val leg = previous?.let { RouteGeometry.legMeters(it, coordinate) } ?: 0.0
        builder.consume(coordinate, segmentMeters = leg)
        previous = coordinate
        this.distanceMeters = distanceMeters
    }
}

/**
 * Live distance, duration, elevation, recent speed, and segment accumulator used both during
 * recording and when replaying the journal after crash recovery or save preparation.
 */
class RecordingDistanceAccumulator {
    private data class RecentSample(val timestampEpochMillis: Long, val distanceMeters: Double)

    var distanceMeters: Double = 0.0
        private set
    var isStationary: Boolean = false
        private set
    var recordedDurationSeconds: Double = 0.0
        private set

    private val elevation = ElevationAccumulator()
    private var previous: RecordingPoint? = null
    private var movementWindowStart: RecordingPoint? = null
    private var movementWindowDistance: Double = 0.0
    private var stationaryAnchor: RecordingPoint? = null
    private var motionStationaryStartedAtMillis: Long? = null
    private val movingTime = MovingTimeAccumulator()
    private val recentWindow = ArrayDeque<RecentSample>()
    private val segmentsLedger = RecordingSegmentLedger()

    val averageSpeedMps: Double?
        get() = if (recordedDurationSeconds > 0.0) distanceMeters / recordedDurationSeconds else null

    val movingSeconds: Double
        get() = movingTime.seconds

    val recentSpeedMps: Double?
        get() {
            val anchor = recentWindow.firstOrNull() ?: return null
            val latest = recentWindow.lastOrNull() ?: return null
            val spanSeconds = (latest.timestampEpochMillis - anchor.timestampEpochMillis) / MILLIS_PER_SECOND
            if (spanSeconds < MINIMUM_RECENT_SPEED_SPAN_SECONDS) return null
            return max(0.0, latest.distanceMeters - anchor.distanceMeters) / spanSeconds
        }

    val elevationGainMeters: Double?
        get() = if (elevation.hasChange) elevation.gainMeters else null

    val elevationLossMeters: Double?
        get() = if (elevation.hasChange) elevation.lossMeters else null

    val segments: List<RouteSegment>
        get() = segmentsLedger.shown

    fun append(point: RecordingPoint): Double {
        if (point.hasFlag(RecordingPointFlags.RESUMED)) {
            recentWindow.clear()
        }
        val distance = accumulate(point)
        movingTime.record(point.toRouteCoordinate())
        appendRecentSample(point.timestampEpochMillis, distance)
        segmentsLedger.record(point, distance)
        return distance
    }

    fun snapshot(pointCount: Int, horizontalAccuracyMeters: Double?): RecordingStatsSnapshot = RecordingStatsSnapshot(
        distanceMeters = distanceMeters,
        recordedDurationSeconds = recordedDurationSeconds,
        movingSeconds = movingSeconds,
        elevationGainMeters = elevationGainMeters,
        elevationLossMeters = elevationLossMeters,
        averageSpeedMps = averageSpeedMps,
        recentSpeedMps = recentSpeedMps,
        isStationary = isStationary,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        pointCount = pointCount,
        segments = segments
    )

    private fun appendRecentSample(timestampEpochMillis: Long, distanceMeters: Double) {
        val latest = recentWindow.lastOrNull()
        if (latest != null && timestampEpochMillis <= latest.timestampEpochMillis) return
        recentWindow.addLast(RecentSample(timestampEpochMillis, distanceMeters))
        while (recentWindow.size > 1) {
            val spanSeconds = (timestampEpochMillis - recentWindow[1].timestampEpochMillis) / MILLIS_PER_SECOND
            if (spanSeconds < RECENT_SPEED_WINDOW_SECONDS) break
            recentWindow.removeFirst()
        }
    }

    private fun accumulate(point: RecordingPoint): Double {
        elevation.record(point.toRouteCoordinate())
        val prev = previous
        return when {
            prev == null -> {
                previous = point
                movementWindowStart = point
                updateMotionStationaryStart(point)
                distanceMeters
            }
            point.hasFlag(RecordingPointFlags.RESUMED) -> handleResumed(point)
            else -> accumulateContinuously(point, prev)
        }
    }

    private fun accumulateContinuously(point: RecordingPoint, prev: RecordingPoint): Double {
        val beganMotionStationary = updateMotionStationaryStart(point)
        val deltaSeconds = max(0.0, (point.timestampEpochMillis - prev.timestampEpochMillis) / MILLIS_PER_SECOND)
        recordedDurationSeconds += deltaSeconds

        return when {
            point.hasFlag(RecordingPointFlags.CARRIED) -> handleCarried(point, prev)
            isStationary -> handleStationary(point, prev)
            else -> {
                val legMeters = RouteGeometry.distanceMeters(prev.coordinate, point.coordinate)
                distanceMeters += legMeters
                movementWindowDistance += legMeters
                previous = point
                updateMovementWindow(point, beganMotionStationary)
            }
        }
    }

    private fun handleResumed(point: RecordingPoint): Double {
        previous = point
        movementWindowStart = point
        movementWindowDistance = 0.0
        stationaryAnchor = null
        isStationary = false
        updateMotionStationaryStart(point)
        return distanceMeters
    }

    private fun handleCarried(point: RecordingPoint, prev: RecordingPoint): Double {
        distanceMeters += RouteGeometry.distanceMeters(prev.coordinate, point.coordinate)
        previous = point
        movementWindowStart = point
        movementWindowDistance = 0.0
        stationaryAnchor = null
        isStationary = false
        return distanceMeters
    }

    private fun handleStationary(point: RecordingPoint, prev: RecordingPoint): Double {
        val anchor = stationaryAnchor ?: prev
        val displacement = RouteGeometry.distanceMeters(anchor.coordinate, point.coordinate)
        previous = point
        val stayedWithinAnchor = point.hasFlag(RecordingPointFlags.MOTION_STATIONARY) ||
            displacement <= RESUME_DISPLACEMENT_METERS
        if (!stayedWithinAnchor) {
            isStationary = false
            stationaryAnchor = null
            distanceMeters += displacement
            movementWindowStart = point
            movementWindowDistance = 0.0
        }
        return distanceMeters
    }

    private fun updateMovementWindow(point: RecordingPoint, beganMotionStationary: Boolean): Double {
        val windowStart = movementWindowStart
        val windowSeconds = windowStart?.let {
            (point.timestampEpochMillis - it.timestampEpochMillis) / MILLIS_PER_SECOND
        }
        when {
            beganMotionStationary -> {
                movementWindowStart = point
                movementWindowDistance = 0.0
            }
            windowStart != null && windowSeconds != null && windowSeconds >= STATIONARY_INTERVAL_SECONDS -> {
                evaluateMovementWindow(point, windowStart)
            }
        }
        return distanceMeters
    }

    private fun evaluateMovementWindow(point: RecordingPoint, windowStart: RecordingPoint) {
        val netDisplacement = RouteGeometry.distanceMeters(windowStart.coordinate, point.coordinate)
        val motionConfirmed = motionStationaryStartedAtMillis?.let { startedAt ->
            (point.timestampEpochMillis - startedAt) / MILLIS_PER_SECOND >= STATIONARY_INTERVAL_SECONDS
        } ?: false

        if (netDisplacement < STATIONARY_NET_DISPLACEMENT_METERS || motionConfirmed) {
            distanceMeters = max(0.0, distanceMeters - movementWindowDistance)
            movementWindowDistance = 0.0
            stationaryAnchor = windowStart
            isStationary = true
        } else {
            movementWindowStart = point
            movementWindowDistance = 0.0
        }
    }

    private fun updateMotionStationaryStart(point: RecordingPoint): Boolean {
        val isMotionStationary = point.hasFlag(RecordingPointFlags.MOTION_STATIONARY)
        val startedNow = isMotionStationary && motionStationaryStartedAtMillis == null
        motionStationaryStartedAtMillis = when {
            !isMotionStationary -> null
            startedNow -> point.timestampEpochMillis
            else -> motionStationaryStartedAtMillis
        }
        return startedNow
    }

    companion object {
        const val STATIONARY_INTERVAL_SECONDS = MovementThresholds.STATIONARY_INTERVAL_SECONDS
        const val STATIONARY_NET_DISPLACEMENT_METERS = MovementThresholds.STATIONARY_NET_DISPLACEMENT_METERS
        const val RESUME_DISPLACEMENT_METERS = 20.0
        const val RECENT_SPEED_WINDOW_SECONDS = 300.0
        const val MINIMUM_RECENT_SPEED_SPAN_SECONDS = 60.0
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
