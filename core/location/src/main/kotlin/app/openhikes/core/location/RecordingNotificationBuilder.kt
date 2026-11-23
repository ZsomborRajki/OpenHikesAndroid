package app.openhikes.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.ui.format.HikeMetricsFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Android 16.0-safe Live Update notifications using [NotificationCompat.ProgressStyle] and
 * [NotificationCompat.Builder.setRequestPromotedOngoing] with Pause/Resume controls only.
 */
@Singleton
class RecordingNotificationBuilder @Inject constructor(@ApplicationContext private val context: Context) {
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.recording_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(payload: LiveUpdatePayload, distanceUnit: DistanceUnit, requestPromoted: Boolean): Notification {
        ensureChannel()
        val distanceText = HikeMetricsFormatter.formatDistance(payload.distanceMeters, distanceUnit)
        val statusLabel = statusText(payload.runState)
        val metricsLine = buildMetricsLine(payload, distanceText, distanceUnit)
        val chipText = shortCriticalText(payload, distanceText)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(OpenHikesIcons.Hiking)
            .setContentTitle(payload.title)
            .setContentText(metricsLine)
            .setSubText(statusLabel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(createOpenAppIntent())
            .setShortCriticalText(chipText)
            .setRequestPromotedOngoing(requestPromoted)
            .setStyle(buildProgressStyle(payload))

        applyChronometer(builder, payload)
        addControls(builder, payload)
        return builder.build()
    }

    private fun applyChronometer(builder: NotificationCompat.Builder, payload: LiveUpdatePayload) {
        val timerStart = payload.timerStartEpochMillis
        if (payload.runState == LiveUpdateRunState.RECORDING && timerStart != null) {
            builder.setWhen(timerStart)
                .setShowWhen(true)
                .setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
                .setUsesChronometer(false)
        }
    }

    private fun addControls(builder: NotificationCompat.Builder, payload: LiveUpdatePayload) {
        if (payload.kind != LiveUpdateKind.RECORDING) return
        when (payload.runState) {
            LiveUpdateRunState.FINDING_GPS,
            LiveUpdateRunState.RECORDING -> builder.addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, OpenHikesIcons.Pause),
                    context.getString(R.string.recording_notification_action_pause),
                    createServiceActionIntent(RecordingForegroundService.ACTION_PAUSE, REQUEST_CODE_PAUSE)
                ).build()
            )
            LiveUpdateRunState.AUTO_PAUSED,
            LiveUpdateRunState.PAUSED -> builder.addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, OpenHikesIcons.PlayArrow),
                    context.getString(R.string.recording_notification_action_resume),
                    createServiceActionIntent(RecordingForegroundService.ACTION_RESUME, REQUEST_CODE_RESUME)
                ).build()
            )
            LiveUpdateRunState.SAVING,
            LiveUpdateRunState.FOLLOWING -> Unit
        }
    }

    private fun buildProgressStyle(payload: LiveUpdatePayload): NotificationCompat.ProgressStyle {
        val isActive = payload.runState == LiveUpdateRunState.RECORDING ||
            payload.runState == LiveUpdateRunState.FOLLOWING
        val segmentColor = if (isActive) COLOR_ACTIVE_SEGMENT else COLOR_PAUSED_SEGMENT
        val indeterminate = payload.runState == LiveUpdateRunState.FINDING_GPS ||
            payload.runState == LiveUpdateRunState.SAVING
        val progress = payload.progressPercent?.coerceIn(0, PROGRESS_MAX)
            ?: if (isActive) PROGRESS_MAX else 0

        return NotificationCompat.ProgressStyle()
            .setStyledByProgress(payload.kind == LiveUpdateKind.FOLLOWING)
            .setProgressIndeterminate(indeterminate)
            .setProgress(progress)
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX)
                        .setColor(segmentColor)
                )
            )
    }

    private fun statusText(runState: LiveUpdateRunState): String = when (runState) {
        LiveUpdateRunState.FINDING_GPS -> context.getString(R.string.recording_notification_state_finding_gps)
        LiveUpdateRunState.RECORDING -> context.getString(R.string.recording_notification_state_recording)
        LiveUpdateRunState.AUTO_PAUSED -> context.getString(R.string.recording_notification_state_auto_paused)
        LiveUpdateRunState.PAUSED -> context.getString(R.string.recording_notification_state_paused)
        LiveUpdateRunState.SAVING -> context.getString(R.string.recording_notification_state_saving)
        LiveUpdateRunState.FOLLOWING -> context.getString(R.string.recording_notification_state_following)
    }

    private fun buildMetricsLine(payload: LiveUpdatePayload, distanceText: String, distanceUnit: DistanceUnit): String {
        val status = statusText(payload.runState)
        val gain = payload.elevationGainMeters
        return if (gain != null && gain > 0.0) {
            context.getString(
                R.string.recording_notification_metrics_with_gain,
                distanceText,
                HikeMetricsFormatter.formatElevation(gain, distanceUnit),
                status
            )
        } else {
            context.getString(R.string.recording_notification_metrics_line, distanceText, status)
        }
    }

    private fun shortCriticalText(payload: LiveUpdatePayload, distanceText: String): String = when (payload.runState) {
        LiveUpdateRunState.FINDING_GPS -> context.getString(R.string.recording_notification_chip_gps)
        LiveUpdateRunState.PAUSED,
        LiveUpdateRunState.AUTO_PAUSED -> context.getString(R.string.recording_notification_chip_paused)
        LiveUpdateRunState.SAVING -> context.getString(R.string.recording_notification_chip_saving)
        LiveUpdateRunState.RECORDING,
        LiveUpdateRunState.FOLLOWING -> distanceText
    }

    private fun createOpenAppIntent(): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launchIntent.putExtra(RecordingForegroundService.EXTRA_OPEN_RECORDING, true)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createServiceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RecordingForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "openhikes_recording_live"
        const val NOTIFICATION_ID = 3001
        private const val PROGRESS_MAX = 100
        private const val REQUEST_CODE_OPEN_APP = 3100
        private const val REQUEST_CODE_PAUSE = 3101
        private const val REQUEST_CODE_RESUME = 3102
        private val COLOR_ACTIVE_SEGMENT = Color.rgb(27, 127, 59)
        private val COLOR_PAUSED_SEGMENT = Color.rgb(120, 128, 122)
    }
}
