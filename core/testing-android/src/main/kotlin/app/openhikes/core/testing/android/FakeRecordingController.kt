package app.openhikes.core.testing.android

import app.openhikes.core.common.RecordingStopName
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.location.CompletedRecordingSave
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPreparation
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingRecoverySummary
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [RecordingController] fake for `:feature:recording` and shell UI tests.
 */
@Singleton
@Suppress("TooManyFunctions")
class FakeRecordingController(
    private val defaultStartEpochMillis: Long = DEFAULT_SUGGESTED_EPOCH_MILLIS,
    private val externalScope: CoroutineScope? = null
) : RecordingController {
    @Inject
    constructor() : this(DEFAULT_SUGGESTED_EPOCH_MILLIS, null)

    private val stateFlow = MutableStateFlow(RecordingSessionState())
    private val statsFlow = MutableStateFlow(RecordingStatsSnapshot())
    private val traceFlow = MutableStateFlow(RecordingTraceSnapshot())
    private val completedSaveFlow = MutableStateFlow<CompletedRecordingSave?>(null)

    override val state: StateFlow<RecordingSessionState> = stateFlow.asStateFlow()
    override val stats: StateFlow<RecordingStatsSnapshot> = statsFlow.asStateFlow()
    override val trace: StateFlow<RecordingTraceSnapshot> = traceFlow.asStateFlow()
    override val completedSave: StateFlow<CompletedRecordingSave?> = completedSaveFlow.asStateFlow()

    var grantedFinePermission: Boolean = true
    var simulatedElapsedSeconds: Double = 0.0
    var holdsKeepsakesForStop: Boolean = false
    var nextStopStepOverride: RecordingStopStep? = null
    var nextStopFailure: Throwable? = null
    var stopGate: CompletableDeferred<Unit>? = null

    var startCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set
    var resumeCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var retrySaveCount: Int = 0
        private set
    var discardCount: Int = 0
        private set
    var recoverCount: Int = 0
        private set
    var flushCount: Int = 0
        private set
    var lastCustomName: String? = null
        private set
    var lastOfferedTitle: String? = null
        private set

    private var sessionCounter = 1

    override fun elapsedSeconds(): Double = simulatedElapsedSeconds

    override fun consumeCompletedSave(hikeId: HikeId) {
        if (completedSaveFlow.value?.hikeId == hikeId) {
            completedSaveFlow.value = null
        }
    }

    override suspend fun start() {
        startCount += 1
        completedSaveFlow.value = null
        if (!grantedFinePermission) {
            stateFlow.value = stateFlow.value.copy(
                phase = RecordingPhase.FAILED,
                failure = RecordingFailure.LocationDenied
            )
            return
        }
        val sessionId = "fake-recording-${sessionCounter++}"
        stateFlow.value = RecordingSessionState(
            phase = RecordingPhase.WAITING_FOR_FIX,
            sessionId = sessionId,
            sessionStartedAtEpochMillis = defaultStartEpochMillis,
            draftTitle = RecordingTitle.defaultTitle(defaultStartEpochMillis)
        )
        traceFlow.value = traceFlow.value.copy(hikeId = HikeId(sessionId))
    }

    override suspend fun onPermissionResult(grantedFine: Boolean) {
        grantedFinePermission = grantedFine
        if (grantedFine) {
            start()
        } else {
            stateFlow.value = stateFlow.value.copy(
                phase = RecordingPhase.FAILED,
                failure = RecordingFailure.LocationDenied
            )
        }
    }

    override suspend fun pause() {
        pauseCount += 1
        if (!stateFlow.value.isActive) return
        stateFlow.value = stateFlow.value.copy(
            phase = RecordingPhase.PAUSED,
            isAutoPaused = false
        )
    }

    override suspend fun resume() {
        resumeCount += 1
        if (!stateFlow.value.isActive) return
        val resumedPhase = if (statsFlow.value.pointCount > 0) {
            RecordingPhase.RECORDING
        } else {
            RecordingPhase.WAITING_FOR_FIX
        }
        stateFlow.value = stateFlow.value.copy(
            phase = resumedPhase,
            isAutoPaused = false,
            recoveryState = RecordingRecoveryState.Absent,
            failure = null
        )
    }

    override suspend fun settledStopStep(): RecordingStopStep? {
        if (!stateFlow.value.isStoppable) return null
        val points = traceFlow.value.points
        val hasDrawableLeg = if (points.isNotEmpty()) {
            RecordingPreparation.hasDrawableRouteLeg(points)
        } else {
            statsFlow.value.pointCount >= MINIMUM_SAVE_POINTS
        }
        return nextStopStepOverride ?: RecordingStopStep.resolve(
            hasDrawableLeg = hasDrawableLeg,
            holdsKeepsakes = holdsKeepsakesForStop
        )
    }

    override fun suggestedTitle(): String = RecordingTitle.defaultTitle(
        stateFlow.value.sessionStartedAtEpochMillis ?: defaultStartEpochMillis
    )

    override suspend fun stop(customName: String?, offeredTitle: String?): Result<Hike> {
        val targetScope = externalScope
        return if (targetScope != null) {
            targetScope.async { executeStop(customName, offeredTitle) }.await()
        } else {
            executeStop(customName, offeredTitle)
        }
    }

    private suspend fun executeStop(customName: String?, offeredTitle: String?): Result<Hike> {
        stopCount += 1
        lastCustomName = customName
        lastOfferedTitle = offeredTitle
        stateFlow.value = stateFlow.value.copy(phase = RecordingPhase.SAVING, isAutoPaused = false)
        stopGate?.await()
        val points = traceFlow.value.points
        val hasDrawableLeg = if (points.isNotEmpty()) {
            RecordingPreparation.hasDrawableRouteLeg(points)
        } else {
            points.size >= MINIMUM_SAVE_POINTS
        }
        val injectedError = nextStopFailure
        return when {
            !hasDrawableLeg -> {
                discard()
                Result.failure(
                    IllegalStateException("Recording discarded with fewer than $MINIMUM_SAVE_POINTS points")
                )
            }
            injectedError != null -> {
                nextStopFailure = null
                stateFlow.value = stateFlow.value.copy(
                    phase = RecordingPhase.FAILED,
                    failure = RecordingFailure.Save(injectedError.message ?: "Save failed"),
                    canRetrySave = true
                )
                Result.failure(injectedError)
            }
            else -> finishWithHike(customName, offeredTitle)
        }
    }

    override suspend fun retrySave(): Result<Hike> {
        val targetScope = externalScope
        return if (targetScope != null) {
            targetScope.async { executeRetrySave() }.await()
        } else {
            executeRetrySave()
        }
    }

    private suspend fun executeRetrySave(): Result<Hike> {
        retrySaveCount += 1
        stateFlow.value = stateFlow.value.copy(phase = RecordingPhase.SAVING, failure = null)
        stopGate?.await()
        val injectedError = nextStopFailure
        if (injectedError != null) {
            nextStopFailure = null
            stateFlow.value = stateFlow.value.copy(
                phase = RecordingPhase.FAILED,
                failure = RecordingFailure.Save(injectedError.message ?: "Save failed"),
                canRetrySave = true
            )
            return Result.failure(injectedError)
        }
        return finishWithHike(lastCustomName, lastOfferedTitle)
    }

    override suspend fun discard() {
        discardCount += 1
        resetState(preserveCompletedSave = false)
    }

    override fun dismissFailure() {
        val current = stateFlow.value
        if (current.phase != RecordingPhase.FAILED || current.canRetrySave) return
        if (current.sessionId == null) {
            resetState()
        } else {
            stateFlow.value = current.copy(phase = RecordingPhase.PAUSED, failure = null)
        }
    }

    override fun dismissRecoveryNotice() {
        val current = stateFlow.value
        if (current.recoveryState == RecordingRecoveryState.Resumed) {
            stateFlow.value = current.copy(recoveryState = RecordingRecoveryState.Absent)
        }
    }

    override suspend fun recoverOpenSession(automaticallyResume: Boolean) {
        recoverCount += 1
    }

    override suspend fun flushJournal() {
        flushCount += 1
    }

    fun emitStats(snapshot: RecordingStatsSnapshot) {
        statsFlow.value = snapshot
        if (snapshot.pointCount > 0 && stateFlow.value.phase == RecordingPhase.WAITING_FOR_FIX) {
            stateFlow.value = stateFlow.value.copy(phase = RecordingPhase.RECORDING)
        }
    }

    fun emitTrace(snapshot: RecordingTraceSnapshot) {
        traceFlow.value = snapshot
    }

    fun simulateAutoPause() {
        val current = stateFlow.value
        if (!current.isActive) return
        stateFlow.value = current.copy(phase = RecordingPhase.PAUSED, isAutoPaused = true)
    }

    fun simulateRecoveryDecision(summary: RecordingRecoverySummary) {
        val sessionId = stateFlow.value.sessionId ?: "recovered-session"
        stateFlow.value = RecordingSessionState(
            phase = RecordingPhase.PAUSED,
            sessionId = sessionId,
            sessionStartedAtEpochMillis = summary.startedAtEpochMillis,
            recoveryState = RecordingRecoveryState.NeedsDecision(summary),
            draftTitle = RecordingTitle.defaultTitle(summary.startedAtEpochMillis)
        )
    }

    fun simulateFailure(failure: RecordingFailure, canRetrySave: Boolean = false) {
        stateFlow.value = stateFlow.value.copy(
            phase = RecordingPhase.FAILED,
            failure = failure,
            canRetrySave = canRetrySave
        )
    }

    fun advanceElapsedSeconds(deltaSeconds: Double) {
        simulatedElapsedSeconds += deltaSeconds
    }

    private fun finishWithHike(customName: String?, offeredTitle: String?): Result<Hike> {
        val current = stateFlow.value
        val resolvedTitle = offeredTitle ?: current.draftTitle ?: suggestedTitle()
        val resolvedCustomName = customName?.let {
            if (offeredTitle != null) RecordingStopName.typed(it, offeredTitle) else it.takeIf(String::isNotBlank)
        }
        val sessionId = current.sessionId ?: "fake-hike"
        val hikeId = HikeId(sessionId)
        val saved = Hike(
            id = hikeId,
            title = resolvedTitle,
            customName = resolvedCustomName,
            distanceMeters = statsFlow.value.distanceMeters,
            durationSeconds = maxOf(simulatedElapsedSeconds, statsFlow.value.recordedDurationSeconds),
            dateEpochMillis = current.sessionStartedAtEpochMillis ?: defaultStartEpochMillis,
            tintHex = RouteTint.DEFAULT_HEX,
            routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            linePattern = RouteLinePattern.Default,
            routeBorderHex = RouteBorder.NONE_HEX,
            route = traceFlow.value.points
        )
        completedSaveFlow.value = CompletedRecordingSave(sessionId = sessionId, hikeId = hikeId)
        resetState(preserveCompletedSave = true)
        return Result.success(saved)
    }

    private fun resetState(preserveCompletedSave: Boolean = false) {
        simulatedElapsedSeconds = 0.0
        if (!preserveCompletedSave) {
            completedSaveFlow.value = null
        }
        stateFlow.value = RecordingSessionState()
        statsFlow.value = RecordingStatsSnapshot()
        traceFlow.value = RecordingTraceSnapshot()
    }

    companion object {
        const val DEFAULT_SUGGESTED_EPOCH_MILLIS = 1_781_510_400_000L // 2026-06-15T08:00:00Z
        private const val MINIMUM_SAVE_POINTS = 2
    }
}
