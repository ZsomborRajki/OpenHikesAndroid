package app.openhikes.core.model

import kotlin.math.abs

/**
 * Thresholds determining when sustained containment becomes an auto-pause stop and when departure
 * ends it. Parameterized so debug UI tests can supply shorter stop durations.
 */
data class AutoPausePolicy(
    val minimumStopSeconds: Double = DEFAULT_MINIMUM_STOP_SECONDS,
    val stopRadiusMeters: Double = DEFAULT_STOP_RADIUS_METERS,
    val stopHeightMeters: Double = DEFAULT_STOP_HEIGHT_METERS,
    val maximumFixAccuracyMeters: Double = DEFAULT_MAXIMUM_FIX_ACCURACY_METERS,
    val settledSeconds: Double = DEFAULT_SETTLED_SECONDS,
    val settledMeters: Double = DEFAULT_SETTLED_METERS,
    val motionLagSeconds: Double = DEFAULT_MOTION_LAG_SECONDS,
    val departureGapSeconds: Double = DEFAULT_DEPARTURE_GAP_SECONDS,
    val maximumOnFootSpeedMps: Double = DEFAULT_MAXIMUM_ON_FOOT_SPEED_MPS
) {
    companion object {
        const val DEFAULT_MINIMUM_STOP_SECONDS = 300.0
        const val DEFAULT_STOP_RADIUS_METERS = 30.0
        const val DEFAULT_STOP_HEIGHT_METERS = 10.0
        const val DEFAULT_MAXIMUM_FIX_ACCURACY_METERS = 50.0
        const val DEFAULT_SETTLED_SECONDS = 30.0
        const val DEFAULT_SETTLED_METERS = 15.0
        const val DEFAULT_MOTION_LAG_SECONDS = 60.0
        const val DEFAULT_DEPARTURE_GAP_SECONDS = 60.0
        const val DEFAULT_MAXIMUM_ON_FOOT_SPEED_MPS = 8.0

        val Standard = AutoPausePolicy()
    }
}

/**
 * Deterministic state machine deciding when a recording has stopped in one place and when the
 * hiker walks away again, supporting position-only streams as well as motion corroboration.
 */
class AutoPauseWatch(val policy: AutoPausePolicy = AutoPausePolicy.Standard) {
    enum class Motion {
        UNKNOWN,
        STATIONARY,
        ON_FOOT,
        CARRIED;

