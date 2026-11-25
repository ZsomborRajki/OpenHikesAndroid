package app.openhikes.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measured line snapshot computed off the main thread for Edit Route's trimmer and map handles.
 */
class RouteTrimLine private constructor(
    val route: List<RouteCoordinate>,
    val profile: RouteProfile,
    val carriedEnds: RouteCarriedEnds,
    val shape: RouteShape?,
    private val stepState: StepState
) {
    val steps: List<RouteStep>
        get() = stepState.steps

    val endsMovedBySteps: Set<RouteTrimHandle>
        get() = stepState.endsMovedBySteps

    val lastStepChangedLine: Boolean
        get() = stepState.lastStepChangedLine

    val recordedSeconds: Double
        get() = stepState.recordedSeconds

    val clockedRoute: List<RouteCoordinate>
        get() = stepState.clockedRoute

    private data class StepState(
        val steps: List<RouteStep> = emptyList(),
        val endsMovedBySteps: Set<RouteTrimHandle> = emptySet(),
        val lastStepChangedLine: Boolean = true,
        val recordedSeconds: Double = 0.0,
        val clockedRoute: List<RouteCoordinate> = emptyList()
    )
    val distances: List<Double>
        get() = profile.distances

    val totalMeters: Double
        get() = profile.totalDistanceMeters

    fun startSpot(atMeters: Double, takingOffPause: Boolean = false): GeoCoordinate? {
        if (isLeftAlone(RouteTrimHandle.START, atMeters, takingOffPause)) {
            return profile.coordinates.firstOrNull()
        }
        return coordinateAt(bracket(atMeters, RouteTrimHandle.START))
    }

    fun endSpot(atMeters: Double, takingOffPause: Boolean = false): GeoCoordinate? {
        if (isLeftAlone(RouteTrimHandle.END, atMeters, takingOffPause)) {
            return profile.coordinates.lastOrNull()
        }
        return coordinateAt(bracket(atMeters, RouteTrimHandle.END))
    }

    fun timestamp(handle: RouteTrimHandle, atMeters: Double, takingOffPause: Boolean = false): Long? {
        if (isLeftAlone(handle, atMeters, takingOffPause)) {
            return if (handle == RouteTrimHandle.START) {
                route.firstOrNull()?.timestampEpochMillis
            } else {
                route.lastOrNull()?.timestampEpochMillis
            }
        }
        val leg = bracket(atMeters, handle)
        val at = distances[leg.lower] + (distances[leg.upper] - distances[leg.lower]) * leg.fraction
        return RouteTrim.timeAt(at, lower = leg.lower, upper = leg.upper, route = route, distances = distances)
    }

    /**
     * Distance along the route nearest [coordinate], searching locally around [anchorMeters] with a
     * continuity penalty so dragging a handle stays on its own leg of a loop or out-and-back.
     */
    fun distanceNearest(coordinate: GeoCoordinate, aroundAnchorMeters: Double): Double? {
        if (profile.coordinates.size < 2) return null
        val range = candidateSegmentRange(aroundAnchorMeters)
        var bestCost = Double.POSITIVE_INFINITY
        var bestMeters: Double? = null
        for (index in range) {
            val length = distances[index + 1] - distances[index]
            if (length <= 0.0) continue
            val projection = RouteGeometry.project(
                coordinate = coordinate,
                start = profile.coordinates[index],
                end = profile.coordinates[index + 1]
            )
            val meters = distances[index] + projection.fraction * length
            val cost = projection.offRouteMeters + CONTINUITY_WEIGHT * abs(meters - aroundAnchorMeters)
            if (cost < bestCost) {
                bestCost = cost
                bestMeters = meters
            }
        }
        return bestMeters
    }

    /**
     * Distance along the route nearest [coordinate] anywhere on the route if within [withinReachMeters].
     */
    fun distanceAt(coordinate: GeoCoordinate, withinReachMeters: Double): Double? {
        if (profile.coordinates.size < 2) return null
        var bestOff = withinReachMeters
        var bestMeters: Double? = null
        for (index in 0 until profile.coordinates.lastIndex) {
            val length = distances[index + 1] - distances[index]
            if (length <= 0.0) continue
            val projection = RouteGeometry.project(
                coordinate = coordinate,
                start = profile.coordinates[index],
                end = profile.coordinates[index + 1]
            )
            if (projection.offRouteMeters < bestOff) {
                bestOff = projection.offRouteMeters
                bestMeters = distances[index] + projection.fraction * length
            }
        }
        return bestMeters
    }

    fun coordinatesBetween(startMeters: Double, endMeters: Double): List<GeoCoordinate> {
        val from = profile.coordinateAt(startMeters)
        val to = profile.coordinateAt(endMeters)
        if (from == null || to == null) return emptyList()
        val result = ArrayList<GeoCoordinate>()
        result.add(from)
        for (index in profile.coordinates.indices) {
            val dist = distances[index]
            if (dist > startMeters && dist < endMeters) {
                result.add(profile.coordinates[index])
            }
        }
        result.add(to)
        return result
    }

    private fun candidateSegmentRange(referenceDistance: Double): IntRange {
        val lowDist = (referenceDistance - RouteProfile.CONTINUITY_WINDOW_METERS).coerceAtLeast(0.0)
        val highDist = (referenceDistance + RouteProfile.CONTINUITY_WINDOW_METERS).coerceAtMost(totalMeters)
        val lastSeg = profile.coordinates.lastIndex - 1
        val startIdx = (RouteTrim.lowerBound(distances, lowDist) - 1).coerceIn(0, lastSeg)
        val endIdx = RouteTrim.lowerBound(distances, highDist).coerceIn(startIdx, lastSeg)
        return startIdx..endIdx
    }

    private fun isLeftAlone(handle: RouteTrimHandle, atMeters: Double, takingOffPause: Boolean): Boolean {
        if (takingOffPause) return false
        return when (handle) {
            RouteTrimHandle.START -> atMeters <= 0.0
            RouteTrimHandle.END -> atMeters >= totalMeters
        }
    }

    private fun bracket(meters: Double, handle: RouteTrimHandle): Bracket {
        val past = when (handle) {
            RouteTrimHandle.START -> RouteTrim.upperBound(distances, meters)
            RouteTrimHandle.END -> RouteTrim.lowerBound(distances, meters)
        }
        val upper = min(past, distances.lastIndex)
        val lower = max(upper - 1, 0)
        val length = distances[upper] - distances[lower]
        val fraction = if (length > 0.0) ((meters - distances[lower]) / length).coerceIn(0.0, 1.0) else 0.0
        return Bracket(lower = lower, upper = upper, fraction = fraction)
    }

    private fun coordinateAt(bracket: Bracket): GeoCoordinate? {
        if (profile.coordinates.isEmpty()) return null
        return RouteGeometry.interpolate(
            profile.coordinates[bracket.lower],
            profile.coordinates[bracket.upper],
            bracket.fraction
        )
    }

    private data class Bracket(val lower: Int, val upper: Int, val fraction: Double)

    companion object {
        const val CONTINUITY_WEIGHT = 0.02

        fun measure(sources: RouteEditSources, steps: List<RouteStep> = emptyList()): RouteTrimLine {
            if (steps.isEmpty()) {
                return measureRoute(sources.saved)
            }
            val applied = RouteSteps.applying(steps, sources.saved)
            val measured = measureRoute(applied.route)
            val moved = buildSet {
                if (applied.movesStart) add(RouteTrimHandle.START)
                if (applied.movesEnd) add(RouteTrimHandle.END)
            }
            return RouteTrimLine(
                route = measured.route,
                profile = measured.profile,
                carriedEnds = measured.carriedEnds,
                shape = measured.shape,
                stepState = StepState(
                    steps = steps,
                    endsMovedBySteps = moved,
                    lastStepChangedLine = applied.lastStepChangedLine,
                    recordedSeconds = applied.recordedSeconds,
                    clockedRoute = applied.clockedRoute
                )
            )
        }

        fun measureRoute(route: List<RouteCoordinate>): RouteTrimLine {
            val profile = RouteProfile.from(route)
            val carried = RouteCarriedEnds.found(route, profile.distances)
            val shape = RouteShape.of(route)
            return RouteTrimLine(
                route = route,
                profile = profile,
                carriedEnds = carried,
                shape = shape,
                stepState = StepState(recordedSeconds = RouteTrim.recordedSeconds(route))
            )
        }
    }
}
