package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
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
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class TrailMakerJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun drawSaveOpenDetailReturnToLibraryAndOpenEditRoute() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openTrailMaker()

            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).assertDoesNotExist()

            addStopViaMapDrop(renderer, GeoCoordinate(47.71842, 12.83177))
            addStopViaMapDrop(renderer, GeoCoordinate(47.72150, 12.83300))
            addStopViaMapDrop(renderer, GeoCoordinate(47.72450, 12.82900))

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.trailDraftPoint(3)))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled().performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_NAME))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_NAME).performTextReplacement("Saturday Ridge")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_NAME_SAVE).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntilAtLeastOneExists(hasText("Saturday Ridge"), timeoutMillis = 5_000L)

            // Saved drawn trail is an ordinary hike that opens cleanly in Edit Route.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("closing_actions")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_EDIT_ROUTE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_TRIM_SAVE))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            // Return to library: trail appears and home toolbar button returns.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON))
            composeRule.waitUntilAtLeastOneExists(hasText("Saturday Ridge"))
        }
    }

    @Test
    fun backKeepsDraftWithoutDialogWhileDiscardClearsDraft() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openTrailMaker()

            addStopViaMapDrop(renderer, GeoCoordinate(47.71842, 12.83177))
            addStopViaMapDrop(renderer, GeoCoordinate(47.72200, 12.83480))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.trailDraftPoint(2)))

            // Back pops immediately with no confirmation dialog, keeping draft persisted.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_BACK).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE_DIALOG).assertDoesNotExist()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON))

            // Reopen restores both stops.
            openTrailMaker()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.trailDraftPoint(2)))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled()

            // Close opens confirmation dialog; Discard Trail wipes the draft.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE_DIALOG))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_DISCARD).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON))

            // Reopen starts empty again.
            openTrailMaker()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsNotEnabled()
        }
    }

    @Test
    fun toolbarButtonVisibilityGraphSnappingAndTravelModes() {
        ActivityScenario.launch<MainActivity>(
            baseIntent(
                importGpx = TestFixtures.THUMSEE_LOOP_FAST_NAME,
                trailGraph = TestFixtures.THUMSEE_LOOP_TRAILS_GRAPH_NAME
            )
        ).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
                timeoutMillis = 5_000L
            )
            // Home shows Make a trail; pushing HikeDetail hides it; popping restores it.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON))

            // Opening Trail Maker deselects the imported hike and exposes Follow Paths.
            openTrailMaker()
            assertThat(renderer.lastOverlayState.value?.routes?.none { it.isSelected }).isTrue()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("follow_paths")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).assertIsDisplayed()

            // Drop two stops along ThumseeLoopTrails: snaps to the OSM trail graph.
            addStopViaMapDrop(renderer, GeoCoordinate(47.71842, 12.831774))
            addStopViaMapDrop(renderer, GeoCoordinate(47.724008, 12.829098))
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_ROUTE_TIME),
                timeoutMillis = 5_000L
            )
            assertThat(renderer.lastTrailDraftOverlay.value?.legs?.singleOrNull()?.snap)
                .isEqualTo(TrailLegSnap.Snapped)

            // Turning Follow Paths off keeps stops and Save enabled, switching leg to Freehand.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("follow_paths")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailDraftOverlay.value?.legs?.singleOrNull()?.snap == TrailLegSnap.Freehand
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled()

            // Turn Follow Paths back on, then switch to Cycling -> unsupported notice, Save stays enabled.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("travel_mode")
            composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftMode("cycling")).performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasText("No route found for this travel mode"),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled()
        }
    }

    @Test
    fun droppedPinCardStopSearchReorderDeleteAndLandscapeRotationPreserveState() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { scenario ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openTrailMaker()

            // Open fields open Stop Search; cancel leaves draft intact.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_FIELD))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_HERE).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_CANCEL).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_SHEET))

            // Dropped pin stays on map when card closes, reopens on pin tap, and Remove Pin clears it.
            composeRule.runOnIdle { renderer.simulateTrailDraftLongPress(GeoCoordinate(47.7200, 12.8300)) }
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_SHEET))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_CLOSE).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_SHEET))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_DROPPED_PIN).assertIsDisplayed().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_REMOVE))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_REMOVE).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_DROPPED_PIN))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START).assertIsDisplayed()

            // Add 2 stops, insert a 3rd into the leg, reorder, delete one, then rotate to landscape.
            addStopViaMapDrop(renderer, GeoCoordinate(47.71842, 12.83177))
            addStopViaMapDrop(renderer, GeoCoordinate(47.72450, 12.82900))
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailDraftOverlay.value?.legs?.size == 1
            }
            val legEnds = requireNotNull(renderer.lastTrailDraftOverlay.value?.legs?.single()?.ends)
            addStopViaMapDrop(renderer, GeoCoordinate(47.72150, 12.83100), legEnds = legEnds)
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.trailDraftPoint(3)))

            // Reorder Stop 2 down and delete Stop 3 -> leaves 2 stops.
            composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPointMoveDown(2)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPointDelete(3)).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.trailDraftPoint(3)))
            composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPoint(2)).assertIsDisplayed()

            // Rotate to landscape phone (side panel): Trail Maker and stops survive cleanly.
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
            scenario.recreate()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.trailDraftPoint(2)))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled()
        }
    }

    private fun openTrailMaker() {
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN),
            timeoutMillis = 5_000L
        )
    }

    private fun addStopViaMapDrop(
        renderer: FakeTrailMapRenderer,
        coordinate: GeoCoordinate,
        legEnds: app.openhikes.core.model.TrailLegEnds? = null
    ) {
        composeRule.runOnIdle {
            renderer.simulateTrailDraftLongPress(coordinate, legEnds = legEnds)
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP))
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP).performClick()
        composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_SHEET))
    }

    private fun baseIntent(importGpx: String? = null, trailGraph: String? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            if (importGpx != null) putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, importGpx)
            if (trailGraph != null) putExtra(LaunchConfigReader.EXTRA_TRAIL_GRAPH, trailGraph)
        }
    }
}