        companion object {
            fun from(state: RecordingMotionState): Motion = when (state) {
                RecordingMotionState.UNKNOWN -> UNKNOWN
                RecordingMotionState.STATIONARY -> STATIONARY
                RecordingMotionState.PEDESTRIAN -> ON_FOOT
                RecordingMotionState.NON_PEDESTRIAN -> CARRIED
            }
        }
    }

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val timestampEpochMillis: Long,
        val horizontalAccuracyMeters: Double,
        val elevationMeters: Double? = null,
        val speedMps: Double? = null,
        val motion: Motion = Motion.UNKNOWN
    )

    sealed interface Event {
        data class Stopped(val sinceEpochMillis: Long) : Event

        data class Departed(val sinceEpochMillis: Long) : Event
    }

    private data class Candidate(val anchor: Fix, var stillSince: Fix, var isSettled: Boolean = false)

    private data class Stop(var place: Fix, val outbound: MutableList<Fix> = ArrayList())

    private sealed interface State {
        data class Walking(val candidate: Candidate?) : State

        data class Stopped(val stop: Stop) : State
    }

    private var state: State = State.Walking(null)
    private var lastObservedAtMillis: Long? = null
    private var lastPlacedAtMillis: Long? = null
    private var stillSinceMillis: Long? = null
    private var movedSinceFix: Boolean = false

    val isStopped: Boolean
        get() = state is State.Stopped

    val decisionEpochMillis: Long?
        get() {
            val walking = state as? State.Walking ?: return null
            val candidate = walking.candidate ?: return null
            return candidate.anchor.timestampEpochMillis + (policy.minimumStopSeconds * MILLIS_PER_SECOND).toLong()
        }

    fun reset() {
        state = State.Walking(null)
        lastObservedAtMillis = null
        lastPlacedAtMillis = null
        stillSinceMillis = null
        movedSinceFix = false
    }

    fun resumeStop(place: Fix): Boolean {
        val valid = place.horizontalAccuracyMeters.isFinite() &&
            place.horizontalAccuracyMeters > 0.0 &&
            place.horizontalAccuracyMeters <= policy.maximumFixAccuracyMeters &&
            place.latitude.isFinite() &&
            place.longitude.isFinite()
        if (!valid) return false
        reset()
        lastObservedAtMillis = place.timestampEpochMillis
        lastPlacedAtMillis = place.timestampEpochMillis
        state = State.Stopped(Stop(place = place))
        return true
    }

    fun observe(fix: Fix): Event? {
        val prevObserved = lastObservedAtMillis
        val valid = fix.horizontalAccuracyMeters.isFinite() &&
            fix.horizontalAccuracyMeters > 0.0 &&
            fix.latitude.isFinite() &&
            fix.longitude.isFinite()
        val canProcess = valid && (prevObserved == null || fix.timestampEpochMillis > prevObserved)
        return if (!canProcess) {
            null
        } else {
            lastObservedAtMillis = fix.timestampEpochMillis
            if (fix.horizontalAccuracyMeters <= policy.maximumFixAccuracyMeters) {
                lastPlacedAtMillis = fix.timestampEpochMillis
                movedSinceFix = false
            }
            hear(fix.motion, fix.timestampEpochMillis)
            when (val current = state) {
                is State.Walking -> observeWalking(fix, current.candidate)
                is State.Stopped -> observeStopped(fix, current.stop)
            }
        }
    }

    fun tick(nowEpochMillis: Long, motion: Motion): Event? {
        hear(motion, nowEpochMillis)
        val candidate = (state as? State.Walking)?.candidate
        val elapsedSeconds = candidate?.let { (nowEpochMillis - it.anchor.timestampEpochMillis) / MILLIS_PER_SECOND }
        val silentAndStill = candidate != null &&
            elapsedSeconds != null &&
            elapsedSeconds >= policy.minimumStopSeconds &&
            !movedSinceFix &&
            motion != Motion.ON_FOOT &&
            motion != Motion.CARRIED
        return if (!silentAndStill) {
            null
        } else {
            val stillStart = stillSinceMillis
            val placedAt = lastPlacedAtMillis
            val stillThroughout = stillStart != null &&
                placedAt != null &&
                (stillStart - placedAt) / MILLIS_PER_SECOND <= policy.motionLagSeconds
            if (stillThroughout || candidate.isSettled) stopAt(candidate.anchor) else null
        }
    }

    private fun hear(motion: Motion, epochMillis: Long) {
        when (motion) {
            Motion.STATIONARY -> if (stillSinceMillis == null) stillSinceMillis = epochMillis
            Motion.ON_FOOT, Motion.CARRIED -> {
                stillSinceMillis = null
                val placedAt = lastPlacedAtMillis
                if (placedAt != null && epochMillis > placedAt) movedSinceFix = true
            }
            Motion.UNKNOWN -> Unit
        }
    }

    private fun observeWalking(fix: Fix, candidate: Candidate?): Event? {
        val canAnchor = fix.horizontalAccuracyMeters <= policy.maximumFixAccuracyMeters
        val climbed = canAnchor &&
            candidate != null &&
            fix.elevationMeters != null &&
            candidate.anchor.elevationMeters != null &&
            abs(fix.elevationMeters - candidate.anchor.elevationMeters) > policy.stopHeightMeters
        return when {
            candidate == null -> {
                if (canAnchor) state = State.Walking(Candidate(anchor = fix, stillSince = fix))
                null
            }
            isOutside(fix, candidate.anchor) || climbed -> {
                state = State.Walking(if (canAnchor) Candidate(anchor = fix, stillSince = fix) else null)
                null
            }
            !canAnchor -> null
            else -> {
                val stopSpanSeconds = (fix.timestampEpochMillis - candidate.anchor.timestampEpochMillis) /
                    MILLIS_PER_SECOND
                if (stopSpanSeconds >= policy.minimumStopSeconds) {
                    stopAt(candidate.anchor)
                } else {
                    state = State.Walking(holding(candidate, fix))
                    null
                }
            }
        }
    }

    private fun holding(candidate: Candidate, fix: Fix): Candidate {
        val settledSpanSeconds =
            (fix.timestampEpochMillis - candidate.stillSince.timestampEpochMillis) / MILLIS_PER_SECOND
        if (settledSpanSeconds < policy.settledSeconds) return candidate
        val settled = distanceMeters(candidate.stillSince, fix) < policy.settledMeters
        return candidate.copy(
            stillSince = if (settled) candidate.stillSince else fix,
            isSettled = settled
        )
    }

    private fun stopAt(anchor: Fix): Event {
        state = State.Stopped(Stop(place = anchor))
        return Event.Stopped(sinceEpochMillis = anchor.timestampEpochMillis)
    }

    private fun observeStopped(fix: Fix, stop: Stop): Event? = when {
        fix.horizontalAccuracyMeters > policy.maximumFixAccuracyMeters -> null
        isCarried(fix) -> {
            if (isOutside(fix, stop.place)) stop.place = fix
            stop.outbound.clear()
            null
        }
        else -> trackStoppedOutbound(fix, stop)
    }

    private fun trackStoppedOutbound(fix: Fix, stop: Stop): Event? {
        if (shouldExtendOutbound(fix, stop)) {
            stop.outbound.add(fix)
            if (stop.outbound.size > MAXIMUM_OUTBOUND_FIXES) {
                stop.outbound.removeAt(0)
            }
        } else {
            stop.outbound.clear()
            stop.outbound.add(fix)
        }
        return if (!isOutside(fix, stop.place)) {
            null
        } else {
            val departedAt = stop.outbound.first().timestampEpochMillis
            state = State.Walking(null)
            lastObservedAtMillis = null
            Event.Departed(sinceEpochMillis = departedAt)
        }
    }

    private fun shouldExtendOutbound(fix: Fix, stop: Stop): Boolean {
        val last = stop.outbound.lastOrNull() ?: return false
        val gapSeconds = (fix.timestampEpochMillis - last.timestampEpochMillis) / MILLIS_PER_SECOND
        val movingOutward = distanceMeters(stop.place, fix) > distanceMeters(stop.place, last)
        return movingOutward && gapSeconds <= policy.departureGapSeconds
    }

    private fun isOutside(fix: Fix, place: Fix): Boolean =
        distanceMeters(place, fix) > policy.stopRadiusMeters + fix.horizontalAccuracyMeters

    private fun isCarried(fix: Fix): Boolean = fix.motion == Motion.CARRIED ||
        (fix.motion != Motion.STATIONARY && (fix.speedMps ?: 0.0) > policy.maximumOnFootSpeedMps)

    private fun distanceMeters(start: Fix, end: Fix): Double = RouteGeometry.distanceMeters(
        GeoCoordinate(start.latitude, start.longitude),
        GeoCoordinate(end.latitude, end.longitude)
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val MAXIMUM_OUTBOUND_FIXES = 16
    }
}
