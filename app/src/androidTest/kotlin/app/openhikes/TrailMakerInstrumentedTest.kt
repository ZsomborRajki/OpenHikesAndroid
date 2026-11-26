package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class TrailMakerInstrumentedTest {
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
    fun trailMakerAndStopSearchPassAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_TRAIL_GRAPH, TestFixtures.THUMSEE_RIDGE_PATH_GRAPH_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("follow_paths")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("route_card")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_FIELD))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_HERE).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_CANCEL).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_SHEET))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON))
        }
    }
}
