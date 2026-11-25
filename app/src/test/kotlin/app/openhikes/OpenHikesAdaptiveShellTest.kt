package app.openhikes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class OpenHikesAdaptiveShellTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun resizingBetweenPortraitLandscapeAndTabletPreservesBackStackAndSettingsState() {
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE) and isOn())

        // Resize to landscape phone (800x400dp): panel replaces sheet and preserves Settings.
        RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE)
            .performScrollTo()
            .assertIsOn()

        val rootBounds = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
        val panelBounds = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).getUnclippedBoundsInRoot()
        val mapBounds = composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAP).getUnclippedBoundsInRoot()
        val attributionBounds = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ATTRIBUTION).getUnclippedBoundsInRoot()

        assertThat(panelBounds.width).isLessThan(rootBounds.width * 0.5f)
        assertThat(mapBounds.width).isEqualTo(rootBounds.width)
        assertThat(attributionBounds.left).isAtLeast(panelBounds.right)

        // Resize to expanded tablet (1024x768dp): side panel and Settings remain intact.
        RuntimeEnvironment.setQualifiers("w1024dp-h768dp-land")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()

        // Resize back to compact portrait phone: bottom sheet returns with Settings still open.
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SHEET))
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()
    }

    @Test
    fun floatingMapControlsAndBackNavigationDriveMapStateAndSearchFocus() {
        val fakeRenderer = trailMapRenderer as FakeTrailMapRenderer
        val initialZoom = fakeRenderer.lastCameraState.value.zoom

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ZOOM_IN_BUTTON).performClick()
        composeRule.waitForIdle()
        assertThat(fakeRenderer.lastCameraState.value.zoom).isGreaterThan(initialZoom)

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ZOOM_OUT_BUTTON).performClick()
        composeRule.waitForIdle()
        assertThat(fakeRenderer.lastCameraState.value.zoom).isWithin(0.01).of(initialZoom)

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_RESET_NORTH_BUTTON).performClick()
        composeRule.waitForIdle()
        assertThat(fakeRenderer.lastCameraState.value.bearing).isEqualTo(0.0)

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKING_ROUTES_OFF) and isSelected(),
            timeoutMillis = 5_000L
        )
        composeRule.waitForIdle()
        assertThat(fakeRenderer.lastOverlayState.value?.showsHikingRoutes).isFalse()
        composeRule.onNodeWithText("© OpenStreetMap, ESA WorldCover").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ATTRIBUTION).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_ATTRIBUTION_DIALOG))
        composeRule.onNodeWithText("Land cover © ESA WorldCover").assertIsDisplayed()
        composeRule.onNodeWithText("https://esa-worldcover.org").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ATTRIBUTION_DIALOG).assertDoesNotExist()

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).performTextInput("Thumsee")
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsFocused()
        composeRule.onNodeWithTag(OpenHikesTestTags.CLEAR_SEARCH_BUTTON).assertIsDisplayed()

        // First Back closes search and releases focus; tapping search again re-focuses and expands.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OpenHikesTestTags.CLEAR_SEARCH_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsNotFocused()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH).assertIsFocused()
    }

    @Test
    fun settingsAtHalfDetentKeepsVersionRowWithinVisibleSheetBounds() {
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))

        // Settings opens at EXPANDED; cycling handle twice moves EXPANDED -> PEEK -> HALF.
        composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_ATTRIBUTION))
        composeRule.onNodeWithTag(OpenHikesTestTags.SHEET_DRAG_HANDLE).performClick()
        composeRule.waitForIdle()

        val rootBounds = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
        val sheetBounds = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).getUnclippedBoundsInRoot()
        assertThat(sheetBounds.top).isGreaterThan(rootBounds.height * 0.3f)
        assertThat(sheetBounds.top).isLessThan(rootBounds.height * 0.65f)

        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("about")
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_VERSION_ROW).performScrollTo().assertIsDisplayed()
        val versionBounds = composeRule.onNodeWithTag(OpenHikesTestTags.APP_VERSION_ROW).getUnclippedBoundsInRoot()
        assertThat(versionBounds.bottom).isAtMost(rootBounds.bottom)
    }
}
