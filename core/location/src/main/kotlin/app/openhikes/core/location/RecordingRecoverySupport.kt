package app.openhikes.core.location

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingFixWindow
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.RecordingPreparation
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingRecoverySummary
import app.openhikes.core.model.TrackJournalSession

/**
 * Restores interrupted recording sessions from disk [TrackJournal] across app restarts and process death.
 */
@Suppress("LongParameterList")
internal class RecordingRecoverySupport(
    private val runtime: RecordingSessionRuntime,
    private val clock: OpenHikesClock,
    private val hikeRepository: HikeRepository,
    private val locationFeed: LocationFeed,
    private val motionFeed: MotionFeed,
    private val journal: TrackJournal,
    private val serviceBridge: RecordingServiceBridge,
    private val finalizeSession: suspend (TrackJournalSession, String?) -> Result<Hike>
) {
    suspend fun recoverOpenSession(automaticallyResume: Boolean) {
        val current = runtime.stateFlow.value
        val canRecover = current.phase == RecordingPhase.IDLE || current.phase == RecordingPhase.RECOVERING
        if (canRecover) {
            runtime.stateFlow.value = current.copy(phase = RecordingPhase.RECOVERING)
            val loadedSession = runCatching { journal.loadSession() }.getOrElse { error ->
                runtime.fail(
                    RecordingFailure.Storage(error.message ?: "Recovery failed"),
                    endLocationUpdates = false
                )
                return
            }
            if (loadedSession == null) {
                runCatching { hikeRepository.deleteOwnedOrphanedRecordingDrafts(exceptHikeId = null) }
                runtime.resetSession()
            } else if (prepareRecoveredRuntime(loadedSession)) {
                val normalized = RecordingPreparation.normalizedPoints(loadedSession.points)
                runtime.rebuildLiveState(normalized)
                if (loadedSession.metadata.endedAtEpochMillis != null) {
                    finalizeSession(loadedSession.copy(points = normalized), null)
                } else {
                    finishActiveRecovery(loadedSession, normalized, automaticallyResume)
                }
            }
        }
    }

    private suspend fun prepareRecoveredRuntime(session: TrackJournalSession): Boolean {
        val sessionId = session.metadata.sessionId
        runCatching { hikeRepository.deleteOwnedOrphanedRecordingDrafts(exceptHikeId = HikeId(sessionId)) }
        val draftTitle = session.metadata.title
            ?: RecordingTitle.defaultTitle(session.metadata.startedAtEpochMillis)
        val draftClaim = hikeRepository.createOrClaimRecordingDraft(
            hikeId = HikeId(sessionId),
            startedAtEpochMillis = session.metadata.startedAtEpochMillis,
            title = draftTitle
        )
        if (draftClaim.isFailure) {
            runtime.fail(
                RecordingFailure.Save(draftClaim.exceptionOrNull()?.message ?: "Draft recovery failed"),
                endLocationUpdates = false
            )
            return false
        }
        runtime.fixWindow = RecordingFixWindow(
            session.metadata.pausedIntervals.lastOrNull()?.endedAtEpochMillis
                ?: session.metadata.startedAtEpochMillis
        )
        runtime.sessionMonotonicBaseMillis = null
        runtime.stateFlow.value = runtime.stateFlow.value.copy(
            sessionId = sessionId,
            sessionStartedAtEpochMillis = session.metadata.startedAtEpochMillis,
            draftTitle = draftTitle
        )
        return true
    }

    private suspend fun finishActiveRecovery(
        session: TrackJournalSession,
        normalized: List<RecordingPoint>,
        automaticallyResume: Boolean
    ) {
        val wasPaused = session.metadata.pausedIntervals.lastOrNull()?.endedAtEpochMillis == null &&
            session.metadata.pausedIntervals.isNotEmpty()
        val isRecent = (clock.now().toEpochMilli() - session.metadata.lastUpdatedAtEpochMillis) <
            RECENT_RECOVERY_WINDOW_MILLIS
        locationFeed.refreshPermissionStatus()
        val canAutoResume = !wasPaused &&
            automaticallyResume &&
            isRecent &&
            locationFeed.permissionStatus.value == LocationPermissionStatus.GRANTED_FINE

        if (canAutoResume) {
            journal.reopenForAppending()
            val phase = if (normalized.isEmpty()) RecordingPhase.WAITING_FOR_FIX else RecordingPhase.RECORDING
            runtime.stateFlow.value = runtime.stateFlow.value.copy(
                phase = phase,
                recoveryState = RecordingRecoveryState.Resumed
            )
            motionFeed.start()
            val profile = runtime.applyEnergyProfile(isAutoPaused = false)
            locationFeed.startUpdates(profile)
            serviceBridge.ensureStarted()
            runtime.publishSnapshots()
        } else {
            val summary = RecordingRecoverySummary(
                startedAtEpochMillis = session.metadata.startedAtEpochMillis,
                lastUpdatedAtEpochMillis = session.metadata.lastUpdatedAtEpochMillis,
                distanceMeters = runtime.accumulator.distanceMeters,
                pointCount = normalized.size
            )
            runtime.stateFlow.value = runtime.stateFlow.value.copy(
                phase = RecordingPhase.PAUSED,
                recoveryState = RecordingRecoveryState.NeedsDecision(summary)
            )
            runtime.publishSnapshots()
        }
    }

    private companion object {
        const val RECENT_RECOVERY_WINDOW_MILLIS = 300_000L
    }
}
