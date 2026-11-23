package app.openhikes.core.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.openhikes.core.common.MainDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.TrailWalkPhase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Location [Service] keeping active GPS trail recording and background trail following alive
 * across background transitions while hosting the ongoing Android 16.0-safe Live Update notification.
 */
@AndroidEntryPoint
class RecordingForegroundService : Service() {
    @Inject
    lateinit var recordingController: RecordingController

    @Inject
    lateinit var trailWalkController: TrailWalkController

    @Inject
    lateinit var notificationController: LiveUpdateNotificationController

    @Inject
    lateinit var clock: OpenHikesClock

    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    private var serviceScope: CoroutineScope? = null
    private var stateObservationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> serviceScope?.launch {
                if (recordingController.state.value.isActive) {
                    recordingController.pause()
                } else if (trailWalkController.sessionState.value.isWalkActive) {
                    trailWalkController.pause()
                }
            }
            ACTION_RESUME -> serviceScope?.launch {
                if (recordingController.state.value.isActive) {
                    recordingController.resume()
                } else if (trailWalkController.sessionState.value.isWalkActive) {
                    trailWalkController.resume()
                }
            }
            ACTION_STOP_SERVICE -> stopForegroundService()
            else -> handleStart()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateObservationJob?.cancel()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    private fun handleStart() {
        val state = recordingController.state.value
        val walkSession = trailWalkController.sessionState.value
        val notification = if (!state.isActive && walkSession.isWalkActive) {
            val walkProgress = trailWalkController.progressState.value
            val hikeId = walkSession.walkedHikeId?.value
            notificationController.buildInitialFollowingForegroundNotification(
                LiveUpdatePayload(
                    kind = LiveUpdateKind.FOLLOWING,
                    sessionId = "walk-${hikeId.orEmpty()}",
                    title = walkSession.walkedHikeTitle.ifBlank { DEFAULT_WALK_TITLE },
                    runState = if (walkSession.phase == TrailWalkPhase.PAUSED) {
                        LiveUpdateRunState.PAUSED
                    } else {
                        LiveUpdateRunState.FOLLOWING
                    },
                    distanceMeters = walkProgress.metersLeft,
                    isOnRoute = walkProgress.isOnRoute,
                    progressPercent = (walkProgress.coveredFraction * PERCENT_SCALE).roundToInt(),
                    remainingSeconds = walkProgress.secondsLeft,
                    hikeId = hikeId
                )
            )
        } else {
            val stats = recordingController.stats.value
            val title = state.draftTitle
                ?: RecordingTitle.defaultTitle(state.sessionStartedAtEpochMillis ?: clock.now().toEpochMilli())
            val sessionId = state.sessionId ?: "pending"
            val fallbackPayload = state.sessionId?.let { activeSessionId ->
                toFallbackRunState(state)?.let { runState ->
                    LiveUpdatePayload(
                        kind = LiveUpdateKind.RECORDING,
                        sessionId = activeSessionId,
                        title = title,
                        runState = runState,
                        distanceMeters = stats.distanceMeters,
                        elevationGainMeters = stats.elevationGainMeters,
                        averageSpeedMps = stats.averageSpeedMps,
                        timerStartEpochMillis = state.sessionStartedAtEpochMillis
                    )
                }
            }
            notificationController.buildInitialForegroundNotification(
                title = title,
                sessionId = sessionId,
                fallbackPayload = fallbackPayload
            )
        }
        ServiceCompat.startForeground(
            this,
            RecordingNotificationBuilder.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        observeSessionCompletion()
    }

    private fun toFallbackRunState(state: RecordingSessionState): LiveUpdateRunState? = when (state.phase) {
        RecordingPhase.WAITING_FOR_FIX -> LiveUpdateRunState.FINDING_GPS
        RecordingPhase.RECORDING -> LiveUpdateRunState.RECORDING
        RecordingPhase.PAUSED -> if (state.isAutoPaused) LiveUpdateRunState.AUTO_PAUSED else LiveUpdateRunState.PAUSED
        RecordingPhase.SAVING -> LiveUpdateRunState.SAVING
        RecordingPhase.IDLE,
        RecordingPhase.RECOVERING,
        RecordingPhase.FAILED -> null
    }

    private fun observeSessionCompletion() {
        if (stateObservationJob?.isActive == true) return
        stateObservationJob = serviceScope?.launch {
            combine(
                recordingController.state,
                trailWalkController.sessionState
            ) { recState, walkState ->
                recState.isActive || walkState.isWalkActive
            }.collect { anyActive ->
                if (!anyActive) {
                    stopForegroundService()
                }
            }
        }
    }

    private fun stopForegroundService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val DEFAULT_WALK_TITLE = "Hike"
        private const val PERCENT_SCALE = 100.0
        const val ACTION_START = "app.openhikes.location.action.START_RECORDING"
        const val ACTION_PAUSE = "app.openhikes.location.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "app.openhikes.location.action.RESUME_RECORDING"
        const val ACTION_STOP_SERVICE = "app.openhikes.location.action.STOP_SERVICE"
        const val EXTRA_OPEN_RECORDING = "app.openhikes.location.extra.OPEN_RECORDING"
        const val EXTRA_OPEN_WALK_HIKE_ID = "app.openhikes.location.extra.OPEN_WALK_HIKE_ID"
    }
}
