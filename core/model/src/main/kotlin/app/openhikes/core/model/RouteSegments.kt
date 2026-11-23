package app.openhikes.core.model

import kotlin.math.min

/**
 * Single walked run of a route and the pause duration that followed it (if any).
 */
data class RouteSegment(
    val distanceMeters: Double,
    val durationSeconds: Double? = null,
    val elevationGainMeters: Double? = null,
    val stopSeconds: Double? = null,
    val timedDistanceFraction: Double = 1.0
) {
    val averageSpeedMps: Double?
        get() = durationSeconds?.takeIf { it > 0.0 }?.let { seconds ->
            distanceMeters * timedDistanceFraction / seconds
        }
}

/**
 * Splits a route at pause boundaries into walked segments and inter-segment pauses.
 */
object RouteSegments {
    const val MAXIMUM_COUNT = 30
    private const val MILLIS_PER_SECOND = 1000.0

    /**
     * Stateful single-pass builder shared by saved route inspection and live recording ledgers.
     */
    class Builder {
        private class Run(var firstTimestampMillis: Long?, var lastTimestampMillis: Long?) {
            var meters: Double = 0.0
            var pointCount: Int = 1
            val elevation: ElevationAccumulator = ElevationAccumulator()
            var timedMeters: Double = 0.0
            var pendingMeters: Double = 0.0
        }

        private val segments = ArrayList<RouteSegment>()
        private var run: Run? = null
        private var previousEndMillis: Long? = null

        fun consume(point: RouteCoordinate, segmentMeters: Double) {
            if (point.isPauseBoundary) closeRun()
            val open = run
            if (open == null) {
                run = Run(
                    firstTimestampMillis = point.timestampEpochMillis,
                    lastTimestampMillis = point.timestampEpochMillis
                ).apply {
                    elevation.record(point.elevationMeters)
                }
                return
            }
            open.meters += segmentMeters
            open.pointCount += 1
            if (open.firstTimestampMillis != null) {
                open.pendingMeters += segmentMeters
            }
            if (point.timestampEpochMillis != null) {
                open.timedMeters += open.pendingMeters
                open.pendingMeters = 0.0
            }
            if (open.firstTimestampMillis == null) {
                open.firstTimestampMillis = point.timestampEpochMillis
            }
            if (point.timestampEpochMillis != null) {
                open.lastTimestampMillis = point.timestampEpochMillis
            }
            open.elevation.record(point.elevationMeters)
        }

        fun finish(distanceMeters: Double): List<RouteSegment> {
            val closedSegments = ArrayList(segments)
            var lastEndMillis = previousEndMillis
            run?.let { open ->
                appendClosedRun(open, closedSegments, lastEndMillis)?.let { lastEndMillis = it }
            }
            val walked = closedSegments.sumOf { it.distanceMeters }
            return when {
                closedSegments.size <= 1 || closedSegments.size > MAXIMUM_COUNT -> emptyList()
                walked <= 0.0 || !distanceMeters.isFinite() || distanceMeters <= 0.0 -> closedSegments
                else -> {
                    val scale = distanceMeters / walked
                    closedSegments.map { segment ->
                        segment.copy(distanceMeters = segment.distanceMeters * scale)
                    }
                }
            }
        }

        private fun closeRun() {
            val closed = run ?: return
            run = null
            appendClosedRun(closed, segments, previousEndMillis)?.let { endMillis ->
                previousEndMillis = endMillis
            }
        }

        private fun appendClosedRun(closed: Run, target: MutableList<RouteSegment>, lastEndMillis: Long?): Long? {
            if (closed.pointCount <= 1) return null
            val firstMillis = closed.firstTimestampMillis
            val lastMillis = closed.lastTimestampMillis
            recordPauseGap(target, lastEndMillis, firstMillis)
            val durationSeconds = if (firstMillis != null && lastMillis != null && lastMillis > firstMillis) {
                (lastMillis - firstMillis) / MILLIS_PER_SECOND
            } else {
                null
            }
            val timedFraction = if (closed.meters > 0.0) min(closed.timedMeters / closed.meters, 1.0) else 1.0
            target.add(
                RouteSegment(
                    distanceMeters = closed.meters,
                    durationSeconds = durationSeconds,
                    elevationGainMeters = if (closed.elevation.hasChange) closed.elevation.gainMeters else null,
                    timedDistanceFraction = timedFraction
                )
            )
            return lastMillis
        }

        private fun recordPauseGap(target: MutableList<RouteSegment>, lastEndMillis: Long?, firstMillis: Long?) {
            val hasPauseGap = lastEndMillis != null && firstMillis != null && firstMillis > lastEndMillis
            if (hasPauseGap && target.isNotEmpty()) {
                val stopSeconds = (firstMillis - lastEndMillis) / MILLIS_PER_SECOND
                target[target.lastIndex] = target.last().copy(stopSeconds = stopSeconds)
            }
        }
    }

    fun segments(route: List<RouteCoordinate>, distanceMeters: Double): List<RouteSegment> {
        val builder = Builder()
        var previous: RouteCoordinate? = null
        for (point in route) {
            val leg = previous?.let { RouteGeometry.legMeters(it, point) } ?: 0.0
            builder.consume(point, segmentMeters = leg)
            previous = point
        }
        return builder.finish(distanceMeters)
    }
}
