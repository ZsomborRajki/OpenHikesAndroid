package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure-JVM route trimming and active-recording-clock utilities.
 *
 * Cuts fall along [RouteProfile]'s cumulative walked-distance axis (zero length across [RouteBoundary.PAUSED]).
 * Cuts between vertices interpolate coordinates, finite elevation, and timestamps; cuts within [SNAP_METERS]
 * of an existing point snap to it so dragging a handle back restores the exact original vertex.
 */
object RouteTrim {
    /** Snap tolerance in meters for locking a cut to an existing point rather than interpolating beside it. */
    const val SNAP_METERS = 0.5
    private const val MILLIS_PER_SECOND = 1_000.0

    /**
     * Bitmask naming route ends where a cut standing on the end itself also strips the pause at that end.
     */
    @JvmInline
    value class EndPauses(val rawValue: Int) {
        val isEmpty: Boolean
            get() = rawValue == 0

        operator fun contains(other: EndPauses): Boolean = (rawValue and other.rawValue) == other.rawValue

        operator fun plus(other: EndPauses): EndPauses = EndPauses(rawValue or other.rawValue)

        operator fun minus(other: EndPauses): EndPauses = EndPauses(rawValue and other.rawValue.inv())

        companion object {
            val NONE = EndPauses(0)
            val START = EndPauses(1 shl 0)
            val END = EndPauses(1 shl 1)
        }
    }

    /**
     * Cumulative walked distances along [route], ascending, adding zero across [RouteBoundary.PAUSED] legs.
     */
    fun distances(route: List<RouteCoordinate>): List<Double> {
        val result = ArrayList<Double>(route.size)
        var cumulative = 0.0
        var previous: RouteCoordinate? = null
        for (point in route) {
            cumulative += previous?.let { RouteGeometry.legMeters(it, point) } ?: 0.0
            result.add(cumulative)
            previous = point
        }
        return result
    }

    /**
     * Returns the slice of [route] between [startMeters] and [endMeters], or empty when fewer than two points
     * or zero length remain.
     */
    fun trimmed(
        route: List<RouteCoordinate>,
        startMeters: Double,
        endMeters: Double,
        distances: List<Double> = distances(route),
        takingOff: EndPauses = EndPauses.NONE
    ): List<RouteCoordinate> {
        val total = distances.lastOrNull()
        val hasValidRoute = route.size > 1 && distances.size == route.size && total != null
        val start = if (total != null) min(max(startMeters, 0.0), total) else 0.0
        val end = if (total != null) min(max(endMeters, 0.0), total) else 0.0
        if (!hasValidRoute || end <= start) return emptyList()

        val head = startCut(route, distances, start, isCut = start > 0.0 || EndPauses.START in takingOff)
        val tail = endCut(route, distances, end, isCut = end < total || EndPauses.END in takingOff)
        val kept = assembleTrimmedSlice(route, head, tail)
        return if (kept.size <= 1) {
            emptyList()
        } else {
            finalizeTrimmedEndpoints(kept, head, tail, route, distances)
            kept
        }
    }

    private fun assembleTrimmedSlice(
        route: List<RouteCoordinate>,
        head: StartCut,
        tail: EndCut
    ): MutableList<RouteCoordinate> {
        val kept = ArrayList<RouteCoordinate>(max(2, tail.lastWhole - head.firstWhole + 3))
        head.made?.let { kept.add(it) }
        if (head.firstWhole <= tail.lastWhole) {
            kept.addAll(route.subList(head.firstWhole, tail.lastWhole + 1))
        }
        tail.made?.let { kept.add(it) }
        return kept
    }

    private fun finalizeTrimmedEndpoints(
        kept: MutableList<RouteCoordinate>,
        head: StartCut,
        tail: EndCut,
        route: List<RouteCoordinate>,
        distances: List<Double>
    ) {
        if (head.made == null && kept[0].timestampEpochMillis == null) {
            val index = head.firstWhole
            val inferred = timeAt(distances[index], lower = index, upper = index, route = route, distances = distances)
            kept[0] = kept[0].copy(timestampEpochMillis = inferred)
        }
        if (tail.made == null && kept[kept.lastIndex].timestampEpochMillis == null) {
            val index = tail.lastWhole
            val inferred = timeAt(distances[index], lower = index, upper = index, route = route, distances = distances)
            kept[kept.lastIndex] = kept[kept.lastIndex].copy(timestampEpochMillis = inferred)
        }
        if (head.made != null || head.firstWhole > 0) {
            kept[0] = kept[0].copy(boundary = null, provenance = null)
        }
    }

