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
class LibraryTotalsInstrumentedTest {
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
    fun librarySortReorderAndTotalsFlowAreAccessible() {
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

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntilAtLeastOneExists(hasText("Malerwinkel Loop"), timeoutMillis = 5_000)
            composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BUTTON).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_REORDER_ACTION).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN),
                timeoutMillis = 5_000
            )
            val totalsList = composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN)
            totalsList.performScrollToKey("month_chart")
            composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_MONTH_CHART).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            totalsList.performScrollToKey("records")
            composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_RECORD_LONGEST).assertIsDisplayed().performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000
            )
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
