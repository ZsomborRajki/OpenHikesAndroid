package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.location.di.RecordingJournalDir
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
@Suppress("TooManyFunctions")
class WalkJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var locationFeed: FakeLocationFeed

    @Inject
    lateinit var hikeRepository: HikeRepository

    @Inject
    lateinit var trailWalkController: TrailWalkController

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
        trailWalkController.endWalk()
        recordingController.discard()
        journalDir.deleteRecursively()
        Unit
    }

    @Test
    fun autoStartsAfterWalkingOntoTrailShowsDismissiblePillAndPauseResume() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val profile = openSeededHikeDetailAndWaitForFollowFeed()

            emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_STARTED_PILL).assertDoesNotExist()

            walkAlong(profile, distancesMeters = listOf(0.0, 65.0, 145.0))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_STARTED_PILL), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_PHASE).assertIsDisplayed()
            assertThat(trailWalkController.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_STARTED_PILL_DISMISS).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.WALK_STARTED_PILL), WAIT_TIMEOUT_MILLIS)
            assertThat(trailWalkController.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Resume Hike"), WAIT_TIMEOUT_MILLIS)
            assertThat(trailWalkController.sessionState.value.phase).isEqualTo(TrailWalkPhase.PAUSED)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Pause Hike"), WAIT_TIMEOUT_MILLIS)
            assertThat(trailWalkController.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
        }
    }

    @Test
    fun manualStartOffTrailHasNoStartedPillAndUsesSingleTopBarToggle() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            openSeededHikeDetailAndWaitForFollowFeed()
            emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Pause Hike"), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_STARTED_PILL).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertIsDisplayed()
            assertThat(composeRule.onAllNodes(hasText("Pause Hike")).fetchSemanticsNodes()).hasSize(1)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Resume Hike"), WAIT_TIMEOUT_MILLIS)
        }
    }

    @Test
    fun historyTabKeepsInProgressWalkCardAboveEmptyHistoryState() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            openSeededHikeDetailAndWaitForFollowFeed()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PROGRESS).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("walk_history_empty")
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_HISTORY_EMPTY), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_HISTORY_EMPTY).assertIsDisplayed()
        }
    }

    @Test
    fun endingWalkOverMinimumOpensSummaryReachesFromHistoryAndDrawsMapCoverage() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            val profile = openSeededHikeDetailAndWaitForFollowFeed()

            walkAlong(profile, distancesMeters = listOf(0.0, 70.0, 160.0, 260.0))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_END_DIALOG), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_END_CONFIRM_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_COMPLETION)
                .assertIsDisplayed()
                .assertContentDescriptionContains("percent", substring = true)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SHOW_ON_MAP)
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            waitUntilCondition {
                renderer.lastWalkCoverageOverlay.value?.segments?.isNotEmpty() == true
            }

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SUMMARY_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            composeRule.waitUntilExactlyOneExists(walkRowPrefixMatcher(), WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(walkRowPrefixMatcher()).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN),
                WAIT_TIMEOUT_MILLIS
            )
        }
    }

    @Test
    fun seededHalfLoopWalkListsFiftyPercentInHistoryAndOpensSummary() {
        ActivityScenario.launch<MainActivity>(
            buildLaunchIntent(seedWalkPreset = TestFixtures.SEED_WALK_HALF_LOOP)
        ).use {
            openSeededHikeDetailAndWaitForFollowFeed()

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            composeRule.waitUntilExactlyOneExists(walkRowPrefixMatcher(), WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(walkRowPrefixMatcher() and hasText("50%", substring = true)).assertIsDisplayed()

            composeRule.onNode(walkRowPrefixMatcher()).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_COMPLETION)
                .assertContentDescriptionContains("50 percent complete", substring = true)
        }
    }

    @Test
    fun recordingMidWalkPromptsToEndWalkClearsLibraryBadgeAndOpensRecording() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            openSeededHikeDetailAndWaitForFollowFeed()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_LIST), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithText("Active").assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.RECORD_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_RECORDING_CONFIRM_DIALOG),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithText("End Hike and Record").performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_SCREEN), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertDoesNotExist()
            assertThat(trailWalkController.sessionState.value.isWalkActive).isFalse()
        }
    }

    @Test
    fun followSwitchOffKeepsWalkProgressingParksChartAndPersistsAcrossReopenAndResume() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val profile = openSeededHikeDetailAndWaitForFollowFeed()
            walkAlong(profile, distancesMeters = listOf(0.0, 70.0, 150.0))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)
            val beforeFraction = trailWalkController.progressState.value.coveredFraction

            val detailList = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
            detailList.performScrollToKey("follow_trail")
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).performScrollTo().assertIsOn()
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH) and hasToggleState("Off"),
                WAIT_TIMEOUT_MILLIS
            )

            detailList.performScrollToKey("elevation")
            val parkedReadout = composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_READOUT)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)

            walkAlong(profile, distancesMeters = listOf(260.0, 380.0))
            waitUntilCondition {
                trailWalkController.progressState.value.coveredFraction > beforeFraction
            }
            val afterReadout = composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_READOUT)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
            assertThat(afterReadout).isEqualTo(parkedReadout)

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_LIST), WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(hikeRowPrefixMatcher() and hasText("Thumsee Loop", substring = true)).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN), WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("follow_trail")
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).performScrollTo().assertIsOff()

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Resume Hike"), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasText("Pause Hike"), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).assertIsOff()
        }
    }

    @Test
    fun endingShortWalkStaysOnDetailAndTogglingFollowRearmsAutoStart() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val profile = openSeededHikeDetailAndWaitForFollowFeed()
            emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_END_CONFIRM_BUTTON),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_END_CONFIRM_BUTTON).performClick()

            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN).assertDoesNotExist()

            // Auto-start is suppressed until Follow This Trail is toggled OFF then ON.
            walkAlong(profile, distancesMeters = listOf(0.0, 65.0, 140.0))
            composeRule.waitForIdle()
            assertThat(trailWalkController.sessionState.value.isWalkActive).isFalse()

            val detailList = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
            detailList.performScrollToKey("follow_trail")
            val stopsBeforeToggle = locationFeed.stopCount
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH) and hasToggleState("Off"),
                WAIT_TIMEOUT_MILLIS
            )
            waitUntilCondition { locationFeed.stopCount > stopsBeforeToggle }
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).assertIsOff()

            val startsBeforeRearm = locationFeed.startCount
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH) and hasToggleState("On"),
                WAIT_TIMEOUT_MILLIS
            )
            waitUntilCondition { locationFeed.startCount > startsBeforeRearm }
            composeRule.onNodeWithTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH).assertIsOn()

            walkAlong(profile, distancesMeters = listOf(160.0, 230.0, 310.0))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_STARTED_PILL), WAIT_TIMEOUT_MILLIS)
        }
    }

    @Test
    fun junctionWarningsMapButtonAppearsDuringWalkAndTogglesSelection() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            openSeededHikeDetailAndWaitForFollowFeed()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).assertDoesNotExist()

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).assertIsNotSelected()

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).performClick()
            waitUntilCondition {
                trailWalkController.sessionState.value.isJunctionWarningsEnabled
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).assertIsSelected()

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).performClick()
            waitUntilCondition {
                !trailWalkController.sessionState.value.isJunctionWarningsEnabled
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON).assertIsNotSelected()
        }
    }

    @Test
    fun timeLeftBubbleAppearsOnMapDuringActiveWalk() {
        ActivityScenario.launch<MainActivity>(buildLaunchIntent()).use {
            val profile = openSeededHikeDetailAndWaitForFollowFeed()
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TIME_LEFT).assertDoesNotExist()

            walkAlong(profile, distancesMeters = listOf(0.0, 70.0, 160.0))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_TIME_LEFT), WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(
                hasTestTag(OpenHikesTestTags.WALK_TIME_LEFT) and hasText("left", substring = true)
            ).assertIsDisplayed()
        }
    }

    private fun openSeededHikeDetailAndWaitForFollowFeed(): RouteProfile {
        composeRule.waitUntilAtLeastOneExists(hasText("Thumsee Loop", substring = true), WAIT_TIMEOUT_MILLIS)
        composeRule.onNode(hasText("Thumsee Loop", substring = true)).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ELEVATION_CHART), WAIT_TIMEOUT_MILLIS)
        waitUntilCondition { locationFeed.startCount > 0 }
        val hike = runBlocking {
            val summary = hikeRepository.hikes.first { it.isNotEmpty() }.first()
            checkNotNull(hikeRepository.observeHike(summary.id).first())
        }
        return RouteProfile.from(hike.route)
    }

    private fun walkAlong(profile: RouteProfile, distancesMeters: List<Double>) {
        distancesMeters.forEach { dist ->
            clock.advanceBy(Duration.ofSeconds(15))
            val coord = checkNotNull(profile.coordinateAt(dist))
            emitCoordinate(coord)
        }
    }

    private fun emitCoordinate(coordinate: GeoCoordinate) {
        val fix = LocationFix(
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            timestampEpochMillis = clock.now().toEpochMilli(),
            horizontalAccuracyMeters = 5.0,
            elevationMeters = 550.0,
            verticalAccuracyMeters = 4.0,
            speedMps = 1.4
        )
        runBlocking { locationFeed.emitFix(fix) }
    }

    private fun buildLaunchIntent(seedWalkPreset: String? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
            putExtra(LaunchConfigReader.EXTRA_DISABLE_AUTO_RECOVERY, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            if (seedWalkPreset != null) {
                putExtra(LaunchConfigReader.EXTRA_SEED_WALKS, seedWalkPreset)
            }
        }
    }

    private fun waitUntilCondition(condition: () -> Boolean) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun hasToggleState(stateName: String): SemanticsMatcher =
        SemanticsMatcher("ToggleableState is $stateName") { node ->
            node.config.getOrNull(SemanticsProperties.ToggleableState)?.name == stateName
        }

    private fun walkRowPrefixMatcher(): SemanticsMatcher = SemanticsMatcher("testTag starts with walk-row-") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("${OpenHikesTestTags.WALK_ROW}-") == true
    }

    private fun hikeRowPrefixMatcher(): SemanticsMatcher = SemanticsMatcher("testTag starts with hike-row-") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(OpenHikesTestTags.hikeRow("")) == true
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
