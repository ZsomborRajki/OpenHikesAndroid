package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
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
class HikeLibraryJourneyTest {
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
    fun seededHikeFlowsThroughDetailChartScrubRouteStyleZoomRenameAndAtomicDelete() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHikeDetailAndScrub(renderer)
            editStyleZoomRenameAndDelete(renderer)
        }
    }

    private fun openSeededHikeDetailAndScrub(renderer: FakeTrailMapRenderer) {
        composeRule.waitUntilAtLeastOneExists(
            hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
            timeoutMillis = 5_000
        )
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_LIST).assertIsDisplayed()
        composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).performTextReplacement("Thumsee")
        composeRule.waitUntilAtLeastOneExists(hasTestTag(OpenHikesTestTags.HIKE_SEARCH_SECTION_HEADER))
        composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            renderer.lastOverlayState.value?.routes?.any { it.isSelected } == true &&
                renderer.handledCommands.value.any { it is MapCameraCommand.FitBounds }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CHART).performTouchInput {
            click(Offset(width * 0.65f, centerY))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { renderer.lastHighlight.value != null }
    }

    private fun editStyleZoomRenameAndDelete(renderer: FakeTrailMapRenderer) {
        val detailList = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
        detailList.performScrollToKey("on_map")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_ROW).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN))

        val updatedColor = RouteTint.palette[1]
        composeRule.onNodeWithTag(OpenHikesTestTags.routeColorSwatch(updatedColor)).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN).performScrollToKey("line")
        composeRule.onNodeWithTag(OpenHikesTestTags.routePattern(RouteLinePattern.DASHED.id))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_PATTERN_CAPTION)
            .assertTextEquals("Dashed")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val overlay = renderer.lastOverlayState.value?.routes?.firstOrNull()
            overlay?.tintHex == updatedColor && overlay.linePattern == RouteLinePattern.DASHED
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_BACK).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

        val fitCountBefore = renderer.handledCommands.value.count { it is MapCameraCommand.FitBounds }
        detailList.performScrollToKey("actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ZOOM_BUTTON).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            renderer.handledCommands.value.count { it is MapCameraCommand.FitBounds } > fitCountBefore
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
        composeRule.waitForIdle()

        detailList.performScrollToKey("closing_actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_BUTTON).performScrollTo().performClick()
        detailList.performScrollToKey("header")
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_TITLE_FIELD),
            timeoutMillis = 5_000
        )
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_TITLE_FIELD).performTextReplacement("Morning Thumsee")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_SAVE_BUTTON).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Morning Thumsee"), timeoutMillis = 5_000)

        detailList.performScrollToKey("closing_actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_CONFIRM_BUTTON).performClick()

        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_EMPTY_STATE))
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).assertDoesNotExist()
        composeRule.waitUntil {
            renderer.lastOverlayState.value?.routes?.isEmpty() == true &&
                renderer.lastHighlight.value == null
        }
        assertThat(renderer.lastHighlight.value).isNull()
    }
}
