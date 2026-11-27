package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class MapModesInstrumentedTest {
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
    fun mapModesSwitchingAndOfflineNavigationPassAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE_COUNTRIES, "seeded")
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENFREEMAP)).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENFREEMAP)) and isSelected(),
                timeoutMillis = 5_000L
            )

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKING_ROUTES_OFF) and isSelected(),
                timeoutMillis = 5_000L
            )
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("offline-maps")
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS).performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_USE_OPENHIKES_MAP).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
