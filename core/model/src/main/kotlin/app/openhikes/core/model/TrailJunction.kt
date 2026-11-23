package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.round
import kotlinx.serialization.Serializable

/**
 * Turn instruction at a trail fork or pronounced junction.
 */
@Serializable
enum class JunctionManeuver(val storedId: String) {
    KEEP_LEFT("keepLeft"),
    KEEP_RIGHT("keepRight"),
    SHARP_LEFT("sharpLeft"),
    SHARP_RIGHT("sharpRight"),
    STRAIGHT("straight"),
    TURN_LEFT("turnLeft"),
    TURN_RIGHT("turnRight");

    enum class Side {
        AHEAD,
        LEFT,
        RIGHT
    }

    val side: Side
        get() = when (this) {
            KEEP_LEFT, TURN_LEFT, SHARP_LEFT -> Side.LEFT
            KEEP_RIGHT, TURN_RIGHT, SHARP_RIGHT -> Side.RIGHT
            STRAIGHT -> Side.AHEAD
        }
}

/**
 * Single junction along a trail with maneuvers for both travel directions.
 */
@Serializable
data class TrailJunction(
    val distanceMeters: Double,
    val towardsEnd: JunctionManeuver?,
    val towardsStart: JunctionManeuver?
) {
    fun maneuver(direction: TrailWalkDirection): JunctionManeuver? = when (direction) {
        TrailWalkDirection.TOWARDS_END -> towardsEnd
        TrailWalkDirection.TOWARDS_START -> towardsStart
    }
}

/**
 * Spoken/haptic cue due for an upcoming trail junction.
 */
data class JunctionCue(val junctionIndex: Int, val maneuver: JunctionManeuver, val metersAhead: Double?) {
    val isImminent: Boolean
        get() = metersAhead == null

    fun roundedAheadValue(unit: DistanceUnit): Double? {
        val raw = metersAhead?.takeIf { it.isFinite() } ?: return null
        return when (unit) {
            DistanceUnit.METRIC -> max(METRIC_STEP_METERS, round(raw / METRIC_STEP_METERS) * METRIC_STEP_METERS)
            DistanceUnit.IMPERIAL -> {
                val feet = raw * FEET_PER_METER
                max(IMPERIAL_STEP_FEET, round(feet / IMPERIAL_STEP_FEET) * IMPERIAL_STEP_FEET)
            }
        }
    }

    companion object {
        const val METRIC_STEP_METERS = 10.0
        const val IMPERIAL_STEP_FEET = 50.0
        private const val FEET_PER_METER = 3.280839895
    }
}

/**
 * Stateful fix-by-fix junction announcer deciding when advance (150 m) and imminent (30 m)
 * turn warnings are due, with direction hysteresis, reversal filtering, and re-arming.
 */
class JunctionAnnouncer(junctions: List<TrailJunction>, initialDirection: TrailWalkDirection? = null) {
    val junctions: List<TrailJunction> = junctions.sortedBy { it.distanceMeters }

    var direction: TrailWalkDirection? = initialDirection
        private set

    private var anchorMeters: Double? = null
    private var reversalRun: Int = 0
    private val spoken: MutableMap<SpokenKey, Stage> = HashMap()

    fun advance(distanceMeters: Double, isOnTrail: Boolean): JunctionCue? {
        if (!isOnTrail || !distanceMeters.isFinite()) return null
        updateDirection(distanceMeters)
        rearm(distanceMeters)
        val currentDirection = direction
        val pending = currentDirection?.let { nextPending(distanceMeters, it) }
        return if (currentDirection == null || pending == null) {
            null
        } else {
            resolveCue(index = pending.first, ahead = pending.second, currentDirection = currentDirection)
        }
    }

    fun forgetPosition() {
        anchorMeters = null
        reversalRun = 0
    }

