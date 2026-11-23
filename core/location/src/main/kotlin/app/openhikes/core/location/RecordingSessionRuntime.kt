package app.openhikes.core.location

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RecordingDistanceAccumulator
import app.openhikes.core.model.RecordingEnergyPolicy
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingFixWindow
import app.openhikes.core.model.RecordingMotionState
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.TrackJournalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class PendingPreparedSave(
    val session: TrackJournalSession,
    val prepared: PreparedRecording,
    val title: String,
    val customName: String?
)

/**
 * FIFO serial executor chaining queued disk operations on [TrackJournal] so every task runs
 * strictly in enqueue order regardless of dispatcher scheduling.
 */
internal class SerialTaskQueue(private val scope: CoroutineScope) {
    private val lock = Any()
    private var tailJob: Job? = null

    fun enqueue(block: suspend () -> Unit): Job {
        var previous: Job?
        lateinit var job: Job
        synchronized(lock) {
            previous = tailJob
            job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                block()
            }
            tailJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (tailJob === job) {
                        tailJob = null
                    }
                }
            }
        }
        job.start()
        return job
    }

    suspend fun drain() {
        while (true) {
            val currentTail = synchronized(lock) { tailJob } ?: return
            currentTail.join()
            if (synchronized(lock) { tailJob === currentTail || tailJob == null }) {
                return
            }
        }
    }
}

/**
 * Shared mutable session runtime state updated exclusively on the recording engine path and
 * published through immutable [RecordingSessionState], [RecordingStatsSnapshot], and
 * [RecordingTraceSnapshot] flows.
 */
