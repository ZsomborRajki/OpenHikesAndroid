package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeListSort
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class LibraryTotalsAndOrderJourneyTest {
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
    fun sortingCustomReorderingTotalsRecordOpenAndMapLibraryLineTapWorkEndToEnd() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            putExtra(LaunchConfigReader.EXTRA_SEED_NEARBY_TRAILS, "true")
        }

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            verifySortingAndReordering()
            verifyTotalsAndRecordNavigation()
            verifyMapLibraryLineTap(renderer, scenario)
        }
    }

    private fun verifySortingAndReordering() {
        composeRule.waitUntilAtLeastOneExists(hasText("Malerwinkel Loop"), timeoutMillis = 5_000)
        composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntilAtLeastOneExists(hasText("Rinnkendlsteig"), timeoutMillis = 5_000)
        composeRule.waitUntilAtLeastOneExists(hasText("Gotzenalm"), timeoutMillis = 5_000)

        // Sort alphabetically: Gotzenalm appears above Rinnkendlsteig.
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeSortOption(HikeListSort.ALPHABETICAL.rawValue)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rowTopY("Gotzenalm") < rowTopY("Rinnkendlsteig")
        }

        // Sort by Hilliest: Rinnkendlsteig (630 m) sorts above Gotzenalm (585 m) and Malerwinkel Loop (230 m).
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeSortOption(HikeListSort.HILLIEST.rawValue)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rowTopY("Rinnkendlsteig") < rowTopY("Gotzenalm") &&
                rowTopY("Gotzenalm") < rowTopY("Malerwinkel Loop")
        }

        // Enter reorder mode, move Gotzenalm up above Rinnkendlsteig, then exit with Done.
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_REORDER_ACTION).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Move Gotzenalm up").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rowTopY("Gotzenalm") < rowTopY("Rinnkendlsteig")
        }
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).performClick()
        composeRule.onNodeWithText("Your Order").assertIsDisplayed()
    }

    private fun verifyTotalsAndRecordNavigation() {
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN),
            timeoutMillis = 5_000
        )
        val totalsList = composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN)
        totalsList.performScrollToKey("month_chart")
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_MONTH_CHART).assertIsDisplayed()

        totalsList.performScrollToKey("records")
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_RECORD_LONGEST).assertIsDisplayed().performClick()

        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_DETAIL_TITLE) and hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
            timeoutMillis = 5_000
        )

        // Pop detail then totals back to library.
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN),
            timeoutMillis = 5_000
        )
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BACK).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_LIST), timeoutMillis = 5_000)
    }

    private fun verifyMapLibraryLineTap(renderer: FakeTrailMapRenderer, scenario: ActivityScenario<MainActivity>) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            (renderer.lastOverlayState.value?.libraryLines?.size ?: 0) >= 4
        }
        assertThat(renderer.lastOverlayState.value?.routes).isEmpty()

        // Move map over Gotzenalm's midpoint (47.581, 13.001) and tap the faint library line.
        val gotzenalmMid = GeoCoordinate(47.581, 13.001)
        composeRule.runOnIdle {
            renderer.simulateCameraMove(MapCameraState(center = gotzenalmMid, zoom = 14.0))
            renderer.simulateMapTap(gotzenalmMid)
        }

        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_DETAIL_TITLE) and hasText("Gotzenalm"),
            timeoutMillis = 5_000
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            renderer.lastOverlayState.value?.routes?.size == 1 &&
                renderer.lastOverlayState.value?.libraryLines?.size == 3
        }

        // Navigate back to the library and recreate the activity (rotation): consumed tap must not re-open detail.
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_LIST), timeoutMillis = 5_000)
        scenario.recreate()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_LIST), timeoutMillis = 5_000)
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).assertDoesNotExist()
    }

    private fun rowTopY(title: String): Float =
        composeRule.onNodeWithText(title, substring = true).fetchSemanticsNode().positionInRoot.y
}
