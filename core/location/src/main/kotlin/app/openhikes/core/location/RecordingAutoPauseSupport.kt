package app.openhikes.core.location

import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.AutoPausePolicy
import app.openhikes.core.model.AutoPauseWatch
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.LocationFixPolicy
import app.openhikes.core.model.RecordingFixWindow
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPreparation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges [AutoPauseWatch] decisions with [TrackJournal] backdated point truncation, timer ticks,
 * and held-fix replay when the hiker resumes walking.
 */
@Suppress("LongParameterList")
internal class RecordingAutoPauseSupport(
    private val runtime: RecordingSessionRuntime,
    private val engineMutex: Mutex,
    private val clock: OpenHikesClock,
    private val journal: TrackJournal,
    private val journalQueue: SerialTaskQueue,
    private val locationFeed: LocationFeed,
    private val userSettingsRepository: UserSettingsRepository,
    private val launchConfigHolder: LaunchConfigHolder,
    private val scope: CoroutineScope,
    private val onReplayFix: suspend (LocationFix, Long) -> Unit
) {
    private data class HeldFix(val fix: LocationFix, val receivedAtMillis: Long)

    private var watch: AutoPauseWatch = buildWatch()
    private val heldFixes = ArrayDeque<HeldFix>()
    private var timerJob: Job? = null
    private var refreshJob: Job? = null
    private var needsLiveRebuild: Boolean = false

    val isAutoPaused: Boolean
        get() = runtime.stateFlow.value.phase == RecordingPhase.PAUSED && runtime.stateFlow.value.isAutoPaused

    fun recreatePolicyIfNeeded() {
        val desiredStopSeconds = resolveStopSeconds()
        if (watch.policy.minimumStopSeconds != desiredStopSeconds) {
            watch = AutoPauseWatch(AutoPausePolicy.Standard.copy(minimumStopSeconds = desiredStopSeconds))
        }
    }

    suspend fun observeWhileRecording(fix: LocationFix, trustedElevation: Double?): Boolean {
        recreatePolicyIfNeeded()
        val enabled = runCatching { userSettingsRepository.userSettings.first().autoPauseEnabled }.getOrDefault(true)
        if (!enabled) {
            watch.reset()
            return false
        }
        val watchFix = AutoPauseWatch.Fix(
            latitude = fix.latitude,
            longitude = fix.longitude,
            timestampEpochMillis = fix.timestampEpochMillis,
            horizontalAccuracyMeters = fix.horizontalAccuracyMeters,
            elevationMeters = trustedElevation,
            speedMps = fix.speedMps,
            motion = AutoPauseWatch.Motion.from(runtime.latestMotionState)
        )
        return when (val event = watch.observe(watchFix)) {
            is AutoPauseWatch.Event.Stopped -> beginAutoPause(event.sinceEpochMillis)
            is AutoPauseWatch.Event.Departed, null -> {
                scheduleCheck()
                false
            }
        }
    }

    suspend fun observeWhilePaused(fix: LocationFix, receivedAtMillis: Long) {
        val enabled = runCatching { userSettingsRepository.userSettings.first().autoPauseEnabled }.getOrDefault(true)
        val acceptedCurrent = enabled &&
            LocationFixPolicy.accepts(
                fix = fix,
                maximumAgeSeconds = LocationFixPolicy.FOREGROUND_MAXIMUM_AGE_SECONDS,
                nowEpochMillis = receivedAtMillis
            )
        when {
            !enabled -> takeOverAsManualPause()
            acceptedCurrent -> {
                val watchFix = AutoPauseWatch.Fix(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    timestampEpochMillis = fix.timestampEpochMillis,
                    horizontalAccuracyMeters = fix.horizontalAccuracyMeters,
                    elevationMeters = null,
                    speedMps = fix.speedMps,
                    motion = AutoPauseWatch.Motion.from(runtime.latestMotionState)
                )
                heldFixes.addLast(HeldFix(fix, receivedAtMillis))
                while (heldFixes.size > MAXIMUM_HELD_FIXES) {
                    heldFixes.removeFirst()
                }
                val event = watch.observe(watchFix) as? AutoPauseWatch.Event.Departed
                if (event != null) endAutoPause(event.sinceEpochMillis)
            }
        }
    }

    fun takeOverAsManualPause() {
        if (!isAutoPaused) return
        reset()
        locationFeed.stopUpdates()
        runtime.stateFlow.value = runtime.stateFlow.value.copy(isAutoPaused = false)
        runtime.applyEnergyProfile(isAutoPaused = false)
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
    }

    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        needsLiveRebuild = false
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        cancelRefresh()
        heldFixes.clear()
        watch.reset()
        recreatePolicyIfNeeded()
    }

    private fun scheduleCheck() {
        val decisionMillis = watch.decisionEpochMillis
        val delayMillis = decisionMillis?.let { it - clock.now().toEpochMilli() } ?: 0L
        val shouldSchedule = timerJob?.isActive != true &&
            runtime.stateFlow.value.phase == RecordingPhase.RECORDING &&
            decisionMillis != null &&
            delayMillis > 0L
        if (!shouldSchedule) return

        timerJob = scope.launch {
            delay(delayMillis)
            engineMutex.withLock {
                timerJob = null
                if (runtime.stateFlow.value.phase != RecordingPhase.RECORDING) return@withLock
                val enabled = runCatching {
                    userSettingsRepository.userSettings.first().autoPauseEnabled
                }.getOrDefault(true)
                if (!enabled) return@withLock
                val event = watch.tick(
                    nowEpochMillis = clock.now().toEpochMilli(),
                    motion = AutoPauseWatch.Motion.from(runtime.latestMotionState)
                )
                if (event is AutoPauseWatch.Event.Stopped) {
                    beginAutoPause(event.sinceEpochMillis)
                } else {
                    scheduleCheck()
                }
            }
        }
    }

    private fun beginAutoPause(stoppedAtMillis: Long): Boolean {
        val sessionId = runtime.stateFlow.value.sessionId
        val shouldStart = when {
            sessionId == null -> false
            runtime.stateFlow.value.phase != RecordingPhase.RECORDING -> {
                watch.reset()
                false
            }
            else -> true
        }
        if (shouldStart && sessionId != null) {
            activateAutoPauseState(sessionId, stoppedAtMillis)
        }
        return shouldStart
    }

    private fun activateAutoPauseState(sessionId: String, stoppedAtMillis: Long) {
        timerJob?.cancel()
        timerJob = null
        heldFixes.clear()
        needsLiveRebuild = true
        runtime.stateFlow.value = runtime.stateFlow.value.copy(
            phase = RecordingPhase.PAUSED,
            isAutoPaused = true
        )
        runtime.applyEnergyProfile(isAutoPaused = true)
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
        journalQueue.enqueue {
            journal.pauseDiscardingPointsAfter(atEpochMillis = stoppedAtMillis, cutoffEpochMillis = stoppedAtMillis)
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            journalQueue.drain()
            val loaded = runCatching { journal.loadSession() }.getOrNull() ?: return@launch
            val normalized = RecordingPreparation.normalizedPoints(loaded.points)
            engineMutex.withLock {
                if (needsLiveRebuild && runtime.stateFlow.value.sessionId == sessionId) {
                    needsLiveRebuild = false
                    runtime.rebuildLiveState(normalized)
                }
            }
        }
    }

    private suspend fun endAutoPause(resumedAtMillis: Long) {
        val walkedAway = heldFixes.filter { it.fix.timestampEpochMillis >= resumedAtMillis }
        heldFixes.clear()
        refreshJob?.cancel()
        refreshJob = null
        if (needsLiveRebuild) {
            needsLiveRebuild = false
            journalQueue.drain()
            val loaded = runCatching { journal.loadSession() }.getOrNull()
            if (loaded != null) {
                runtime.rebuildLiveState(RecordingPreparation.normalizedPoints(loaded.points))
            }
        }
        runtime.pendingResumeFlag = true
        runtime.lastAcceptedPoint = null
        val existingWindow = runtime.fixWindow
        if (existingWindow != null) {
            existingWindow.reopen(resumedAtMillis)
        } else {
            runtime.fixWindow = RecordingFixWindow(resumedAtMillis)
        }
        val nextPhase = if (runtime.tracePoints.isEmpty()) RecordingPhase.WAITING_FOR_FIX else RecordingPhase.RECORDING
        runtime.stateFlow.value = runtime.stateFlow.value.copy(
            phase = nextPhase,
            isAutoPaused = false
        )
        runtime.applyEnergyProfile(isAutoPaused = false)
        journalQueue.enqueue {
            journal.resume(resumedAtMillis)
        }
        runtime.publishLiveUpdate(runtime.stateFlow.value, runtime.statsFlow.value)
        for (held in walkedAway) {
            onReplayFix(held.fix, held.receivedAtMillis)
        }
    }

    private fun resolveStopSeconds(): Double = launchConfigHolder.current.value.autoPauseSecondsOverride
        ?: AutoPausePolicy.DEFAULT_MINIMUM_STOP_SECONDS

    private fun buildWatch(): AutoPauseWatch =
        AutoPauseWatch(AutoPausePolicy.Standard.copy(minimumStopSeconds = resolveStopSeconds()))

    private companion object {
        const val MAXIMUM_HELD_FIXES = 64
    }
}
