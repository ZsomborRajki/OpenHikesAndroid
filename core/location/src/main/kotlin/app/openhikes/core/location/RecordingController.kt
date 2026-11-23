package app.openhikes.core.location

import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RecordingTraceSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Coarse UI/lifecycle state for live trail recording, kept separate from high-frequency
 * per-fix [RecordingStatsSnapshot] and [RecordingTraceSnapshot] streams.
 */
data class RecordingSessionState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sessionId: String? = null,
    val sessionStartedAtEpochMillis: Long? = null,
    val isAutoPaused: Boolean = false,
    val energyProfile: RecordingEnergyProfile = RecordingEnergyProfile.Precise,
    val recoveryState: RecordingRecoveryState = RecordingRecoveryState.Absent,
    val failure: RecordingFailure? = null,
    val canRetrySave: Boolean = false,
    val draftTitle: String? = null
) {
    val isActive: Boolean
        get() = when (phase) {
            RecordingPhase.IDLE -> false
            RecordingPhase.RECOVERING -> true
            RecordingPhase.WAITING_FOR_FIX,
            RecordingPhase.RECORDING,
            RecordingPhase.PAUSED,
            RecordingPhase.SAVING,
            RecordingPhase.FAILED -> sessionId != null
        }

    val isCapturingFixes: Boolean
        get() = phase.isCapturingFixes

    val isStoppable: Boolean
        get() = phase.isStoppable
}

/**
 * App-scoped completion token published when a recording save finishes into [hikeId], surviving
 * temporary navigation away from the recording screen.
 */
data class CompletedRecordingSave(val sessionId: String, val hikeId: HikeId)

/**
 * App-scoped live recording engine contract shared between `:feature:recording`, `:app`,
 * and [RecordingForegroundService].
 */
@Suppress("TooManyFunctions")
interface RecordingController {
    val state: StateFlow<RecordingSessionState>
    val stats: StateFlow<RecordingStatsSnapshot>
    val trace: StateFlow<RecordingTraceSnapshot>
    val completedSave: StateFlow<CompletedRecordingSave?>

    fun elapsedSeconds(): Double

    fun consumeCompletedSave(hikeId: HikeId)

    suspend fun start()

    suspend fun onPermissionResult(grantedFine: Boolean)

    suspend fun pause()

    suspend fun resume()

    suspend fun settledStopStep(): RecordingStopStep?

    fun suggestedTitle(): String

    suspend fun stop(customName: String? = null, offeredTitle: String? = null): Result<Hike>

    suspend fun retrySave(): Result<Hike>

    suspend fun discard()

    fun dismissFailure()

    fun dismissRecoveryNotice()

    suspend fun recoverOpenSession(automaticallyResume: Boolean = true)

    suspend fun flushJournal()
}
