package app.openhikes.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysDefaultTogglesAndFiresCallbacksOnInteraction() {
        var closed = false
        var selectedUnit: DistanceUnit? = null
        var autoPauseChanged: Boolean? = null
        var autoSaveChanged: Boolean? = null
        var savePhotosChanged: Boolean? = null

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = UserSettings(),
                        appVersion = "0.1.0 (1)",
                        isLoaded = true
                    ),
                    onClose = { closed = true },
                    onDistanceUnitChange = { selectedUnit = it },
                    onAutoPauseChange = { autoPauseChanged = it },
                    onHikeRemindersChange = {},
                    onLiveUpdatesChange = {},
                    onKeepScreenAwakeChange = {},
                    onAutoSaveTilesChange = { autoSaveChanged = it },
                    onSavePhotosToLibraryChange = { savePhotosChanged = it },
                    onOpenUrl = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_METRIC).assertIsOn()
        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL).assertIsOff()

        composeRule.onNodeWithTag(OpenHikesTestTags.AUTO_PAUSE_TOGGLE).assertIsOn().performClick()
        assertThat(autoPauseChanged).isFalse()

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_REMINDERS_TOGGLE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(OpenHikesTestTags.LIVE_UPDATES_TOGGLE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(OpenHikesTestTags.KEEP_SCREEN_AWAKE_TOGGLE).performScrollTo().assertIsOff()

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.AUTO_SAVE_TILES_TOGGLE)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertThat(autoSaveChanged).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertThat(savePhotosChanged).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("units")
        composeRule.onNodeWithTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL).performScrollTo().performClick()
        assertThat(selectedUnit).isEqualTo(DistanceUnit.IMPERIAL)

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_CLOSE).performClick()
        assertThat(closed).isTrue()
    }

    @Test
    fun aboutSectionExposesLocalizedLegalLinksRepositoryAndVersion() {
        val openedUrls = mutableListOf<String>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                SettingsScreen(
                    uiState = SettingsUiState(
                        settings = UserSettings(),
                        appVersion = "0.1.0 (1)",
                        isLoaded = true
                    ),
                    onClose = {},
                    onDistanceUnitChange = {},
                    onAutoPauseChange = {},
                    onHikeRemindersChange = {},
                    onLiveUpdatesChange = {},
                    onKeepScreenAwakeChange = {},
                    onAutoSaveTilesChange = {},
                    onSavePhotosToLibraryChange = {},
                    onOpenUrl = { openedUrls.add(it) },
                    languageTag = "de-AT"
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("about")
        composeRule.onNodeWithTag(OpenHikesTestTags.PRIVACY_POLICY_LINK).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.TERMS_LINK).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.PROJECT_GITHUB_LINK).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_VERSION_ROW).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0.1.0 (1)").assertIsDisplayed()

        assertThat(openedUrls).containsExactly(
            "https://zsomborrajki.github.io/OpenHikes/de/privacy/",
            "https://zsomborrajki.github.io/OpenHikes/de/terms/",
            "https://github.com/ZsomborRajki/OpenHikesAndroid"
        ).inOrder()
    }
}
