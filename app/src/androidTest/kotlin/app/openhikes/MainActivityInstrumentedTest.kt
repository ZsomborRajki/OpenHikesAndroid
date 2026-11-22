package app.openhikes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import app.openhikes.core.ui.testing.OpenHikesTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class MainActivityInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun launchesMapShellAndSettingsWithAccessibleControls() {
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).assertExists()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAP).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ATTRIBUTION).assertIsDisplayed()
        composeRule.onRoot().tryPerformAccessibilityChecks()

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE) and isOn())
        composeRule.onRoot().tryPerformAccessibilityChecks()

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_CLOSE).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))
    }
}
