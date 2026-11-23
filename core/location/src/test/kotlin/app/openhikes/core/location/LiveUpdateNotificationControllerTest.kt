package app.openhikes.core.location

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveUpdateNotificationControllerTest {
    private lateinit var context: Context
    private lateinit var clock: TestClock
    private lateinit var poster: RecordingTestPoster
    private lateinit var controller: LiveUpdateNotificationController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = TestClock()
        poster = RecordingTestPoster()
        controller = LiveUpdateNotificationController(
            builder = RecordingNotificationBuilder(context),
            poster = poster,
            clock = clock
        )
    }

    @Test
    fun buildsAndroid16ProgressStyleWithPromotedFlagAndNoRemoteViews() {
        controller.onRecordingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = "rec-1",
                title = "Morning Hike – Jun 15, 2026",
                runState = LiveUpdateRunState.RECORDING,
                distanceMeters = 1_250.0,
                elevationGainMeters = 140.0,
                timerStartEpochMillis = clock.now().toEpochMilli()
            )
        )

        val (_, notification) = poster.posted.single()
        @Suppress("DEPRECATION")
        assertThat(notification.contentView).isNull()
        @Suppress("DEPRECATION")
        assertThat(notification.bigContentView).isNull()
        assertThat(notification.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING)).isTrue()
        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE))
            .isEqualTo("android.app.Notification\$ProgressStyle")
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)

        val actionTitles = (0 until NotificationCompat.getActionCount(notification))
            .mapNotNull { index -> NotificationCompat.getAction(notification, index)?.title?.toString() }
        assertThat(actionTitles).containsExactly("Pause")
    }

    @Test
    fun pausedNotificationExposesResumeActionOnlyAndRespectsPromotedToggle() {
        controller.onRecordingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = "rec-2",
                title = "Afternoon Hike",
                runState = LiveUpdateRunState.PAUSED,
                distanceMeters = 800.0
            )
        )
        val pausedNotification = poster.posted.last().second
        val pausedActions = (0 until NotificationCompat.getActionCount(pausedNotification))
            .mapNotNull { index -> NotificationCompat.getAction(pausedNotification, index)?.title?.toString() }
        assertThat(pausedActions).containsExactly("Resume")

        controller.onPreferencesChanged(liveUpdatesEnabled = false, distanceUnit = DistanceUnit.IMPERIAL)
        val updated = poster.posted.last().second
        assertThat(updated.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING)).isFalse()
    }

    @Test
    fun recordingOutranksFollowingAndRestoresFollowingOnRecordingEnd() {
        controller.onFollowingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.FOLLOWING,
                sessionId = "follow-1",
                title = "Thumsee Loop",
                runState = LiveUpdateRunState.FOLLOWING,
                distanceMeters = 450.0,
                progressPercent = 25
            )
        )
        assertThat(poster.posted).hasSize(1)

        clock.advanceBy(Duration.ofSeconds(1))
        controller.onRecordingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = "rec-1",
                title = "Live Recording",
                runState = LiveUpdateRunState.RECORDING,
                distanceMeters = 100.0
            )
        )
        assertThat(poster.posted).hasSize(2)
        assertThat(poster.posted.last().second.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("Live Recording")

        clock.advanceBy(Duration.ofSeconds(25))
        controller.onFollowingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.FOLLOWING,
                sessionId = "follow-1",
                title = "Thumsee Loop",
                runState = LiveUpdateRunState.FOLLOWING,
                distanceMeters = 900.0,
                progressPercent = 45
            )
        )
        assertThat(poster.posted).hasSize(2)

        controller.endRecording()
        assertThat(poster.posted).hasSize(3)
        assertThat(poster.posted.last().second.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("Thumsee Loop")

        controller.endFollowing()
        assertThat(poster.cancelCount).isEqualTo(1)
    }

    @Test
    fun buildInitialForegroundNotificationPreservesActiveSessionPayloadAndChronometer() {
        val startMillis = clock.now().toEpochMilli()
        controller.onRecordingUpdate(
            LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = "rec-resume-1",
                title = "Morning Hike – Jun 15, 2026",
                runState = LiveUpdateRunState.RECORDING,
                distanceMeters = 1_250.0,
                elevationGainMeters = 140.0,
                timerStartEpochMillis = startMillis
            )
        )

        val foregroundNotification = controller.buildInitialForegroundNotification(
            title = "Morning Hike – Jun 15, 2026",
            sessionId = "rec-resume-1"
        )

        assertThat(foregroundNotification.extras.getString(Notification.EXTRA_SUB_TEXT)).isEqualTo("Recording")
        assertThat(foregroundNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .contains("1.2 km")
        assertThat(foregroundNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)).isTrue()
        assertThat(foregroundNotification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN)).isTrue()
        assertThat(foregroundNotification.`when`).isEqualTo(startMillis)
    }

    @Test
    fun followingNotificationShowsEtaProgressAndPauseResumeOnlyWithoutDestructiveEnd() {
        val following = controller.buildInitialFollowingForegroundNotification(
            LiveUpdatePayload(
                kind = LiveUpdateKind.FOLLOWING,
                sessionId = "walk-h1",
                title = "Thumsee Loop",
                runState = LiveUpdateRunState.FOLLOWING,
                distanceMeters = 3_200.0,
                progressPercent = 42,
                remainingSeconds = 2_700.0,
                hikeId = "h1"
            )
        )
        assertThat(following.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("3.2 km left")
        assertThat(following.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("45m")
        val actions = (0 until NotificationCompat.getActionCount(following))
            .mapNotNull { index -> NotificationCompat.getAction(following, index)?.title?.toString() }
        assertThat(actions).containsExactly("Pause")
    }

    private class RecordingTestPoster : NotificationPoster {
        val posted = mutableListOf<Pair<Int, Notification>>()
        var cancelCount = 0

        override fun canPostPromotedNotifications(): Boolean = true

        override fun post(notificationId: Int, notification: Notification) {
            posted.add(notificationId to notification)
        }

        override fun cancel(notificationId: Int) {
            cancelCount += 1
        }
    }
}
