package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteTrimHandle
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
class RouteEditJourneyTest {
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
    fun driveIsCutOffSavedAndRevertedToOriginal() {
        ActivityScenario.launch<MainActivity>(driveIntent()).use { _ ->
            openEditRouteFromLibrary()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT).assertDoesNotExist()

            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END).performClick()
            composeRule.waitUntilDoesNotExist(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsEnabled().performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )

            // Reopen Edit Route: drive offer is gone and Revert to Original is available.
            openEditRouteFromDetail()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_END),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("revert")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT).assertIsDisplayed().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REVERT_CONFIRM))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT_CONFIRM).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )

            // Reopen again: original drive is back and Revert is gone.
            openEditRouteFromDetail()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT).assertDoesNotExist()
        }
    }

    @Test
    fun draggingChartHandleAndMapHandleEnableSave() {
        ActivityScenario.launch<MainActivity>(driveIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openEditRouteFromLibrary()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_TRIM_END), timeoutMillis = 5_000L)
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()

            // Drag the chart's end handle left.
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END).performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsEnabled()

            // Also verify map handles exist and respond to drag.
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_START_HANDLE).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END_HANDLE).assertIsDisplayed()
            val overlayBefore = requireNotNull(renderer.lastRouteEditOverlay.value)
            val midCoordinate = overlayBefore.keptWalkedSegments.first().let { it[it.size / 2] }
            composeRule.runOnIdle {
                renderer.simulateHandleDrag(RouteTrimHandle.START, midCoordinate)
            }
            composeRule.waitForIdle()
            val overlayAfter = requireNotNull(renderer.lastRouteEditOverlay.value)
            assertThat(overlayAfter.cutLeadingSegments).isNotEmpty()
        }
    }

    @Test
    fun stretchStraightenUndoSaveAndCancelPreserveInvariants() {
        ActivityScenario.launch<MainActivity>(driveIntent()).use { _ ->
            openEditRouteFromLibrary()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_TRIM_END), timeoutMillis = 5_000L)

            // Canceling a stretch leaves nothing changed.
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_BEGIN).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STRETCH_CANCEL))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("trimmer")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_START)
                .assertContentDescriptionEquals("Start of stretch")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_CANCEL).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STRETCH_BEGIN))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("trimmer")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_START)
                .assertContentDescriptionEquals("Start of route")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STEP_UNDO).assertDoesNotExist()

            // Straighten -> Undo -> Straighten -> Save -> Reopen persists Undo & Revert.
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_BEGIN).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STEP_UNDO).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()

            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_BEGIN).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )

            openEditRouteFromDetail()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_TRIM_END), timeoutMillis = 5_000L)
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STEP_UNDO).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("revert")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT).assertIsDisplayed()
        }
    }

    @Test
    fun reverseClearsDriveOfferUndoRestoresItAndMapPullRedrawsStretch() {
        ActivityScenario.launch<MainActivity>(driveIntent()).use { scenario ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openEditRouteFromLibrary()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END),
                timeoutMillis = 5_000L
            )

            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_WHOLE_MENU).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_WHOLE_REVERSE))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_WHOLE_OUT_AND_BACK).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_WHOLE_CLOSE_LOOP).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_WHOLE_REVERSE).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END).assertDoesNotExist()

            // Rotating to landscape preserves the active Edit Route destination and Undo state.
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
            scenario.recreate()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STEP_UNDO).performClick()

            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("not_on_foot")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()

            // Pull the line on the map through a new spot.
            val overlay = requireNotNull(renderer.lastRouteEditOverlay.value)
            val routePoint = overlay.keptWalkedSegments.first().let { it[it.size / 2] }
            val pulledTo = GeoCoordinate(routePoint.latitude + 0.004, routePoint.longitude)
            composeRule.runOnIdle {
                assertThat(renderer.simulateRoutePull(routePoint, pulledTo)).isTrue()
            }
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STEP_UNDO), timeoutMillis = 5_000L)
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsEnabled()
        }
    }

    private fun openEditRouteFromLibrary() {
        composeRule.waitUntilAtLeastOneExists(
            hasText(TestFixtures.THUMSEE_WALK_THEN_DRIVE_TITLE),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithText(TestFixtures.THUMSEE_WALK_THEN_DRIVE_TITLE).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        openEditRouteFromDetail()
    }

    private fun openEditRouteFromDetail() {
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("closing_actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_EDIT_ROUTE).assertIsDisplayed().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN))
    }

    private fun driveIntent(): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_WALK_THEN_DRIVE_NAME)
        }
    }
}
