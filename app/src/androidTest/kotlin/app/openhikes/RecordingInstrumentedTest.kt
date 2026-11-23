package app.openhikes

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingNotificationBuilder
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class RecordingInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var locationFeed: FakeLocationFeed

    @Inject
    lateinit var recordingController: RecordingController

    @Inject
    lateinit var notificationBuilder: RecordingNotificationBuilder

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun notificationBuilderUsesAndroid16ProgressStyleAndSafeActions() {
        val notification = notificationBuilder.build(
            payload = LiveUpdatePayload(
                kind = LiveUpdateKind.RECORDING,
                sessionId = "inst-1",
                title = "Morning Hike",
                runState = LiveUpdateRunState.RECORDING,
                distanceMeters = 520.0,
                timerStartEpochMillis = clock.now().toEpochMilli()
            ),
            distanceUnit = DistanceUnit.METRIC,
            requestPromoted = true
        )
        @Suppress("DEPRECATION")
        assertThat(notification.contentView).isNull()
        @Suppress("DEPRECATION")
        assertThat(notification.bigContentView).isNull()
        assertThat(notification.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING)).isTrue()
        assertThat(notification.extras.getString(Notification.EXTRA_TEMPLATE))
            .isEqualTo("android.app.Notification\$ProgressStyle")
        assertThat(NotificationCompat.getActionCount(notification)).isEqualTo(1)
        assertThat(NotificationCompat.getAction(notification, 0)?.title?.toString()).isEqualTo("Pause")
    }

    @Test
    fun liveRecordingFlowPassesAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
            putExtra(LaunchConfigReader.EXTRA_DISABLE_AUTO_RECOVERY, "true")
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORD_BUTTON), 5_000)
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORD_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_SCREEN), 5_000)
            composeRule.waitUntil(5_000) { recordingController.state.value.phase == RecordingPhase.WAITING_FOR_FIX }

            runBlocking {
                locationFeed.emitFix(
                    LocationFix(
                        latitude = 47.71842,
                        longitude = 12.83177,
                        timestampEpochMillis = clock.now().toEpochMilli(),
                        horizontalAccuracyMeters = 4.0,
                        elevationMeters = 530.0,
                        verticalAccuracyMeters = 3.0
                    )
                )
                clock.advanceBy(Duration.ofSeconds(10))
                locationFeed.emitFix(
                    LocationFix(
                        latitude = 47.71900,
                        longitude = 12.83177,
                        timestampEpochMillis = clock.now().toEpochMilli(),
                        horizontalAccuracyMeters = 4.0,
                        elevationMeters = 538.0,
                        verticalAccuracyMeters = 3.0
                    )
                )
            }
            composeRule.waitUntil(5_000) { recordingController.stats.value.pointCount == 2 }
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON))
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_DIALOG))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN), 5_000)
            composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CHART).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
