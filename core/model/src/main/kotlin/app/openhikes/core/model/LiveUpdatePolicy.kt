package app.openhikes.core.model

import kotlin.math.abs

/**
 * Activity kind competing for the single live-update notification slot.
 */
enum class LiveUpdateKind {
    RECORDING,
    FOLLOWING
}

/**
 * High-level run state rendered inside a Live Update notification.
 */
enum class LiveUpdateRunState {
    FINDING_GPS,
    RECORDING,
    AUTO_PAUSED,
    PAUSED,
    SAVING,
    FOLLOWING;

    val stopsFixStream: Boolean
        get() = this == PAUSED || this == AUTO_PAUSED || this == SAVING
}

/**
 * Normalized Live Update payload shared between recording and future trail-following engines.
 */
data class LiveUpdatePayload(
    val kind: LiveUpdateKind,
    val sessionId: String,
    val title: String,
    val runState: LiveUpdateRunState,
    val distanceMeters: Double,
    val elevationGainMeters: Double? = null,
    val averageSpeedMps: Double? = null,
    val timerStartEpochMillis: Long? = null,
    val isOnRoute: Boolean = true,
    val progressPercent: Int? = null
)

/**
 * Outcome of submitting a Live Update payload to [LiveUpdateArbiter].
 */
enum class LiveUpdateDecision {
    POST_IMMEDIATE,
    SUPPRESS
}

/**
 * Pure precedence and rate-limit arbiter for Live Updates:
 * - Recording always outranks trail following.
 * - Updates are throttled to at most once every 20 s or 25 m distance delta unless a state change occurs.
 * - Transitions that stop the GPS fix stream bypass throttling; repeating route/state flips share
 *   independent 10 s flip floors.
 */
class LiveUpdateArbiter {
    private var activeRecording: LiveUpdatePayload? = null
    private var activeFollowing: LiveUpdatePayload? = null
    private var displayed: LiveUpdatePayload? = null
    private var lastPostedAtMillis: Long? = null
    private var lastRunStateFlipAtMillis: Long? = null
    private var lastRouteFlipAtMillis: Long? = null

    val activeKind: LiveUpdateKind?
        get() = when {
            activeRecording != null -> LiveUpdateKind.RECORDING
            activeFollowing != null -> LiveUpdateKind.FOLLOWING
            else -> null
        }

    fun submit(payload: LiveUpdatePayload, nowEpochMillis: Long): LiveUpdateDecision {
        when (payload.kind) {
            LiveUpdateKind.RECORDING -> activeRecording = payload
            LiveUpdateKind.FOLLOWING -> {
                activeFollowing = payload
                if (activeRecording != null) return LiveUpdateDecision.SUPPRESS
            }
        }
        return evaluateForDisplay(payload, nowEpochMillis)
    }

    /**
     * Ends [kind] and returns a suppressed lower-priority payload if one should now resume display.
     */
    fun end(kind: LiveUpdateKind, nowEpochMillis: Long): LiveUpdatePayload? {
        when (kind) {
            LiveUpdateKind.RECORDING -> activeRecording = null
            LiveUpdateKind.FOLLOWING -> activeFollowing = null
        }
        val shouldRestore = displayed?.kind == kind
        val restored = if (shouldRestore) {
            displayed = null
            lastPostedAtMillis = null
            lastRunStateFlipAtMillis = null
            lastRouteFlipAtMillis = null
            activeFollowing?.also { evaluateForDisplay(it, nowEpochMillis) }
        } else {
            null
        }
        return restored
    }

    private fun evaluateForDisplay(payload: LiveUpdatePayload, nowEpochMillis: Long): LiveUpdateDecision {
        val previous = displayed
        val isNewSlot = previous == null || previous.kind != payload.kind || previous.sessionId != payload.sessionId
        return when {
            isNewSlot -> {
                recordPost(payload, nowEpochMillis, runStateFlipped = false, routeFlipped = false)
                LiveUpdateDecision.POST_IMMEDIATE
            }
            isIdenticalDisplayState(previous, payload) -> LiveUpdateDecision.SUPPRESS
            else -> evaluateThrottle(previous, payload, nowEpochMillis)
        }
    }

    private fun evaluateThrottle(
        previous: LiveUpdatePayload,
        payload: LiveUpdatePayload,
        nowEpochMillis: Long
    ): LiveUpdateDecision {
        val runStateFlipped = previous.runState != payload.runState
        val routeFlipped = previous.isOnRoute != payload.isOnRoute
        val bypassesThrottle = payload.runState.stopsFixStream ||
            canPostFlip(runStateFlipped, lastRunStateFlipAtMillis, nowEpochMillis) ||
            canPostFlip(routeFlipped, lastRouteFlipAtMillis, nowEpochMillis)
        val enoughTimeOrDistance = isDueByIntervalOrDistance(previous, payload, nowEpochMillis)
        return if (!bypassesThrottle && !enoughTimeOrDistance) {
            LiveUpdateDecision.SUPPRESS
        } else {
            recordPost(payload, nowEpochMillis, runStateFlipped = runStateFlipped, routeFlipped = routeFlipped)
            LiveUpdateDecision.POST_IMMEDIATE
        }
    }

    private fun canPostFlip(flipped: Boolean, lastFlipAtMillis: Long?, nowEpochMillis: Long): Boolean =
        flipped && (lastFlipAtMillis == null || (nowEpochMillis - lastFlipAtMillis) >= MINIMUM_FLIP_INTERVAL_MILLIS)

    private fun isDueByIntervalOrDistance(
        previous: LiveUpdatePayload,
        next: LiveUpdatePayload,
        nowEpochMillis: Long
    ): Boolean {
        val lastPost = lastPostedAtMillis ?: return true
        val elapsedReady = (nowEpochMillis - lastPost) >= MINIMUM_UPDATE_INTERVAL_MILLIS
        val distanceDelta = abs(next.distanceMeters - previous.distanceMeters)
        val hasSignificantChange = distanceDelta >= MINIMUM_DISTANCE_DELTA_METERS ||
            next.progressPercent != previous.progressPercent ||
            next.elevationGainMeters != previous.elevationGainMeters ||
            next.title != previous.title
        return elapsedReady && hasSignificantChange
    }

    private fun isIdenticalDisplayState(previous: LiveUpdatePayload, next: LiveUpdatePayload): Boolean =
        previous.copy(timerStartEpochMillis = next.timerStartEpochMillis) == next

    private fun recordPost(
        payload: LiveUpdatePayload,
        nowEpochMillis: Long,
        runStateFlipped: Boolean,
        routeFlipped: Boolean
    ) {
        displayed = payload
        lastPostedAtMillis = nowEpochMillis
        if (runStateFlipped) lastRunStateFlipAtMillis = nowEpochMillis
        if (routeFlipped) lastRouteFlipAtMillis = nowEpochMillis
    }

    companion object {
        const val MINIMUM_UPDATE_INTERVAL_MILLIS = 20_000L
        const val MINIMUM_FLIP_INTERVAL_MILLIS = 10_000L
        const val MINIMUM_DISTANCE_DELTA_METERS = 25.0
    }
}