    private fun resolveCue(index: Int, ahead: Double, currentDirection: TrailWalkDirection): JunctionCue? {
        val maneuver = junctions[index].maneuver(currentDirection) ?: return null
        val key = SpokenKey(index = index, direction = currentDirection)
        return when {
            ahead <= IMMINENT_METERS -> {
                spoken[key] = Stage.IMMINENT
                JunctionCue(junctionIndex = index, maneuver = maneuver, metersAhead = null)
            }
            ahead > AHEAD_FLOOR_METERS && !spoken.containsKey(key) -> {
                spoken[key] = Stage.AHEAD
                JunctionCue(junctionIndex = index, maneuver = maneuver, metersAhead = ahead)
            }
            else -> null
        }
    }

    private fun updateDirection(distance: Double) {
        val anchor = anchorMeters
        val currentDirection = direction
        when {
            anchor == null -> anchorMeters = distance
            currentDirection == null -> detectInitialDirection(anchor, distance)
            else -> trackReversal(currentDirection, anchor, distance)
        }
    }

    private fun detectInitialDirection(anchor: Double, distance: Double) {
        val step = distance - anchor
        if (step >= DIRECTION_STEP_METERS) {
            direction = TrailWalkDirection.TOWARDS_END
            anchorMeters = distance
        } else if (step <= -DIRECTION_STEP_METERS) {
            direction = TrailWalkDirection.TOWARDS_START
            anchorMeters = distance
        }
    }

    private fun trackReversal(currentDirection: TrailWalkDirection, anchor: Double, distance: Double) {
        val back = if (currentDirection == TrailWalkDirection.TOWARDS_END) anchor - distance else distance - anchor
        if (back <= 0.0) {
            anchorMeters = distance
            reversalRun = 0
        } else {
            reversalRun = if (back >= REVERSAL_METERS) reversalRun + 1 else 0
            if (reversalRun >= REVERSAL_FIXES) {
                direction = if (currentDirection == TrailWalkDirection.TOWARDS_END) {
                    TrailWalkDirection.TOWARDS_START
                } else {
                    TrailWalkDirection.TOWARDS_END
                }
                anchorMeters = distance
                reversalRun = 0
            }
        }
    }

    private fun rearm(distance: Double) {
        val iterator = spoken.entries.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next().key
            if (abs(junctions[key.index].distanceMeters - distance) > REARM_METERS) {
                iterator.remove()
            }
        }
    }

    private fun nextPending(distance: Double, currentDirection: TrailWalkDirection): Pair<Int, Double>? {
        val indices = if (currentDirection == TrailWalkDirection.TOWARDS_END) {
            junctions.indices
        } else {
            junctions.indices.reversed()
        }
        var found: Pair<Int, Double>? = null
        var blocked = false
        for (index in indices) {
            if (found != null || blocked) break
            val evaluation = evaluateJunction(index, distance, currentDirection)
            when (evaluation) {
                JunctionEvaluation.Skip -> Unit
                JunctionEvaluation.Stop -> blocked = true
                is JunctionEvaluation.Ready -> found = evaluation.index to evaluation.ahead
            }
        }
        return found
    }

    private fun evaluateJunction(
        index: Int,
        distance: Double,
        currentDirection: TrailWalkDirection
    ): JunctionEvaluation {
        val junction = junctions[index]
        val ahead = if (currentDirection == TrailWalkDirection.TOWARDS_END) {
            junction.distanceMeters - distance
        } else {
            distance - junction.distanceMeters
        }
        val alreadyImminent = spoken[SpokenKey(index, currentDirection)] == Stage.IMMINENT
        return when {
            ahead <= -PASSED_METERS -> JunctionEvaluation.Skip
            ahead > AHEAD_METERS -> JunctionEvaluation.Stop
            junction.maneuver(currentDirection) == null -> JunctionEvaluation.Skip
            alreadyImminent && ahead > 0.0 -> JunctionEvaluation.Stop
            alreadyImminent -> JunctionEvaluation.Skip
            else -> JunctionEvaluation.Ready(index, ahead)
        }
    }

    private sealed interface JunctionEvaluation {
        data object Skip : JunctionEvaluation
        data object Stop : JunctionEvaluation
        data class Ready(val index: Int, val ahead: Double) : JunctionEvaluation
    }

    private data class SpokenKey(val index: Int, val direction: TrailWalkDirection)

    private enum class Stage {
        AHEAD,
        IMMINENT
    }

    companion object {
        const val AHEAD_METERS = 150.0
        const val AHEAD_FLOOR_METERS = 70.0
        const val IMMINENT_METERS = 30.0
        const val PASSED_METERS = 10.0
        const val DIRECTION_STEP_METERS = 10.0
        const val REVERSAL_METERS = 30.0
        const val REVERSAL_FIXES = 2
        const val REARM_METERS = 250.0
    }
}

