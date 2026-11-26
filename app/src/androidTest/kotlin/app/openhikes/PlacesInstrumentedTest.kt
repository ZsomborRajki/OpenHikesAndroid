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
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.network.TrailPointsScenarios
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
class PlacesInstrumentedTest {
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
    fun hikePlacesDetailAdderAndPlacesAroundPassAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_PLACES_NAME)
            putExtra(LaunchConfigReader.EXTRA_TRAIL_POINTS, TrailPointsScenarios.SEEDED)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACES).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithText("Boathouse").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_EDIT).assertIsDisplayed()
            composeRule.onRoot().tryPerformAccessibilityChecks()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_BACK).performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_SEARCH).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PLACES_AROUND_ON_TRAIL),
                timeoutMillis = 5_000L
            )
            composeRule.onRoot().tryPerformAccessibilityChecks()
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        }
    }
}