internal class RecordingSessionRuntime(
    val clock: OpenHikesClock,
    private val locationFeed: LocationFeed,
    private val motionFeed: MotionFeed,
    private val notificationController: LiveUpdateNotificationController,
    private val serviceBridge: RecordingServiceBridge
) {
    val stateFlow = MutableStateFlow(RecordingSessionState())
    val statsFlow = MutableStateFlow(RecordingStatsSnapshot())
    val traceFlow = MutableStateFlow(RecordingTraceSnapshot())
    val completedSaveFlow = MutableStateFlow<CompletedRecordingSave?>(null)

    var startRequested: Boolean = false
    var activeActivationId: String? = null
    var sessionMonotonicBaseMillis: Long? = null
    var fixWindow: RecordingFixWindow? = null
    var lastAcceptedPoint: RecordingPoint? = null
    var pendingResumeFlag: Boolean = false
    var latestMotionState: RecordingMotionState = RecordingMotionState.UNKNOWN
    var latestHorizontalAccuracyMeters: Double? = null
    var lastPowerState: DevicePowerState = DevicePowerState()
    var accumulator: RecordingDistanceAccumulator = RecordingDistanceAccumulator()
    val tracePoints: MutableList<RouteCoordinate> = ArrayList()
    var pendingPreparedSave: PendingPreparedSave? = null
    var lastOfferedTitle: String? = null

    fun elapsedSeconds(): Double {
        val baseMono = sessionMonotonicBaseMillis
        val startedAt = stateFlow.value.sessionStartedAtEpochMillis
        val elapsedMillis = when {
            baseMono != null -> clock.monotonicMillis() - baseMono
            startedAt != null -> clock.now().toEpochMilli() - startedAt
            else -> 0L
        }
        return elapsedMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    }

    fun applyEnergyProfile(isAutoPaused: Boolean): RecordingEnergyProfile {
        val profile = RecordingEnergyPolicy.profile(
            RecordingEnergyPolicy.Conditions(
                isLowPowerModeEnabled = lastPowerState.isPowerSaveMode,
                thermalSeverity = lastPowerState.thermalSeverity,
                isStationary = accumulator.isStationary,
                isAutoPaused = isAutoPaused
            )
        )
        if (stateFlow.value.energyProfile != profile) {
            stateFlow.value = stateFlow.value.copy(energyProfile = profile)
        }
        if (stateFlow.value.isCapturingFixes || isAutoPaused) {
            locationFeed.updateProfile(profile)
        }
        return profile
    }

    fun rebuildLiveState(points: List<RecordingPoint>) {
        accumulator = RecordingDistanceAccumulator()
        tracePoints.clear()
        for (point in points) {
            accumulator.append(point)
            tracePoints.add(point.toRouteCoordinate())
        }
        lastAcceptedPoint = points.lastOrNull()
        latestHorizontalAccuracyMeters = lastAcceptedPoint?.horizontalAccuracyMeters
        publishSnapshots()
    }

    fun publishSnapshots() {
        val currentState = stateFlow.value
        val statsSnapshot = accumulator.snapshot(
            pointCount = tracePoints.size,
            horizontalAccuracyMeters = latestHorizontalAccuracyMeters
        )
        statsFlow.value = statsSnapshot
        traceFlow.value = RecordingTraceSnapshot(
            hikeId = currentState.sessionId?.let(::HikeId),
            points = tracePoints.toList(),
            tintHex = RouteTint.DEFAULT_HEX
        )
        publishLiveUpdate(currentState, statsSnapshot)
    }

    fun publishLiveUpdate(currentState: RecordingSessionState, statsSnapshot: RecordingStatsSnapshot) {
        val sessionId = currentState.sessionId ?: return
        val runState = when (currentState.phase) {
            RecordingPhase.WAITING_FOR_FIX -> LiveUpdateRunState.FINDING_GPS
            RecordingPhase.RECORDING -> LiveUpdateRunState.RECORDING
            RecordingPhase.PAUSED -> if (currentState.isAutoPaused) {
                LiveUpdateRunState.AUTO_PAUSED
            } else {
                LiveUpdateRunState.PAUSED
            }
            RecordingPhase.SAVING -> LiveUpdateRunState.SAVING
            RecordingPhase.IDLE,
            RecordingPhase.RECOVERING,
            RecordingPhase.FAILED -> return
        }
        val title = currentState.draftTitle ?: "Hike"
        notificationController.onRecordingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = sessionId,
                title = title,
                runState = runState,
                distanceMeters = statsSnapshot.distanceMeters,
                elevationGainMeters = statsSnapshot.elevationGainMeters,
                averageSpeedMps = statsSnapshot.averageSpeedMps,
                timerStartEpochMillis = currentState.sessionStartedAtEpochMillis
            )
        )
    }

    fun fail(failure: RecordingFailure, endLocationUpdates: Boolean = true) {
        if (endLocationUpdates) {
            locationFeed.stopUpdates()
            motionFeed.stop()
        }
        startRequested = false
        val previous = stateFlow.value
        stateFlow.value = previous.copy(
            phase = RecordingPhase.FAILED,
            isAutoPaused = false,
            failure = failure,
            canRetrySave = pendingPreparedSave != null
        )
        notificationController.endRecording()
        serviceBridge.stopService()
    }

    fun resetSession(preserveCompletedSave: Boolean = false) {
        locationFeed.stopUpdates()
        motionFeed.stop()
        startRequested = false
        activeActivationId = null
        sessionMonotonicBaseMillis = null
        fixWindow = null
        lastAcceptedPoint = null
        pendingResumeFlag = false
        latestMotionState = RecordingMotionState.UNKNOWN
        latestHorizontalAccuracyMeters = null
        accumulator = RecordingDistanceAccumulator()
        tracePoints.clear()
        pendingPreparedSave = null
        lastOfferedTitle = null
        if (!preserveCompletedSave) {
            completedSaveFlow.value = null
        }
        stateFlow.value = RecordingSessionState()
        statsFlow.value = RecordingStatsSnapshot()
        traceFlow.value = RecordingTraceSnapshot()
        notificationController.endRecording()
        serviceBridge.stopService()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
