package app.openhikes.core.model

import kotlin.math.round
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Compact coordinate path carried by a [RouteStep] that redraws or closes ground.
 * Pairs of `[latitude, longitude]` rounded to ~10 cm precision (`1e-6` degrees).
 */
@Serializable
data class RoutePath(val flat: List<Double> = emptyList()) {
    val count: Int
        get() = flat.size / PAIR_SIZE

    val points: List<RouteCoordinate>
        get() = List(count) { index ->
            RouteCoordinate(
                latitude = flat[index * PAIR_SIZE],
                longitude = flat[index * PAIR_SIZE + 1],
                provenance = RouteProvenance.INFERRED
            )
        }

    companion object {
        private const val PRECISION = 1_000_000.0
        private const val PAIR_SIZE = 2

        val STRAIGHT = RoutePath(emptyList())

        fun fromCoordinates(coordinates: List<GeoCoordinate>): RoutePath {
            val flat = ArrayList<Double>(coordinates.size * PAIR_SIZE)
            for (coord in coordinates) {
                flat.add(rounded(coord.latitude))
                flat.add(rounded(coord.longitude))
            }
            return RoutePath(flat)
        }

        private fun rounded(degrees: Double): Double = round(degrees * PRECISION) / PRECISION
    }
}

/**
 * Single reversible edit step applied in order to derive an edited hike route from its original.
 */
@Serializable
sealed interface RouteStep {
    val pathCoordinateCount: Int
        get() = 0

    val keepsClock: Boolean
        get() = true

    @Serializable
    @SerialName("closeLoop")
    data class CloseLoop(val path: RoutePath = RoutePath.STRAIGHT) : RouteStep {
        override val pathCoordinateCount: Int
            get() = path.count
    }

    @Serializable
    @SerialName("outAndBack")
    data object OutAndBack : RouteStep

    @Serializable
    @SerialName("pause")
    data class Pause(val startMeters: Double, val endMeters: Double) : RouteStep

    @Serializable
    @SerialName("replace")
    data class Replace(val startMeters: Double, val endMeters: Double, val path: RoutePath) : RouteStep {
        override val pathCoordinateCount: Int
            get() = path.count
    }

    @Serializable
    @SerialName("reverse")
    data object Reverse : RouteStep {
        override val keepsClock: Boolean
            get() = false
    }

    @Serializable
    @SerialName("startAt")
    data class StartAt(val meters: Double) : RouteStep {
        override val keepsClock: Boolean
            get() = false
    }

    @Serializable
    @SerialName("trim")
    data class Trim(val startMeters: Double, val endMeters: Double, val endPauses: Int = 0) : RouteStep
}

/**
 * Pure-JVM interpreter applying ordered [RouteStep] edits while preserving pause boundaries,
 * inferred provenance, recorded duration accounting, and point-budget bounds.
 */
object RouteSteps {
    /** Maximum total coordinates carried across all custom paths of one hike's edit steps. */
    const val MAXIMUM_PATH_COORDINATES = 6_000

    /** Distance below which two coordinates are treated as the same join spot. */
    const val JOIN_METERS = 1.0

    data class Applied(
        val route: List<RouteCoordinate>,
        val movesStart: Boolean,
        val movesEnd: Boolean,
        val recordedSeconds: Double,
        val lastStepChangedLine: Boolean,
        val clockedRoute: List<RouteCoordinate>
    )

    fun pathCoordinateCount(steps: List<RouteStep>): Int = steps.sumOf { it.pathCoordinateCount }

    fun maximumPoints(fromCount: Int): Int = fromCount * 2 + MAXIMUM_PATH_COORDINATES

    fun applying(steps: List<RouteStep>, toLine: List<RouteCoordinate>): Applied {
        var route = toLine
        var movesStart = false
        var movesEnd = false
        var clock = RouteTrim.recordedSeconds(toLine)
        var recorded = clock
        var lastStepChangedLine = false
        var clockedRoute: List<RouteCoordinate> = emptyList()
        val limit = maximumPoints(toLine.size)

        for (step in steps) {
            val before = route
            val after = applying(step, before)
            lastStepChangedLine = after != before && after.size <= limit
            if (!lastStepChangedLine) continue
            route = after
            val recordedNow = RouteTrim.recordedSeconds(after)
            if (step.keepsClock) recorded += recordedNow - clock
            if (!step.keepsClock && clockedRoute.isEmpty()) clockedRoute = before
            clock = recordedNow
            val (movedStart, movedEnd) = stepMovesEnds(step, before, route)
            if (movedStart) movesStart = true
            if (movedEnd) movesEnd = true
        }
        return Applied(
            route = route,
            movesStart = movesStart,
            movesEnd = movesEnd,
            recordedSeconds = recorded,
            lastStepChangedLine = lastStepChangedLine,
            clockedRoute = clockedRoute
        )
    }

    fun applying(step: RouteStep, toLine: List<RouteCoordinate>): List<RouteCoordinate> {
        if (toLine.size <= 1) return toLine
        val result = when (step) {
            is RouteStep.CloseLoop -> RouteStepTransforms.closed(toLine, step.path)
            RouteStep.OutAndBack -> if (endsMeet(toLine)) {
                toLine
            } else {
                toLine +
                    RouteStepTransforms.returning(toLine).drop(1)
            }
            is RouteStep.Pause -> RouteStepTransforms.paused(toLine, step.startMeters, step.endMeters)
            is RouteStep.Replace -> RouteStepTransforms.replaced(toLine, step.startMeters, step.endMeters, step.path)
            RouteStep.Reverse -> RouteStepTransforms.withoutTimes(RouteStepTransforms.turned(toLine))
            is RouteStep.StartAt -> RouteStepTransforms.rotated(toLine, step.meters)
            is RouteStep.Trim -> RouteTrim.trimmed(
                route = toLine,
                startMeters = step.startMeters,
                endMeters = step.endMeters,
                takingOff = RouteTrim.EndPauses(step.endPauses)
            )
        }
        return if (result.size > 1 && RouteGeometry.routeLengthMeters(result) > 0.0) result else toLine
    }

    fun walkedStillOn(clocked: List<RouteCoordinate>, kept: List<RouteCoordinate>): List<RouteCoordinate> {
        val places = kept.mapTo(HashSet(kept.size)) { Place(it.latitude, it.longitude) }
        return clocked.filter { Place(it.latitude, it.longitude) in places }
    }

    fun endsMeet(line: List<RouteCoordinate>): Boolean =
        line.size >= 2 && endsMeet(line.first().coordinate, line.last().coordinate)

    fun endsMeet(start: GeoCoordinate, end: GeoCoordinate): Boolean =
        RouteGeometry.distanceMeters(start, end) <= RouteShape.CLOSING_TOLERANCE_METERS

    private fun stepMovesEnds(
        step: RouteStep,
        before: List<RouteCoordinate>,
        after: List<RouteCoordinate>
    ): Pair<Boolean, Boolean> = when (step) {
        is RouteStep.CloseLoop, RouteStep.OutAndBack -> false to true
        RouteStep.Reverse, is RouteStep.StartAt -> true to true
        is RouteStep.Pause, is RouteStep.Replace, is RouteStep.Trim -> {
            val movedStart = !RouteStepTransforms.isSameFix(after.firstOrNull(), before.firstOrNull())
            val movedEnd = !RouteStepTransforms.isSameFix(after.lastOrNull(), before.lastOrNull())
            movedStart to movedEnd
        }
    }

    private data class Place(val latitude: Double, val longitude: Double)
}
