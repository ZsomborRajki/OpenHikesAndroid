package app.openhikes.core.location

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.RecordingStopName
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.AltitudePolicy
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.LocationFixPolicy
import app.openhikes.core.model.RecordingDistanceAccumulator
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingFixPolicy
import app.openhikes.core.model.RecordingFixWindow
import app.openhikes.core.model.RecordingMotionState
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.RecordingPointFlags
import app.openhikes.core.model.RecordingPreparation
import app.openhikes.core.model.RecordingPreparationResult
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.TrackJournalSession
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Encapsulates recording lifecycle transitions: session activation, fix ingestion, pause/resume,
 * stop/retry finalization, and discard cleanup.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class RecordingLifecycleSupport(
    private val runtime: RecordingSessionRuntime,
    private val autoPauseSupport: RecordingAutoPauseSupport,
    private val clock: OpenHikesClock,
    private val hikeRepository: HikeRepository,
    private val locationFeed: LocationFeed,
    private val motionFeed: MotionFeed,
    private val journal: TrackJournal,
    private val journalQueue: SerialTaskQueue,
    private val serviceBridge: RecordingServiceBridge
) {
    suspend fun activateSessionIfPossible() {
        val current = runtime.stateFlow.value
        if (!runtime.startRequested || current.sessionId != null || runtime.activeActivationId != null) return
        val activationId = UUID.randomUUID().toString()
        runtime.activeActivationId = activationId
        try {
            if (!verifyFineLocationPermission()) return
            hikeRepository.deleteOwnedOrphanedRecordingDrafts(exceptHikeId = null)
            startAndClaimDraft(activationId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: TrackJournalException) {
            failActivationIfWanted(activationId, error)
        } catch (error: IllegalStateException) {
            failActivationIfWanted(activationId, error)
        } finally {
            if (runtime.activeActivationId == activationId) {
                runtime.activeActivationId = null
            }
        }
    }

    private fun failActivationIfWanted(activationId: String, error: Throwable) {
        if (isActivationStillWanted(activationId)) {
            runtime.fail(
                RecordingFailure.Storage(error.message ?: "Unable to start recording"),
                endLocationUpdates = false
            )
        }
    }

    suspend fun acceptFix(fix: LocationFix, receivedAtMillis: Long = clock.now().toEpochMilli()) {
        val currentState = runtime.stateFlow.value
        when {
            currentState.phase == RecordingPhase.PAUSED -> {
                if (autoPauseSupport.isAutoPaused) autoPauseSupport.observeWhilePaused(fix, receivedAtMillis)
            }
            currentState.isCapturingFixes && runtime.fixWindow?.admits(fix.timestampEpochMillis) == true -> {
                processLiveFix(fix, receivedAtMillis)
            }
        }
    }

    private suspend fun processLiveFix(fix: LocationFix, receivedAtMillis: Long) {
        val isCurrent = LocationFixPolicy.accepts(
            fix = fix,
            maximumAgeSeconds = LocationFixPolicy.FOREGROUND_MAXIMUM_AGE_SECONDS,
            nowEpochMillis = receivedAtMillis
        )
        if (isCurrent) runtime.latestHorizontalAccuracyMeters = fix.horizontalAccuracyMeters
        val isKept = RecordingFixPolicy.accepts(
            fix = fix,
            afterPrevious = runtime.lastAcceptedPoint,
            motionState = runtime.latestMotionState,
            nowEpochMillis = receivedAtMillis
        )
        val trustedElevation = if (isKept) {
            AltitudePolicy.trustedAltitude(fix.elevationMeters, fix.verticalAccuracyMeters)
        } else {
            null
        }
        val pausedNow = isCurrent && autoPauseSupport.observeWhileRecording(fix, trustedElevation)
        if (!pausedNow) {
            if (!isKept) {
                runtime.publishSnapshots()
            } else {
                var flags = RecordingPointFlags.NONE
                if (runtime.pendingResumeFlag) {
                    flags = flags or RecordingPointFlags.RESUMED
                    runtime.pendingResumeFlag = false
                }
                flags = when (runtime.latestMotionState) {
                    RecordingMotionState.STATIONARY -> flags or RecordingPointFlags.MOTION_STATIONARY
                    RecordingMotionState.NON_PEDESTRIAN -> flags or RecordingPointFlags.NON_PEDESTRIAN
                    RecordingMotionState.UNKNOWN, RecordingMotionState.PEDESTRIAN -> flags
                }
                keepAcceptedPoint(
                    RecordingPoint(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        timestampEpochMillis = fix.timestampEpochMillis,
                        horizontalAccuracyMeters = fix.horizontalAccuracyMeters,
                        elevationMeters = trustedElevation,
                        courseDegrees = LocationFixPolicy.course(fix),
                        speedMps = fix.speedMps?.takeIf { it.isFinite() && it >= 0.0 },
                        flags = flags
                    )
                )
            }
        }
    }

    fun pause() {
        if (autoPauseSupport.isAutoPaused) {
            autoPauseSupport.takeOverAsManualPause()
            return
        }
        val current = runtime.stateFlow.value
        if (!current.isCapturingFixes) return
        autoPauseSupport.reset()
        locationFeed.stopUpdates()
        motionFeed.stop()
        val pausedAtMillis = clock.now().toEpochMilli()
        runtime.stateFlow.value = current.copy(phase = RecordingPhase.PAUSED, isAutoPaused = false)
        runtime.applyEnergyProfile(isAutoPaused = false)
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
        journalQueue.enqueue {
            try {
                journal.pause(pausedAtMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TrackJournalException) {
                runtime.fail(RecordingFailure.Storage(error.message ?: "Pause failed"))
            }
        }
    }

    suspend fun resume() {
        val current = runtime.stateFlow.value
        if (current.phase == RecordingPhase.PAUSED) {
            autoPauseSupport.reset()
            if (verifyFineLocationPermission(endOnFail = true)) {
                val resumedAtMillis = try {
                    journalQueue.drain()
                    journal.reopenForAppending()
                    clock.now().toEpochMilli().also { journal.resume(it) }
                } catch (error: TrackJournalException) {
                    runtime.fail(RecordingFailure.Storage(error.message ?: "Resume failed"))
                    null
                }
                if (resumedAtMillis != null) {
                    activateResumedSession(resumedAtMillis)
                }
            }
        }
    }

    private suspend fun activateResumedSession(resumedAtMillis: Long) {
        runtime.pendingResumeFlag = true
        runtime.lastAcceptedPoint = null
        runtime.fixWindow?.reopen(resumedAtMillis) ?: run { runtime.fixWindow = RecordingFixWindow(resumedAtMillis) }
        val nextPhase = if (runtime.tracePoints.isEmpty()) RecordingPhase.WAITING_FOR_FIX else RecordingPhase.RECORDING
        runtime.stateFlow.value = runtime.stateFlow.value.copy(
            phase = nextPhase,
            isAutoPaused = false,
            recoveryState = RecordingRecoveryState.Absent,
            failure = null
        )
        if (!startForegroundServiceOrFail(pauseJournalOnFailure = true)) return
        motionFeed.start()
        locationFeed.startUpdates(runtime.applyEnergyProfile(isAutoPaused = false))
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
    }

    suspend fun settledStopStep(): RecordingStopStep? {
        val current = runtime.stateFlow.value
        val hasLiveDrawableLeg = RecordingPreparation.hasDrawableRouteLeg(runtime.tracePoints)
        return when {
            !current.isStoppable -> null
            hasLiveDrawableLeg -> RecordingStopStep.resolve(
                hasDrawableLeg = true,
                holdsKeepsakes = false
            )
            else -> {
                autoPauseSupport.cancelRefresh()
                journalQueue.drain()
                runCatching {
                    val points = journal.loadSession()?.points.orEmpty()
                    RecordingPreparation.hasDrawableLeg(RecordingPreparation.normalizedPoints(points))
                }.fold(
                    onSuccess = { hasDrawableLeg ->
                        RecordingStopStep.resolve(
                            hasDrawableLeg = hasDrawableLeg,
                            holdsKeepsakes = false
                        )
                    },
                    onFailure = { RecordingStopStep.CONFIRM_DISCARD }
                )
            }
        }
    }

    suspend fun stop(customName: String?, offeredTitle: String?): Result<Hike> {
        val current = runtime.stateFlow.value
        val canStop = current.isStoppable && current.sessionId != null && current.sessionStartedAtEpochMillis != null
        return if (!canStop) {
            Result.failure(IllegalStateException("No active recording is ready to stop."))
        } else {
            executeStop(current, customName, offeredTitle)
        }
    }

    private suspend fun executeStop(
        current: RecordingSessionState,
        customName: String?,
        offeredTitle: String?
    ): Result<Hike> {
        runtime.lastOfferedTitle = offeredTitle
        runtime.stateFlow.value = current.copy(phase = RecordingPhase.SAVING, isAutoPaused = false)
        autoPauseSupport.reset()
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
        journalQueue.drain()
        locationFeed.stopUpdates()
        motionFeed.stop()

        val endedAtMillis = clock.now().toEpochMilli()
        val resolvedTitle = offeredTitle?.takeIf(String::isNotBlank) ?: current.draftTitle
        val resolvedCustomName = RecordingStopName.typed(customName.orEmpty(), offeredTitle)
        return try {
            journal.reopenForAppending()
            journal.finish(
                atEpochMillis = endedAtMillis,
                title = resolvedTitle,
                customName = resolvedCustomName
            )
            val session = journal.loadSession() ?: throw TrackJournalException.Io("Missing track journal at Stop")
            finishPreparedSession(
                session = session,
                customName = resolvedCustomName
            )
        } catch (error: TrackJournalException) {
            runtime.fail(RecordingFailure.Storage(error.message ?: "Stop failed"), endLocationUpdates = false)
            Result.failure(error)
        }
    }

    suspend fun retrySave(): Result<Hike> {
        val pending = runtime.pendingPreparedSave
            ?: return Result.failure(IllegalStateException("No failed recording save is ready to retry."))
        runtime.stateFlow.value = runtime.stateFlow.value.copy(phase = RecordingPhase.SAVING, failure = null)
        return persistPreparedSave(pending)
    }

    suspend fun discard() {
        runtime.activeActivationId = null
        runtime.startRequested = false
        val sessionId = runtime.stateFlow.value.sessionId
        autoPauseSupport.reset()
        locationFeed.stopUpdates()
        motionFeed.stop()
        journalQueue.drain()
        runCatching { journal.discard() }
        if (sessionId != null) {
            runCatching { hikeRepository.deleteRecordingDraft(HikeId(sessionId)) }
        }
        runtime.resetSession()
    }

    suspend fun finishPreparedSession(session: TrackJournalSession, customName: String?): Result<Hike> {
        val prepResult = RecordingPreparation.prepare(session.points, session.metadata.startedAtEpochMillis)
        if (prepResult !is RecordingPreparationResult.Success) {
            runtime.fail(RecordingFailure.TooShort, endLocationUpdates = false)
            return Result.failure(IllegalStateException("Recording has fewer than two points."))
        }
        val title = runtime.lastOfferedTitle
            ?: session.metadata.title
            ?: RecordingTitle.defaultTitle(session.metadata.startedAtEpochMillis)
        val pending = PendingPreparedSave(
            session = session,
            prepared = prepResult.prepared,
            title = title,
            customName = customName
        )
        runtime.pendingPreparedSave = pending
        return persistPreparedSave(pending)
    }

    private suspend fun startAndClaimDraft(activationId: String) {
        val sessionId = UUID.randomUUID().toString()
        val startedAtMillis = clock.now().toEpochMilli()
        val draftTitle = RecordingTitle.defaultTitle(startedAtMillis)
        journal.start(sessionId = sessionId, startedAtEpochMillis = startedAtMillis, title = draftTitle)
        if (!isActivationStillWanted(activationId)) {
            runCatching { journal.discardIfSessionIs(sessionId) }
            return
        }
        val draftResult = hikeRepository.createOrClaimRecordingDraft(
            hikeId = HikeId(sessionId),
            startedAtEpochMillis = startedAtMillis,
            title = draftTitle
        )
        if (draftResult.isFailure || !isActivationStillWanted(activationId)) {
            runCatching { journal.discardIfSessionIs(sessionId) }
            if (draftResult.isSuccess) runCatching { hikeRepository.deleteRecordingDraft(HikeId(sessionId)) }
            if (isActivationStillWanted(activationId)) {
                runtime.fail(
                    RecordingFailure.Save(draftResult.exceptionOrNull()?.message ?: "Draft creation failed"),
                    endLocationUpdates = false
                )
            }
            return
        }
        initializeActiveSession(sessionId, startedAtMillis, draftTitle)
    }

    private fun verifyFineLocationPermission(endOnFail: Boolean = false): Boolean {
        locationFeed.refreshPermissionStatus()
        return when (locationFeed.permissionStatus.value) {
            LocationPermissionStatus.GRANTED_FINE -> true
            LocationPermissionStatus.GRANTED_COARSE -> {
                runtime.fail(RecordingFailure.PreciseLocationRequired, endLocationUpdates = endOnFail)
                false
            }
            LocationPermissionStatus.DENIED -> {
                runtime.fail(RecordingFailure.LocationDenied, endLocationUpdates = endOnFail)
                false
            }
        }
    }

    private suspend fun persistPreparedSave(pending: PendingPreparedSave): Result<Hike> {
        val sessionId = pending.session.metadata.sessionId
        val endedAt = pending.session.metadata.endedAtEpochMillis ?: clock.now().toEpochMilli()
        val result = hikeRepository.finalizeRecordingDraft(
            hikeId = HikeId(sessionId),
            title = pending.title,
            customName = pending.customName,
            startedAtEpochMillis = pending.session.metadata.startedAtEpochMillis,
            endedAtEpochMillis = endedAt,
            prepared = pending.prepared
        )
        return result.fold(
            onSuccess = { saved ->
                runCatching { journal.discard() }
                runtime.resetSession(preserveCompletedSave = true)
                runtime.completedSaveFlow.value = CompletedRecordingSave(
                    sessionId = sessionId,
                    hikeId = saved.id
                )
                Result.success(saved)
            },
            onFailure = { error ->
                runtime.fail(RecordingFailure.Save(error.message ?: "Unable to save hike"), endLocationUpdates = false)
                Result.failure(error)
            }
        )
    }

    private suspend fun initializeActiveSession(sessionId: String, startedAtMillis: Long, draftTitle: String) {
        autoPauseSupport.reset()
        runtime.pendingPreparedSave = null
        runtime.lastOfferedTitle = null
        runtime.pendingResumeFlag = false
        runtime.lastAcceptedPoint = null
        runtime.latestHorizontalAccuracyMeters = null
        runtime.fixWindow = RecordingFixWindow(startedAtMillis)
        runtime.sessionMonotonicBaseMillis = clock.monotonicMillis()
        runtime.accumulator = RecordingDistanceAccumulator()
        runtime.tracePoints.clear()
        runtime.stateFlow.value = RecordingSessionState(
            phase = RecordingPhase.WAITING_FOR_FIX,
            sessionId = sessionId,
            sessionStartedAtEpochMillis = startedAtMillis,
            draftTitle = draftTitle
        )
        if (!startForegroundServiceOrFail(pauseJournalOnFailure = true)) return
        motionFeed.start()
        locationFeed.startUpdates(runtime.applyEnergyProfile(isAutoPaused = false))
        runtime.publishSnapshots()
    }

    private suspend fun startForegroundServiceOrFail(pauseJournalOnFailure: Boolean): Boolean = try {
        serviceBridge.ensureStarted()
        true
    } catch (error: IllegalStateException) {
        handleForegroundServiceStartFailure(error, pauseJournalOnFailure)
        false
    } catch (error: SecurityException) {
        handleForegroundServiceStartFailure(error, pauseJournalOnFailure)
        false
    }

    private suspend fun handleForegroundServiceStartFailure(error: RuntimeException, pauseJournalOnFailure: Boolean) {
        locationFeed.stopUpdates()
        motionFeed.stop()
        if (pauseJournalOnFailure) {
            try {
                journal.pause(clock.now().toEpochMilli())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TrackJournalException) {
                // Keep the draft session on disk even if marking the pause interval fails.
            }
        }
        runtime.fail(
            RecordingFailure.Storage(error.message ?: "Unable to start location service"),
            endLocationUpdates = true
        )
    }

    private fun keepAcceptedPoint(point: RecordingPoint) {
        runtime.accumulator.append(point)
        runtime.lastAcceptedPoint = point
        runtime.latestHorizontalAccuracyMeters = point.horizontalAccuracyMeters
        runtime.tracePoints.add(point.toRouteCoordinate())
        val currentState = runtime.stateFlow.value
        if (currentState.phase != RecordingPhase.RECORDING) {
            runtime.stateFlow.value = currentState.copy(phase = RecordingPhase.RECORDING)
        }
        runtime.applyEnergyProfile(isAutoPaused = false)
        runtime.publishSnapshots()
        journalQueue.enqueue {
            try {
                journal.append(point)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TrackJournalException) {
                runtime.fail(RecordingFailure.Storage(error.message ?: "Failed to journal track point"))
            }
        }
    }

    private fun isActivationStillWanted(activationId: String): Boolean = runtime.activeActivationId == activationId &&
        runtime.startRequested &&
        runtime.stateFlow.value.sessionId == null
}
