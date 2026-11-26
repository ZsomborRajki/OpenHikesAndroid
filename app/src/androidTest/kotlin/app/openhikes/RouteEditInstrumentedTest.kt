package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
class RouteEditInstrumentedTest {
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
    fun editRouteWithDriveOfferAndStretchModePassesAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_WALK_THEN_DRIVE_NAME)
            putExtra(LaunchConfigReader.EXTRA_TRAIL_GRAPH, TestFixtures.THUMSEE_RIDGE_PATH_GRAPH_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_WALK_THEN_DRIVE_TITLE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithText(TestFixtures.THUMSEE_WALK_THEN_DRIVE_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("closing_actions")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_EDIT_ROUTE).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END).assertContentDescriptionEquals("End of route")
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_BEGIN).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STRETCH_FOLLOW))
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END)
                .assertContentDescriptionEquals("End of stretch")
            composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_FOLLOW).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
