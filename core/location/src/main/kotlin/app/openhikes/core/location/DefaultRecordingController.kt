package app.openhikes.core.location

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RecordingTraceSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [RecordingController] coordinating live location ingestion, crash-safe [TrackJournal]
 * writes, auto-pause, energy throttling, draft persistence, and Live Update notifications.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
internal class DefaultRecordingController @Inject constructor(
    private val clock: OpenHikesClock,
    private val locationFeed: LocationFeed,
    motionFeed: MotionFeed,
    powerMonitor: DevicePowerMonitor,
    userSettingsRepository: UserSettingsRepository,
    hikeRepository: HikeRepository,
    private val journal: TrackJournal,
    notificationController: LiveUpdateNotificationController,
    serviceBridge: RecordingServiceBridge,
    launchConfigHolder: LaunchConfigHolder,
    @ApplicationScope private val applicationScope: CoroutineScope
) : RecordingController {
    private val engineMutex = Mutex()
    private val engineContext = applicationScope.coroutineContext.minusKey(Job)
    private val journalQueue = SerialTaskQueue(applicationScope)
    private val runtime = RecordingSessionRuntime(
        clock = clock,
        locationFeed = locationFeed,
        motionFeed = motionFeed,
        notificationController = notificationController,
        serviceBridge = serviceBridge
    )
    private val autoPauseSupport: RecordingAutoPauseSupport = RecordingAutoPauseSupport(
        runtime = runtime,
        engineMutex = engineMutex,
        clock = clock,
        journal = journal,
        journalQueue = journalQueue,
        locationFeed = locationFeed,
        userSettingsRepository = userSettingsRepository,
        launchConfigHolder = launchConfigHolder,
        scope = applicationScope,
        onReplayFix = { fix, receivedAt -> lifecycleSupport.acceptFix(fix, receivedAt) }
    )
    private val lifecycleSupport: RecordingLifecycleSupport = RecordingLifecycleSupport(
        runtime = runtime,
        autoPauseSupport = autoPauseSupport,
        clock = clock,
        hikeRepository = hikeRepository,
        locationFeed = locationFeed,
        motionFeed = motionFeed,
        journal = journal,
        journalQueue = journalQueue,
        serviceBridge = serviceBridge
    )
    private val recoverySupport: RecordingRecoverySupport = RecordingRecoverySupport(
        runtime = runtime,
        clock = clock,
        hikeRepository = hikeRepository,
        locationFeed = locationFeed,
        motionFeed = motionFeed,
        journal = journal,
        serviceBridge = serviceBridge,
        finalizeSession = { session, customName -> lifecycleSupport.finishPreparedSession(session, customName) }
    )

    override val state: StateFlow<RecordingSessionState> = runtime.stateFlow
    override val stats: StateFlow<RecordingStatsSnapshot> = runtime.statsFlow
    override val trace: StateFlow<RecordingTraceSnapshot> = runtime.traceFlow
    override val completedSave: StateFlow<CompletedRecordingSave?> = runtime.completedSaveFlow.asStateFlow()

    init {
        applicationScope.launch {
            locationFeed.fixes.collect { fix ->
                engineMutex.withLock {
                    lifecycleSupport.acceptFix(fix, clock.now().toEpochMilli())
                }
            }
        }
        applicationScope.launch {
            motionFeed.motionState.collect { motion ->
                engineMutex.withLock {
                    runtime.latestMotionState = motion
                }
            }
        }
        applicationScope.launch {
            powerMonitor.powerState.collect { power ->
                engineMutex.withLock {
                    runtime.lastPowerState = power
                    if (runtime.stateFlow.value.isActive) {
                        runtime.applyEnergyProfile(autoPauseSupport.isAutoPaused)
                    }
                }
            }
        }
        applicationScope.launch {
            userSettingsRepository.userSettings.collect { settings ->
                notificationController.onPreferencesChanged(
                    liveUpdatesEnabled = settings.liveUpdatesEnabled,
                    distanceUnit = settings.distanceUnit
                )
            }
        }
        applicationScope.launch {
            journal.backgroundFlushFailures.collect { error ->
                engineMutex.withLock {
                    if (runtime.stateFlow.value.isActive) {
                        runtime.fail(RecordingFailure.Storage(error.message ?: "Journal flush failed"))
                    }
                }
            }
        }
    }

    override fun elapsedSeconds(): Double = runtime.elapsedSeconds()

    override fun photoCoordinateAt(shutterEpochMillis: Long?): GeoCoordinate? =
        runtime.photoCoordinateAt(shutterEpochMillis)

    override fun consumeCompletedSave(hikeId: HikeId) {
        if (runtime.completedSaveFlow.value?.hikeId == hikeId) {
            runtime.completedSaveFlow.value = null
        }
    }

    override suspend fun start() = withContext(engineContext) {
        engineMutex.withLock {
            val current = runtime.stateFlow.value
            if (current.phase == RecordingPhase.FAILED && current.sessionId == null) {
                runtime.resetSession()
            }
            if (runtime.stateFlow.value.phase != RecordingPhase.IDLE) return@withLock
            runtime.completedSaveFlow.value = null
            runtime.startRequested = true
            runtime.stateFlow.value = runtime.stateFlow.value.copy(
                phase = RecordingPhase.WAITING_FOR_FIX,
                failure = null
            )
            lifecycleSupport.activateSessionIfPossible()
        }
    }

    override suspend fun onPermissionResult(grantedFine: Boolean) = withContext(engineContext) {
        engineMutex.withLock {
            locationFeed.refreshPermissionStatus()
            val current = runtime.stateFlow.value
            if (grantedFine && locationFeed.permissionStatus.value == LocationPermissionStatus.GRANTED_FINE) {
                if (current.phase == RecordingPhase.FAILED && current.sessionId == null) {
                    runtime.resetSession()
                }
                if (runtime.stateFlow.value.phase == RecordingPhase.IDLE) {
                    runtime.completedSaveFlow.value = null
                    runtime.startRequested = true
                    runtime.stateFlow.value = runtime.stateFlow.value.copy(
                        phase = RecordingPhase.WAITING_FOR_FIX,
                        failure = null
                    )
                }
                if (runtime.startRequested && runtime.stateFlow.value.sessionId == null) {
                    lifecycleSupport.activateSessionIfPossible()
                }
            } else if (
                current.sessionId == null ||
                runtime.startRequested ||
                current.phase == RecordingPhase.WAITING_FOR_FIX
            ) {
                val failure = if (locationFeed.permissionStatus.value == LocationPermissionStatus.GRANTED_COARSE) {
                    RecordingFailure.PreciseLocationRequired
                } else {
                    RecordingFailure.LocationDenied
                }
                runtime.fail(failure, endLocationUpdates = current.sessionId != null)
            }
        }
    }

    override suspend fun pause() = withContext(engineContext) {
        engineMutex.withLock {
            lifecycleSupport.pause()
        }
    }

    override suspend fun resume() = withContext(engineContext) {
        engineMutex.withLock {
            lifecycleSupport.resume()
        }
    }

    override suspend fun settledStopStep(): RecordingStopStep? = withContext(engineContext) {
        engineMutex.withLock {
            lifecycleSupport.settledStopStep()
        }
    }

    override fun suggestedTitle(): String = RecordingTitle.defaultTitle(
        runtime.stateFlow.value.sessionStartedAtEpochMillis ?: clock.now().toEpochMilli()
    )

    override suspend fun stop(customName: String?, offeredTitle: String?): Result<Hike> = applicationScope.async {
        engineMutex.withLock {
            lifecycleSupport.stop(customName, offeredTitle)
        }
    }.await()

    override suspend fun retrySave(): Result<Hike> = applicationScope.async {
        engineMutex.withLock {
            lifecycleSupport.retrySave()
        }
    }.await()

    override suspend fun discard() {
        applicationScope.async {
            engineMutex.withLock {
                lifecycleSupport.discard()
            }
        }.await()
    }

    override fun dismissFailure() {
        val current = runtime.stateFlow.value
        if (current.phase != RecordingPhase.FAILED || current.canRetrySave) return
        if (current.sessionId == null) {
            runtime.resetSession()
        } else {
            runtime.stateFlow.value = current.copy(phase = RecordingPhase.PAUSED, failure = null)
        }
    }

    override fun dismissRecoveryNotice() {
        val current = runtime.stateFlow.value
        if (current.recoveryState == RecordingRecoveryState.Resumed) {
            runtime.stateFlow.value = current.copy(recoveryState = RecordingRecoveryState.Absent)
        }
    }

    override suspend fun recoverOpenSession(automaticallyResume: Boolean) = withContext(engineContext) {
        engineMutex.withLock {
            recoverySupport.recoverOpenSession(automaticallyResume)
        }
    }

    override suspend fun flushJournal() {
        applicationScope.async {
            engineMutex.withLock {
                if (!runtime.stateFlow.value.isActive) return@withLock
                journalQueue.drain()
                runCatching { journal.flush() }
                runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
            }
        }.await()
    }
}
