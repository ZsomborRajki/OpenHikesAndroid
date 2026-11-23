package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.di.RecordingJournalDir
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
@Suppress("TooManyFunctions")
class RecordingJourneyTest {
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
    lateinit var trailMapRenderer: TrailMapRenderer

    @Inject
    @RecordingJournalDir
    lateinit var journalDir: File

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() = runBlocking {
        recordingController.discard()
        journalDir.deleteRecursively()
        Unit
    }

    @Test
    fun preStartFixRejectedLiveOverlayUpdatesAndPauseResumeAddsSegment() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            startRecordingFromToolbar()

            // Emit pre-start cached fix; should remain in WAITING_FOR_FIX with 0 points
            emitFix(offsetSeconds = -5L, lat = 47.71800)
            composeRule.waitForIdle()
            assertThat(recordingController.state.value.phase).isEqualTo(RecordingPhase.WAITING_FOR_FIX)
            assertThat(recordingController.stats.value.pointCount).isEqualTo(0)

            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)
            composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
                (renderer.lastLiveRecordingOverlay.value?.walkedSegments?.firstOrNull()?.size ?: 0) >= 2
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON).assertDoesNotExist()

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_RESUME_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON).assertIsDisplayed()

            clock.advanceBy(Duration.ofSeconds(15))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_RESUME_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON))
            emitFix(offsetSeconds = 0L, lat = 47.71980)
            clock.advanceBy(Duration.ofSeconds(10))
            emitFix(offsetSeconds = 0L, lat = 47.72040)

            composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) { recordingController.stats.value.segments.size == 2 }
        }
    }

    @Test
    fun discardWhilePausedReturnsToEmptyLibraryWithIntactChrome() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            startRecordingFromToolbar()
            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_DIALOG).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_CONFIRM_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_EMPTY_STATE))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsDisplayed()
        }
    }

    @Test
    fun zeroPointStopEndsImmediatelyWithoutStopDialog() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            startRecordingFromToolbar()
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_EMPTY_STATE))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_DIALOG).assertDoesNotExist()
        }
    }

    @Test
    fun stopDialogRenameKeepRecordingAndConfirmDiscardBehaveCleanly() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            startRecordingFromToolbar()
            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_DIALOG))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_KEEP_BUTTON).performClick()
            composeRule.waitUntil { recordingController.state.value.phase == RecordingPhase.RECORDING }

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_DIALOG))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_NAME_FIELD)
                .performTextReplacement("Evening Ridge Walk")
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNode(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_TITLE) and hasText("Evening Ridge Walk")
            ).assertIsDisplayed()
        }
    }

    @Test
    fun stopDialogKeepingDefaultTitleSavesOfferedTitle() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            startRecordingFromToolbar()
            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            val expectedTitle = recordingController.suggestedTitle()
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNode(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_TITLE) and hasText(expectedTitle)
            ).assertIsDisplayed()
        }
    }

    @Test
    fun failFirstSavePreservesDraftAndRetrySaveCompletesToHikeDetail() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent(failFirstSave = true)).use {
            startRecordingFromToolbar()
            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_RETRY_SAVE))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_RETRY_SAVE).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        }
    }

    @Test
    fun autoPauseDepartureSplitsTwoSegmentsVisibleOnSavedHikeDetail() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent(autoPauseSeconds = 5.0)).use {
            startRecordingFromToolbar()
            emitFix(offsetSeconds = 0L, lat = 47.71842)
            clock.advanceBy(Duration.ofSeconds(10))
            emitFix(offsetSeconds = 0L, lat = 47.71880)
            clock.advanceBy(Duration.ofSeconds(6))
            emitFix(offsetSeconds = 0L, lat = 47.71881)

            composeRule.waitUntilAtLeastOneExists(hasText("Auto-paused"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            clock.advanceBy(Duration.ofSeconds(10))
            emitFix(offsetSeconds = 0L, lat = 47.71940)
            clock.advanceBy(Duration.ofSeconds(10))
            emitFix(offsetSeconds = 0L, lat = 47.72000)

            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            val detailList = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
            detailList.performScrollToKey("segments")
            composeRule.onNodeWithTag(OpenHikesTestTags.hikeSegment(1)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.hikeSegment(2)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun liveWindowResizePreservesActiveRecordingRouteAndSessionState() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use { scenario ->
            startRecordingFromToolbar()
            emitTwoWalkedFixes()
            composeRule.waitUntilAtLeastOneExists(hasText("Recording"), timeoutMillis = WAIT_TIMEOUT_MILLIS)

            scenario.onActivity { activity ->
                RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
                activity.onConfigurationChanged(activity.resources.configuration)
            }
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_SCREEN).assertIsDisplayed()
            assertThat(recordingController.stats.value.pointCount).isEqualTo(2)
        }
    }

    private fun buildLaunchIntent(failFirstSave: Boolean = false, autoPauseSeconds: Double? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
            putExtra(LaunchConfigReader.EXTRA_DISABLE_AUTO_RECOVERY, "true")
            if (failFirstSave) putExtra(LaunchConfigReader.EXTRA_FAIL_FIRST_SAVE, "true")
            if (autoPauseSeconds != null) {
                putExtra(LaunchConfigReader.EXTRA_AUTO_PAUSE_SECONDS, autoPauseSeconds.toString())
            }
        }
    }

    private fun startRecordingFromToolbar() {
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORD_BUTTON), WAIT_TIMEOUT_MILLIS)
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORD_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_SCREEN), WAIT_TIMEOUT_MILLIS)
        composeRule.waitUntil { recordingController.state.value.phase == RecordingPhase.WAITING_FOR_FIX }
    }

    private fun emitTwoWalkedFixes() {
        emitFix(offsetSeconds = 0L, lat = 47.71842)
        clock.advanceBy(Duration.ofSeconds(10))
        emitFix(offsetSeconds = 0L, lat = 47.71900)
    }

    private fun emitFix(offsetSeconds: Long, lat: Double) {
        val fix = LocationFix(
            latitude = lat,
            longitude = 12.83177,
            timestampEpochMillis = clock.now().toEpochMilli() + offsetSeconds * 1_000L,
            horizontalAccuracyMeters = 4.0,
            elevationMeters = 540.0,
            verticalAccuracyMeters = 3.0,
            speedMps = 1.4
        )
        runBlocking { locationFeed.emitFix(fix) }
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
