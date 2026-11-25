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
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
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
class OfflineMapsInstrumentedTest {
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
    fun offlineMapsAndHikeCoverageDialogPassAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE_COUNTRIES, "seeded")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("offline-maps")
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS).performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("AT")),
                timeoutMillis = 5_000
            )
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SEARCH).performTextReplacement("wa")
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("US-WA")),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_CLEAR_SEARCH).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_BACK).performClick()

            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("actions")
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_COUNTRY_BUTTON),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_COUNTRY_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.OFFLINE_COUNTRY_DIALOG))
            composeRule.onNodeWithText("Download Germany", substring = true).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
