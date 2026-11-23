package app.openhikes.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LiveUpdateArbiter
import app.openhikes.core.model.LiveUpdateDecision
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam for posting and cancelling platform Live Update notifications without binding tests
 * directly to [NotificationManagerCompat].
 */
interface NotificationPoster {
    fun canPostPromotedNotifications(): Boolean

    fun post(notificationId: Int, notification: Notification)

    fun cancel(notificationId: Int)
}

@Singleton
internal class AndroidNotificationPoster @Inject constructor(@ApplicationContext private val context: Context) :
    NotificationPoster {
    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    override fun canPostPromotedNotifications(): Boolean = manager.canPostPromotedNotifications()

    @SuppressLint("MissingPermission")
    override fun post(notificationId: Int, notification: Notification) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted || !manager.areNotificationsEnabled()) return
        manager.notify(notificationId, notification)
    }

    override fun cancel(notificationId: Int) {
        manager.cancel(notificationId)
    }
}

/**
 * Mediates Live Update precedence, throttling, and preference toggling across live recording
 * and trail-following notifications.
 */
@Singleton
class LiveUpdateNotificationController @Inject constructor(
    private val builder: RecordingNotificationBuilder,
    private val poster: NotificationPoster,
    private val clock: OpenHikesClock
) {
    private val lock = Any()
    private val arbiter = LiveUpdateArbiter()
    private var liveUpdatesEnabled: Boolean = true
    private var distanceUnit: DistanceUnit = DistanceUnit.METRIC
    private var lastDisplayedPayload: LiveUpdatePayload? = null

    fun buildInitialForegroundNotification(title: String, sessionId: String = "pending"): Notification {
        val payload = LiveUpdatePayload(
            kind = LiveUpdateKind.RECORDING,
            sessionId = sessionId,
            title = title,
            runState = LiveUpdateRunState.FINDING_GPS,
            distanceMeters = 0.0
        )
        return synchronized(lock) {
            lastDisplayedPayload = payload
            arbiter.submit(payload, clock.now().toEpochMilli())
            builder.build(
                payload = payload,
                distanceUnit = distanceUnit,
                requestPromoted = liveUpdatesEnabled && poster.canPostPromotedNotifications()
            )
        }
    }

    fun onRecordingUpdate(payload: LiveUpdatePayload) {
        submit(payload.copy(kind = LiveUpdateKind.RECORDING))
    }

    fun onFollowingUpdate(payload: LiveUpdatePayload) {
        submit(payload.copy(kind = LiveUpdateKind.FOLLOWING))
    }

    fun onPreferencesChanged(liveUpdatesEnabled: Boolean, distanceUnit: DistanceUnit) {
        val toRepublish = synchronized(lock) {
            val changed = this.liveUpdatesEnabled != liveUpdatesEnabled || this.distanceUnit != distanceUnit
            this.liveUpdatesEnabled = liveUpdatesEnabled
            this.distanceUnit = distanceUnit
            if (changed) lastDisplayedPayload else null
        } ?: return

        val notification = builder.build(
            payload = toRepublish,
            distanceUnit = distanceUnit,
            requestPromoted = liveUpdatesEnabled && poster.canPostPromotedNotifications()
        )
        poster.post(RecordingNotificationBuilder.NOTIFICATION_ID, notification)
    }

    fun endRecording() {
        end(LiveUpdateKind.RECORDING)
    }

    fun endFollowing() {
        end(LiveUpdateKind.FOLLOWING)
    }

    private fun submit(payload: LiveUpdatePayload) {
        val decision = synchronized(lock) {
            val result = arbiter.submit(payload, clock.now().toEpochMilli())
            if (result == LiveUpdateDecision.POST_IMMEDIATE) {
                lastDisplayedPayload = payload
            }
            result
        }
        if (decision != LiveUpdateDecision.POST_IMMEDIATE) return
        val notification = builder.build(
            payload = payload,
            distanceUnit = distanceUnit,
            requestPromoted = liveUpdatesEnabled && poster.canPostPromotedNotifications()
        )
        poster.post(RecordingNotificationBuilder.NOTIFICATION_ID, notification)
    }

    private fun end(kind: LiveUpdateKind) {
        val (restored, unit, promoted) = synchronized(lock) {
            val fallback = arbiter.end(kind, clock.now().toEpochMilli())
            lastDisplayedPayload = fallback
            Triple(
                fallback,
                distanceUnit,
                liveUpdatesEnabled && poster.canPostPromotedNotifications()
            )
        }
        if (restored != null) {
            poster.post(
                RecordingNotificationBuilder.NOTIFICATION_ID,
                builder.build(restored, unit, promoted)
            )
        } else if (arbiter.activeKind == null) {
            poster.cancel(RecordingNotificationBuilder.NOTIFICATION_ID)
        }
    }
}
