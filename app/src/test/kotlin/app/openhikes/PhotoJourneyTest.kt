package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
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
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class PhotoJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun floatingPhotoControlsAndPinsFollowHikeDetailAcrossHistoryTabAndWithdrawAtRoot() {
        ActivityScenario.launch<MainActivity>(seededPhotosIntent(photoCount = 2)).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer

            // Library root never exposes floating camera or library buttons.
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_CAMERA_BUTTON).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_PHOTO_LIBRARY_BUTTON).assertDoesNotExist()

            openSeededHike()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_CAMERA_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_PHOTO_LIBRARY_BUTTON).assertIsDisplayed()
            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins?.size == 2
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PHOTO_STRIP))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("actions")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_SHARE_ARCHIVE_BUTTON).assertIsDisplayed()

            // Switching to History keeps map photo pins visible.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("segment_tabs")
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            composeRule.waitForIdle()
            assertThat(renderer.lastTrailPhotosOverlay.value?.pins).hasSize(2)

            // Returning to library root withdraws floating controls and clears map photo pins.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.MAP_CAMERA_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_PHOTO_LIBRARY_BUTTON).assertDoesNotExist()
            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins.isNullOrEmpty()
            }
        }
    }

    @Test
    fun viewerShowsOnMapOpensFromCalloutAndDeletesDownToLastPhoto() {
        ActivityScenario.launch<MainActivity>(seededPhotosIntent(photoCount = 2)).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins?.size == 2
            }
            val firstPin = requireNotNull(renderer.lastTrailPhotosOverlay.value?.pins?.first())
            val firstPhotoId = firstPin.pin.photo.id

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.hikePhotoTile(firstPhotoId)).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PHOTO_VIEWER))
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_SHOW_ON_MAP_BUTTON),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_SHARE_BUTTON),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DELETE_BUTTON).assertIsDisplayed()

            // Show on map selects the pin on the map overlay.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_SHOW_ON_MAP_BUTTON).performClick()
            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.selectedPinId == firstPin.id
            }

            // Confirm delete twice -> auto-closes viewer back to Hike Detail with no pins left.
            deleteCurrentPhotoInViewer()
            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins?.size == 1
            }
            deleteCurrentPhotoInViewer()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PHOTO_STRIP).assertDoesNotExist()
            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins.isNullOrEmpty()
            }
        }
    }

    @Test
    fun omittedLocalPhotoFileShowsUnavailableStateAndHidesShareButton() {
        val intent = seededPhotosIntent(photoCount = 1).apply {
            putExtra(LaunchConfigReader.EXTRA_OMIT_SEEDED_PHOTO_FILES, "true")
        }
        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins?.size == 1
            }
            val photoId = requireNotNull(renderer.lastTrailPhotosOverlay.value?.pins?.single()?.pin?.photo?.id)

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.hikePhotoTile(photoId)).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PHOTO_VIEWER))
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_NOT_ON_THIS_DEVICE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_SHARE_BUTTON).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DELETE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PHOTO_DELETE_DIALOG))
        }
    }

    @Test
    fun landscapeGalleryFillsWindowNarrowsOnShowOnMapAndSettingsShowsPhotosStorage() {
        RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
        ActivityScenario.launch<MainActivity>(seededPhotosIntent(photoCount = 1)).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            waitUntilCondition {
                renderer.lastTrailPhotosOverlay.value?.pins?.size == 1
            }
            val photoId = requireNotNull(renderer.lastTrailPhotosOverlay.value?.pins?.single()?.pin?.photo?.id)

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.hikePhotoTile(photoId)).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PHOTO_VIEWER))

            val rootWidth = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot().right.value
            val expandedWidth = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL)
                .getUnclippedBoundsInRoot().right.value
            assertThat(expandedWidth).isGreaterThan(rootWidth * 0.9f)

            // Show on map narrows side panel back beside the live map.
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_SHOW_ON_MAP_BUTTON),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_SHOW_ON_MAP_BUTTON).performClick()
            waitUntilCondition {
                val narrowed = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL)
                    .getUnclippedBoundsInRoot().right.value
                narrowed < rootWidth * 0.55f
            }

            // Navigate back to library and verify Settings photo storage row & toggle.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_VIEWER_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
            composeRule.onNodeWithTag(
                OpenHikesTestTags.SETTINGS_PHOTOS_STORAGE_ROW
            ).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE) and isOn(),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE).assertIsOn()
        }
    }

    @Test
    fun placeDetailShowsPhotoButtonsAndRemoveDialogExplainsPhotosStayInGallery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_PLACES_NAME)
        }
        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithText("Boathouse").performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_CAMERA).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_LIBRARY).assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_REMOVE).performScrollTo().performClick()
            composeRule.waitUntilAtLeastOneExists(hasText("Its photos stay in this hike’s gallery.", substring = true))
        }
    }

    private fun waitUntilCondition(condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun openSeededHike() {
        composeRule.waitUntilAtLeastOneExists(
            hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
            timeoutMillis = 5_000L
        )
    }

    private fun deleteCurrentPhotoInViewer() {
        composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DELETE_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PHOTO_DELETE_DIALOG))
        composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DELETE_CONFIRM).performClick()
    }

    private fun seededPhotosIntent(photoCount: Int): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            putExtra(LaunchConfigReader.EXTRA_SEED_PHOTO_COUNT, photoCount.toString())
        }
    }
}
