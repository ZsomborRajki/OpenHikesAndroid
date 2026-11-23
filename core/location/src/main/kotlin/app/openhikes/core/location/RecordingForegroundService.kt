package app.openhikes.core.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import app.openhikes.core.common.MainDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.model.RecordingPhase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Location [Service] keeping active GPS trail recording alive across background transitions and
 * hosting the ongoing Android 16.0-safe Live Update notification.
 */
@AndroidEntryPoint
class RecordingForegroundService : Service() {
    @Inject
    lateinit var recordingController: RecordingController

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
            ACTION_PAUSE -> serviceScope?.launch { recordingController.pause() }
            ACTION_RESUME -> serviceScope?.launch { recordingController.resume() }
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
        val title = state.draftTitle
            ?: RecordingTitle.defaultTitle(state.sessionStartedAtEpochMillis ?: clock.now().toEpochMilli())
        val notification = notificationController.buildInitialForegroundNotification(
            title = title,
            sessionId = state.sessionId ?: "pending"
        )
        ServiceCompat.startForeground(
            this,
            RecordingNotificationBuilder.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        observeRecordingCompletion()
    }

    private fun observeRecordingCompletion() {
        if (stateObservationJob?.isActive == true) return
        stateObservationJob = serviceScope?.launch {
            recordingController.state.collect { state ->
                if (state.phase == RecordingPhase.IDLE && !state.isActive) {
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
        const val ACTION_START = "app.openhikes.location.action.START_RECORDING"
        const val ACTION_PAUSE = "app.openhikes.location.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "app.openhikes.location.action.RESUME_RECORDING"
        const val ACTION_STOP_SERVICE = "app.openhikes.location.action.STOP_SERVICE"
        const val EXTRA_OPEN_RECORDING = "app.openhikes.location.extra.OPEN_RECORDING"
    }
}
