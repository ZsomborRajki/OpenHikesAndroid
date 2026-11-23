package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class OfflineMapsJourneyTest {
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
    fun seededOfflineMapsListsByNameTogglesDetailFramesSelectionAndExpandsUsGroup() {
        ActivityScenario.launch<MainActivity>(seededIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openOfflineMapsFromToolbar()

            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("AT")),
                timeoutMillis = 5_000
            )
            assertThat(renderer.lastOverlayState.value?.countryOutlines).hasSize(10)

            // Extra Detail starts ON (1.47 GB); toggling OFF halves Austria's listed size (735 MB).
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertIsOn()
            composeRule.onNodeWithText("1.47 GB").assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).performClick()
            composeRule.waitUntilAtLeastOneExists(hasText("735 MB"), timeoutMillis = 5_000)

            // Selecting Austria highlights its row and frames the map camera.
            composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("AT")).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("AT")).assertIsSelected()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                renderer.handledCommands.value.any { it is MapCameraCommand.FitBounds }
            }

            // Expand US split-country group (3 regions) -> Colorado & Wyoming.
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("group-US")
            composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryGroup("US")).assertIsDisplayed().performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountryGroup("US")) and hasStateDescription("Expanded"),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("group-US-US-CO")
            composeRule.onNodeWithText("Colorado").assertIsDisplayed()
        }
    }

    @Test
    fun seededOfflineMapsSearchesRanksSortsLargestAndSelectsOnMapTap() {
        ActivityScenario.launch<MainActivity>(seededIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openOfflineMapsFromToolbar()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("AT")),
                timeoutMillis = 5_000
            )

            // Search "wa" puts Washington, United States above Norway and hides sort button.
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SEARCH).performTextReplacement("wa")
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("US-WA")),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON).assertDoesNotExist()
            val waBounds = composeRule
                .onNodeWithTag(OpenHikesTestTags.offlineCountry("US-WA"))
                .getUnclippedBoundsInRoot()
            val noBounds = composeRule
                .onNodeWithTag(OpenHikesTestTags.offlineCountry("NO"))
                .getUnclippedBoundsInRoot()
            assertThat(waBounds.top).isLessThan(noBounds.top)

            // Search "united" matches Colorado, United States; "Atlantis" shows empty state.
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SEARCH).performTextReplacement("united")
            composeRule.waitUntilAtLeastOneExists(hasText("Colorado, United States"), timeoutMillis = 5_000)

            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SEARCH).performTextReplacement("Atlantis")
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_MAPS_EMPTY_SEARCH),
                timeoutMillis = 5_000
            )

            // Clear search and sort by Largest -> France appears above Germany.
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_CLEAR_SEARCH).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON).performClick()
            composeRule.onNodeWithText("Largest").performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON) and hasStateDescription("Largest"),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("section-all-countries")
            val frBounds = composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("FR")).getUnclippedBoundsInRoot()
            val deBounds = composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("DE")).getUnclippedBoundsInRoot()
            assertThat(frBounds.top).isLessThan(deBounds.top)

            // Map tap inside Germany selects DE directly.
            renderer.simulateMapTap(GeoCoordinate(latitude = 50.0, longitude = 10.5))
            composeRule.waitUntil(timeoutMillis = 5_000) {
                renderer.lastOverlayState.value?.countryOutlines?.any { it.countryId == "DE" && it.isSelected } == true
            }
        }
    }

    @Test
    fun opensNearestHikerLocationAndShowsSameCountriesFromSettings() {
        val intent = seededIntent().apply {
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
            putExtra(LaunchConfigReader.EXTRA_MAP_START, "47.718420,12.831774")
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            openOfflineMapsFromToolbar()
            composeRule.waitUntilAtLeastOneExists(hasText("Nearby"), timeoutMillis = 5_000)
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("section-nearby")
            val deBounds = composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("DE")).getUnclippedBoundsInRoot()
            val atBounds = composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountry("AT")).getUnclippedBoundsInRoot()
            assertThat(deBounds.top).isLessThan(atBounds.top)

            // Back to home -> Settings -> Offline Maps row shows the same countries.
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_OFFLINE_MAPS).performScrollTo().performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("AT")),
                timeoutMillis = 5_000
            )
        }
    }

    @Test
    fun hikeDetailOffersOnlyTheCountryItsTrailCrosses() {
        val intent = seededIntent().apply {
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
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
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_COUNTRY_BUTTON).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.OFFLINE_COUNTRY_DIALOG))
            composeRule.onNodeWithText("Download Germany", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("Download Austria", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun refusedCatalogShowsCouldnLoadAndTryAgain() {
        val intent = seededIntent(scenario = "refused")

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            openOfflineMapsFromToolbar()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.OFFLINE_MAPS_LOAD_ERROR),
                timeoutMillis = 5_000
            )
            composeRule.onNodeWithText("Couldn’t Load Countries").assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_TRY_AGAIN).assertIsDisplayed()
        }
    }

    private fun openOfflineMapsFromToolbar() {
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_OFFLINE_MAPS_BUTTON))
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_OFFLINE_MAPS_BUTTON).performClick()
    }

    private fun seededIntent(scenario: String = "seeded"): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE_COUNTRIES, scenario)
        }
    }
}
