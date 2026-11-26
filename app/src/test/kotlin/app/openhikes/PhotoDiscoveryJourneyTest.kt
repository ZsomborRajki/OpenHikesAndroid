package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [36], application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class PhotoDiscoveryJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var hikeRepository: HikeRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun discoversStubbedPhotosTogglesSelectionImportsAndDeduplicatesOnReopen() {
        ActivityScenario.launch<MainActivity>(discoveryIntent(stubCount = 3)).use { _ ->
            openSeededHikeAndDiscovery()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_GRID),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(0)).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(1)).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(2)).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).assertIsEnabled()

            // Deselect all -> Add disabled -> Select all -> Add enabled.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_SELECT_ALL_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_SELECT_ALL_BUTTON).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).assertIsEnabled()

            // Import all 3 -> auto-closes back to Hike Detail with the strip populated.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PHOTO_STRIP))

            // Reopen discovery -> already-imported assets are deduplicated -> Empty with no Add button.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_EMPTY),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_DONE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        }
    }

    @Test
    fun emptyLimitedLibraryExposesSelectMorePhotosAction() {
        ActivityScenario.launch<MainActivity>(discoveryIntent(stubCount = 0, stubAccess = "limited")).use { _ ->
            openSeededHikeAndDiscovery()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_EMPTY),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_SELECT_MORE_BUTTON).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON).assertDoesNotExist()
        }
    }

    @Test
    fun selectionPreservesAcrossRotationAndResetsAfterDoneAndReopen() {
        ActivityScenario.launch<MainActivity>(discoveryIntent(stubCount = 3)).use { scenario ->
            openSeededHikeAndDiscovery()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_GRID),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(0)).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(0)).assertIsNotSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(1)).assertIsSelected()

            // Rotate to landscape side panel: deselected tile remains deselected.
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
            scenario.recreate()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_GRID),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(0)).assertIsNotSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(1)).assertIsSelected()

            // Dismiss with Done and reopen -> fresh discovery resets selection to all selected.
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_DONE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_GRID),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.discoveredPhoto(0)).assertIsSelected()
        }
    }

    @Test
    fun untimedDrawnTrailShowsUnsupportedWithoutPromptingPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_STUB_PHOTO_LIBRARY_ACCESS, "denied")
        }
        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            runBlocking {
                hikeRepository.saveDrawnTrail(
                    title = "Untimed Ridge Sketch",
                    distanceMeters = 900.0,
                    route = listOf(
                        RouteCoordinate(47.7184, 12.8317),
                        RouteCoordinate(47.7210, 12.8330)
                    ),
                    madeOnEpochMillis = 1_750_000_000_000L
                )
            }
            composeRule.waitUntilAtLeastOneExists(hasText("Untimed Ridge Sketch"), timeoutMillis = 5_000L)
            composeRule.onNodeWithText("Untimed Ridge Sketch").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PHOTO_DISCOVERY_UNSUPPORTED),
                timeoutMillis = 5_000L
            )
        }
    }

    private fun openSeededHikeAndDiscovery() {
        composeRule.waitUntilAtLeastOneExists(
            hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.PHOTO_DISCOVERY_BUTTON).performClick()
    }

    private fun discoveryIntent(stubCount: Int, stubAccess: String? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            putExtra(LaunchConfigReader.EXTRA_STUB_PHOTO_LIBRARY_COUNT, stubCount.toString())
            if (stubAccess != null) {
                putExtra(LaunchConfigReader.EXTRA_STUB_PHOTO_LIBRARY_ACCESS, stubAccess)
            }
        }
    }
}
