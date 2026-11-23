package app.openhikes.core.testing.android

import app.openhikes.core.location.OffTrailQuestion
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.location.TrailWalkEnd
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.location.TrailWalkStartNotice
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Controllable in-memory [TrailWalkController] fake for ViewModel and Compose screen tests.
 */
@Suppress("TooManyFunctions")
class FakeTrailWalkController(
    initialSession: TrailWalkSessionState = TrailWalkSessionState(),
    initialProgress: TrailWalkProgressState = TrailWalkProgressState()
) : TrailWalkController {
    private val sessionFlow = MutableStateFlow(initialSession)
    private val progressFlow = MutableStateFlow(initialProgress)

    override val sessionState: StateFlow<TrailWalkSessionState> = sessionFlow.asStateFlow()
    override val progressState: StateFlow<TrailWalkProgressState> = progressFlow.asStateFlow()

    var activeSecondsValue: Double = 0.0
    var nextEndResult: TrailWalkEnd? = null
    var startByHandCalls: Int = 0
        private set
    var pauseCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var endCalls: Int = 0
        private set
    var restoreCalls: Int = 0
        private set
    var lastAutoFollowChange: Pair<HikeId, Boolean>? = null
        private set

    fun setSession(state: TrailWalkSessionState) {
        sessionFlow.value = state
    }

    fun setProgress(state: TrailWalkProgressState) {
        progressFlow.value = state
    }

    fun emitStartNotice(hikeId: HikeId, title: String) {
        sessionFlow.update {
            it.copy(
                walkedHikeId = hikeId,
                walkedHikeTitle = title,
                phase = TrailWalkPhase.FOLLOWING,
                startNotice = TrailWalkStartNotice(hikeId, title),
                isJunctionWarningsOffered = true
            )
        }
    }

    fun emitOffTrailQuestion(hikeId: HikeId, title: String) {
        sessionFlow.update { it.copy(offTrailQuestion = OffTrailQuestion(hikeId, title)) }
    }

    override fun activeSeconds(): Double = activeSecondsValue

    override suspend fun restoreAtLaunch() {
        restoreCalls += 1
    }

    override fun attachForegroundHike(hike: Hike, profile: RouteProfile) = Unit

    override fun detachForegroundHike(hikeId: HikeId) = Unit

    override suspend fun startByHand(hikeId: HikeId): Boolean {
        startByHandCalls += 1
        sessionFlow.update {
            it.copy(
                walkedHikeId = hikeId,
                phase = TrailWalkPhase.FOLLOWING,
                startNotice = null,
                isJunctionWarningsOffered = true
            )
        }
        progressFlow.update { it.copy(hikeId = hikeId) }
        return true
    }

    override suspend fun pause(): Boolean {
        pauseCalls += 1
        if (sessionFlow.value.phase != TrailWalkPhase.FOLLOWING) return false
        sessionFlow.update { it.copy(phase = TrailWalkPhase.PAUSED, offTrailQuestion = null) }
        return true
    }

    override suspend fun resume(): Boolean {
        resumeCalls += 1
        if (sessionFlow.value.phase != TrailWalkPhase.PAUSED) return false
        sessionFlow.update { it.copy(phase = TrailWalkPhase.FOLLOWING) }
        return true
    }

    override suspend fun endWalk(): TrailWalkEnd {
        endCalls += 1
        val override = nextEndResult?.also { nextEndResult = null }
        val hikeId = sessionFlow.value.walkedHikeId
        return when {
            override != null -> {
                if (override !is TrailWalkEnd.Refused) {
                    clearSession((override as? TrailWalkEnd.Kept)?.walk)
                }
                override
            }
            hikeId == null -> TrailWalkEnd.Discarded
            else -> {
                val saved = HikeWalk(
                    id = "fake-walk-${hikeId.value}",
                    hikeId = hikeId,
                    startedAtEpochMillis = 0L,
                    endedAtEpochMillis = 60_000L,
                    distanceMeters = 500.0,
                    durationSeconds = 60.0,
                    coveredIntervals = listOf(0.0, 500.0),
                    furthestDistanceMeters = 500.0,
                    routeDistanceMeters = 1_000.0,
                    endReason = TrailWalkEndReason.ENDED
                )
                clearSession(saved)
                TrailWalkEnd.Kept(saved)
            }
        }
    }

    override fun onAutoFollowChanged(hikeId: HikeId, enabled: Boolean) {
        lastAutoFollowChange = hikeId to enabled
    }

    override fun onHikeDeleted(hikeId: HikeId) {
        if (sessionFlow.value.walkedHikeId == hikeId) {
            clearSession(null)
        }
    }

    override fun dismissStartNotice() {
        sessionFlow.update { it.copy(startNotice = null) }
    }

    override fun keepFollowingAfterOffTrail() {
        sessionFlow.update { it.copy(offTrailQuestion = null) }
    }

    override fun consumeLastEndedWalk() {
        sessionFlow.update { it.copy(lastEndedWalk = null) }
    }

    override suspend fun toggleJunctionWarnings() {
        sessionFlow.update { it.copy(isJunctionWarningsEnabled = !it.isJunctionWarningsEnabled) }
    }

    override fun onAppForegrounded() = Unit

    private fun clearSession(lastEnded: HikeWalk?) {
        sessionFlow.update {
            it.copy(
                walkedHikeId = null,
                walkedHikeTitle = "",
                phase = null,
                startNotice = null,
                offTrailQuestion = null,
                lastEndedWalk = lastEnded,
                isJunctionWarningsOffered = false,
                lastJunctionCue = null
            )
        }
        progressFlow.value = TrailWalkProgressState()
    }
}
