package app.openhikes

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
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
class MainActivitySmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun launchesMapFirstShellInPortraitWithSearchSettingsAndAttribution() {
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).assertExists()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAP).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ATTRIBUTION).assertIsDisplayed()
    }

    @Test
    fun settingsChangesPersistAcrossCloseAndReopenAndLegalLinksRemainReachable() {
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))

        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL) and isOn())

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.AUTO_SAVE_TILES_TOGGLE).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.AUTO_SAVE_TILES_TOGGLE) and isOn())
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE) and isOn())

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("about")
        composeRule.onNodeWithTag(OpenHikesTestTags.PRIVACY_POLICY_LINK).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.TERMS_LINK).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_CLOSE).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))

        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL).assertIsOn()
        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_METRIC).assertIsOff()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.AUTO_SAVE_TILES_TOGGLE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .assertIsOn()
    }

    @Test
    fun manifestDisablesAutomaticBackup() {
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        val allowBackupFlag = app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        assertThat(allowBackupFlag).isEqualTo(0)
    }
}
