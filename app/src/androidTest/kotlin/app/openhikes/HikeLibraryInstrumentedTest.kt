package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class HikeLibraryInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun hikeLibraryDetailChartStyleRenameAndDeleteAreAccessible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_LIST).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CHART).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            val detailList = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
            detailList.performScrollToKey("on_map")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_ROW).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.routeColorSwatch(RouteTint.palette[1])).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN).performScrollToKey("line")
            composeRule.onNodeWithTag(OpenHikesTestTags.routePattern(RouteLinePattern.DASHED.id))
                .performScrollTo()
                .performClick()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            detailList.performScrollToKey("closing_actions")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_BUTTON).performScrollTo().performClick()
            detailList.performScrollToKey("header")
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_TITLE_FIELD),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_TITLE_FIELD).performTextReplacement("Thumsee Renamed")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_SAVE_BUTTON).performClick()
            composeRule.waitUntilAtLeastOneExists(hasText("Thumsee Renamed"))

            detailList.performScrollToKey("closing_actions")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_BUTTON).performScrollTo().performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_DIALOG).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_CONFIRM_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_EMPTY_STATE))
        }
    }
}