/**
 * Geometry-based fallback detector finding clear trail turns along a [RouteProfile] when
 * an offline routing graph tile is not yet available.
 */
object RouteTurnJunctionDetector {
    private const val MIN_COORDINATES_FOR_TURN = 3
    private const val LOOK_DISTANCE_METERS = 25.0
    private const val MIN_JUNCTION_SPACING_METERS = 40.0
    private const val TURN_ANGLE_MIN_DEGREES = 50.0
    private const val SHARP_TURN_ANGLE_DEGREES = 115.0
    private const val RAD_TO_DEG = 180.0 / PI

    fun detect(profile: RouteProfile): List<TrailJunction> {
        val total = profile.totalDistanceMeters
        if (profile.coordinates.size < MIN_COORDINATES_FOR_TURN || total < LOOK_DISTANCE_METERS * 2) {
            return emptyList()
        }
        val found = ArrayList<TrailJunction>()
        var lastJunctionDistance = -MIN_JUNCTION_SPACING_METERS
        for (index in 1 until profile.coordinates.lastIndex) {
            val junction = detectAtVertex(profile, index, total, lastJunctionDistance)
            if (junction != null) {
                found.add(junction)
                lastJunctionDistance = junction.distanceMeters
            }
        }
        return found
    }

    private fun detectAtVertex(
        profile: RouteProfile,
        index: Int,
        totalDistance: Double,
        lastJunctionDistance: Double
    ): TrailJunction? {
        val dist = profile.distances[index]
        val isNearPause = profile.pausedSegments.contains(index - 1) || profile.pausedSegments.contains(index)
        val withinBounds = dist >= LOOK_DISTANCE_METERS && totalDistance - dist >= LOOK_DISTANCE_METERS
        val spacedOut = dist - lastJunctionDistance >= MIN_JUNCTION_SPACING_METERS
        val before = if (!isNearPause && withinBounds && spacedOut) {
            profile.coordinateAt(dist - LOOK_DISTANCE_METERS)
        } else {
            null
        }
        val after = if (before != null) profile.coordinateAt(dist + LOOK_DISTANCE_METERS) else null
        return if (before == null || after == null) {
            null
        } else {
            val signedTurn = signedTurnDegrees(before, profile.coordinates[index], after)
            if (abs(signedTurn) < TURN_ANGLE_MIN_DEGREES) {
                null
            } else {
                TrailJunction(
                    distanceMeters = dist,
                    towardsEnd = maneuverForSignedAngle(signedTurn),
                    towardsStart = maneuverForSignedAngle(-signedTurn)
                )
            }
        }
    }

    private fun maneuverForSignedAngle(signedDegrees: Double): JunctionManeuver {
        val isSharp = abs(signedDegrees) >= SHARP_TURN_ANGLE_DEGREES
        return if (signedDegrees > 0.0) {
            if (isSharp) JunctionManeuver.SHARP_LEFT else JunctionManeuver.TURN_LEFT
        } else {
            if (isSharp) JunctionManeuver.SHARP_RIGHT else JunctionManeuver.TURN_RIGHT
        }
    }

    private fun signedTurnDegrees(before: GeoCoordinate, at: GeoCoordinate, after: GeoCoordinate): Double {
        val (inX, inY) = RouteGeometry.localOffset(before, at)
        val (outX, outY) = RouteGeometry.localOffset(at, after)
        val cross = inX * outY - inY * outX
        val dot = inX * outX + inY * outY
        return atan2(cross, dot) * RAD_TO_DEG
    }
}
