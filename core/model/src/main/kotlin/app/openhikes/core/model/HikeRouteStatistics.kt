package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Tracks moving duration over a sliding displacement window, resetting cleanly at pause boundaries.
 */
class MovingTimeAccumulator {
    private data class Sample(val timestampMillis: Long, val coordinate: GeoCoordinate)

    private val window = ArrayDeque<Sample>()
    private var previous: Sample? = null

    var seconds: Double = 0.0
        private set

    fun record(point: RouteCoordinate) {
        if (point.isPauseBoundary) {
            resetSegment()
        }
        val timestampMillis = point.timestampEpochMillis ?: return
        val prev = previous
        val elapsedSeconds = prev?.let { (timestampMillis - it.timestampMillis) / MILLIS_PER_SECOND }
        if (elapsedSeconds != null && elapsedSeconds <= 0.0) return

        val sample = Sample(timestampMillis, point.coordinate)
        previous = sample
        window.addLast(sample)
        trim(newestMillis = timestampMillis)
        if (elapsedSeconds != null && isMoving(sample)) {
            seconds += elapsedSeconds
        }
    }

    fun resetSegment() {
        window.clear()
        previous = null
    }

    private fun trim(newestMillis: Long) {
        val stationaryIntervalMillis = MovementThresholds.STATIONARY_INTERVAL_SECONDS * MILLIS_PER_SECOND
        while (window.size > 1 && newestMillis - window[1].timestampMillis >= stationaryIntervalMillis) {
            window.removeFirst()
        }
    }

    private fun isMoving(sample: Sample): Boolean {
        val anchor = window.firstOrNull() ?: return true
        val spanSeconds = (sample.timestampMillis - anchor.timestampMillis) / MILLIS_PER_SECOND
        return spanSeconds < MovementThresholds.STATIONARY_INTERVAL_SECONDS ||
            RouteGeometry.distanceMeters(anchor.coordinate, sample.coordinate) >= STILLNESS_RATE_MPS * spanSeconds
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0
        val STILLNESS_RATE_MPS = MovementThresholds.STATIONARY_NET_DISPLACEMENT_METERS /
            MovementThresholds.STATIONARY_INTERVAL_SECONDS
    }
}

/**
 * Derived hike statistics calculated from route geometry, timestamps, and elevation readings.
 */
data class HikeRouteStatistics(
    val pointCount: Int,
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
    val durationSeconds: Double?,
    val movingDurationSeconds: Double?,
    val minElevationMeters: Double?,
    val maxElevationMeters: Double?,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val averageSpeedMps: Double?,
    val movingAverageSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val inferredDistanceMeters: Double?
) {
    private class Accumulator {
        var firstTimestampMillis: Long? = null
        var lastTimestampMillis: Long? = null
        val elevation = ElevationAccumulator()
        val movingTime = MovingTimeAccumulator()
        var fastestSpeedMps: Double = 0.0
        var inferredMeters: Double = 0.0
        private var walkedMeters = 0.0
        private var timedMeters = 0.0
        private var pendingMeters = 0.0
        private var previousPoint: RouteCoordinate? = null

        val durationSeconds: Double?
            get() = clockSeconds(firstTimestampMillis, lastTimestampMillis)

        val timedDistanceFraction: Double
            get() = if (walkedMeters > 0.0) min(timedMeters / walkedMeters, 1.0) else 1.0

        fun consume(point: RouteCoordinate, segmentMeters: Double) {
            recordCoverage(point, segmentMeters)
            recordTimestamp(point.timestampEpochMillis)
            elevation.record(point)
            movingTime.record(point)
            recordSpeed(point, segmentMeters)
            recordInferred(point, segmentMeters)
            previousPoint = point
        }

        private fun recordCoverage(point: RouteCoordinate, segmentMeters: Double) {
            walkedMeters += segmentMeters
            if (firstTimestampMillis != null) {
                pendingMeters += segmentMeters
            }
            if (point.timestampEpochMillis != null) {
                timedMeters += pendingMeters
                pendingMeters = 0.0
            }
        }

        private fun recordTimestamp(timestampMillis: Long?) {
            timestampMillis ?: return
            if (firstTimestampMillis == null) {
                firstTimestampMillis = timestampMillis
            }
            lastTimestampMillis = timestampMillis
        }

        private fun recordSpeed(point: RouteCoordinate, segmentMeters: Double) {
            val prevTime = previousPoint?.timestampEpochMillis
            val time = point.timestampEpochMillis
            if (!point.isPauseBoundary && prevTime != null && time != null) {
                val elapsedSeconds = (time - prevTime) / MILLIS_PER_SECOND
                if (elapsedSeconds > 0.0) {
                    val speed = segmentMeters / elapsedSeconds
                    if (speed <= MovementThresholds.MAXIMUM_ON_FOOT_SPEED_MPS) {
                        fastestSpeedMps = max(fastestSpeedMps, speed)
                    }
                }
            }
        }

        private fun recordInferred(point: RouteCoordinate, segmentMeters: Double) {
            if (previousPoint != null && point.isInferred) {
                inferredMeters += segmentMeters
            }
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        fun durationSeconds(route: List<RouteCoordinate>): Double? {
            val first = route.firstOrNull { it.timestampEpochMillis != null }?.timestampEpochMillis
            val last = route.lastOrNull { it.timestampEpochMillis != null }?.timestampEpochMillis
            return clockSeconds(first, last)
        }

        fun from(distanceMeters: Double, route: List<RouteCoordinate>): HikeRouteStatistics {
            val acc = Accumulator()
            var previous: RouteCoordinate? = null
            for (point in route) {
                val legMeters = previous?.let { RouteGeometry.legMeters(it, point) } ?: 0.0
                acc.consume(point, legMeters)
                previous = point
            }
            val duration = acc.durationSeconds
            val moving = duration?.let { dur ->
                val bounded = min(acc.movingTime.seconds, dur)
                bounded.takeIf { it > 0.0 }
            }
            val timedDistance = distanceMeters * acc.timedDistanceFraction
            return HikeRouteStatistics(
                pointCount = route.size,
                startEpochMillis = acc.firstTimestampMillis,
                endEpochMillis = acc.lastTimestampMillis,
                durationSeconds = duration,
                movingDurationSeconds = moving,
                minElevationMeters = acc.elevation.minimumMeters,
                maxElevationMeters = acc.elevation.maximumMeters,
                elevationGainMeters = if (acc.elevation.hasChange) acc.elevation.gainMeters else null,
                elevationLossMeters = if (acc.elevation.hasChange) acc.elevation.lossMeters else null,
                averageSpeedMps = duration?.let { timedDistance / it },
                movingAverageSpeedMps = moving?.let { timedDistance / it },
                maxSpeedMps = acc.fastestSpeedMps.takeIf { it > 0.0 },
                inferredDistanceMeters = acc.inferredMeters.takeIf { it > 0.0 }
            )
        }

        private fun clockSeconds(firstMillis: Long?, lastMillis: Long?): Double? {
            if (firstMillis == null || lastMillis == null || lastMillis <= firstMillis) return null
            return (lastMillis - firstMillis) / MILLIS_PER_SECOND
        }
    }
}