    /**
     * Sum of active recording intervals between stamped points in [route], excluding paused gaps
     * while preserving elapsed time across untimed inferred stretches.
     */
    fun recordedSeconds(route: List<RouteCoordinate>): Double {
        var totalSeconds = 0.0
        var beganMillis: Long? = null
        var isPaused = false
        for (point in route) {
            if (point.isPauseBoundary) isPaused = true
            val arrived = point.timestampEpochMillis ?: continue
            val start = beganMillis
            if (start != null && !isPaused && arrived > start) {
                totalSeconds += (arrived - start) / MILLIS_PER_SECOND
            }
            beganMillis = arrived
            isPaused = false
        }
        return totalSeconds
    }

    /**
     * Interpolates the clock at [targetMeters] on the leg between [lower] and [upper] from the nearest
     * enclosing stamped points, returning `null` if either side is untimed or a pause separates them.
     */
    fun timeAt(
        targetMeters: Double,
        lower: Int,
        upper: Int,
        route: List<RouteCoordinate>,
        distances: List<Double>
    ): Long? {
        val before = (lower downTo 0).firstOrNull { route[it].timestampEpochMillis != null }
        val after = (upper..route.lastIndex).firstOrNull { route[it].timestampEpochMillis != null }
        val began = before?.let { route[it].timestampEpochMillis }
        val arrived = after?.let { route[it].timestampEpochMillis }
        val hasBounds = before != null && after != null
        val hasTimes = began != null && arrived != null
        return when {
            !hasBounds || !hasTimes -> null
            after <= before -> began
            ((before + 1)..after).any { route[it].isPauseBoundary } -> null
            else -> {
                val span = distances[after] - distances[before]
                val fraction = if (span > 0.0) (targetMeters - distances[before]) / span else 0.0
                began + ((arrived - began).toDouble() * fraction).roundToLong()
            }
        }
    }

    private fun startCut(
        route: List<RouteCoordinate>,
        distances: List<Double>,
        start: Double,
        isCut: Boolean
    ): StartCut {
        if (!isCut) return StartCut(made = null, firstWhole = 0)
        val upper = min(upperBound(distances, start), route.lastIndex)
        val lower = max(upper - 1, 0)
        return when {
            start - distances[lower] <= SNAP_METERS -> StartCut(made = null, firstWhole = lower)
            distances[upper] - start <= SNAP_METERS -> StartCut(made = null, firstWhole = upper)
            else -> StartCut(
                made = interpolatePoint(start, lower = lower, upper = upper, route = route, distances = distances),
                firstWhole = upper
            )
        }
    }

    private fun endCut(route: List<RouteCoordinate>, distances: List<Double>, end: Double, isCut: Boolean): EndCut {
        if (!isCut) return EndCut(lastWhole = route.lastIndex, made = null)
        val upper = min(lowerBound(distances, end), route.lastIndex)
        val lower = max(upper - 1, 0)
        return when {
            distances[upper] - end <= SNAP_METERS -> EndCut(lastWhole = upper, made = null)
            end - distances[lower] <= SNAP_METERS -> EndCut(lastWhole = lower, made = null)
            else -> EndCut(
                lastWhole = lower,
                made = interpolatePoint(end, lower = lower, upper = upper, route = route, distances = distances)
            )
        }
    }

    private fun interpolatePoint(
        targetMeters: Double,
        lower: Int,
        upper: Int,
        route: List<RouteCoordinate>,
        distances: List<Double>
    ): RouteCoordinate {
        val from = route[lower]
        val to = route[upper]
        val length = distances[upper] - distances[lower]
        val fraction = if (length > 0.0) (targetMeters - distances[lower]) / length else 0.0
        val coordinate = RouteGeometry.interpolate(from.coordinate, to.coordinate, fraction)
        val lowElev = from.elevationMeters?.takeIf { it.isFinite() }
        val highElev = to.elevationMeters?.takeIf { it.isFinite() }
        val elevation = if (lowElev != null && highElev != null) {
            lowElev + (highElev - lowElev) * fraction
        } else {
            null
        }
        val timestamp = timeAt(targetMeters, lower = lower, upper = upper, route = route, distances = distances)
        return RouteCoordinate(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            elevationMeters = elevation,
            timestampEpochMillis = timestamp,
            motion = to.motion,
            provenance = to.provenance
        )
    }

    /** First index whose value is strictly greater than [target]. */
    internal fun upperBound(sorted: List<Double>, target: Double): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] <= target) low = mid + 1 else high = mid
        }
        return low
    }

    /** First index whose value is greater than or equal to [target]. */
    internal fun lowerBound(sorted: List<Double>, target: Double): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    private data class StartCut(val made: RouteCoordinate?, val firstWhole: Int)

    private data class EndCut(val lastWhole: Int, val made: RouteCoordinate?)
}
