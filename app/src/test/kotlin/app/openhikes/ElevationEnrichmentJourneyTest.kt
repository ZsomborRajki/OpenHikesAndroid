package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.data.ElevationScenarios
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.ElevationCredit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import com.google.common.truth.Truth.assertThat
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class ElevationEnrichmentJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Inject
    lateinit var hikeRepository: HikeRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun trailMakerMeasuresLiveElevationShadesSteepnessAndSavesHikeWithChart() {
        ActivityScenario.launch<MainActivity>(
            baseIntent(
                trailGraph = TestFixtures.THUMSEE_LOOP_TRAILS_GRAPH_NAME,
                elevationScenario = ElevationScenarios.SEEDED
            )
        ).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_TRAIL_MAKER_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN))

            addStopViaMapDrop(renderer, GeoCoordinate(47.71842, 12.831774))
            addStopViaMapDrop(renderer, GeoCoordinate(47.724008, 12.829098))

            // After the 2-second settle debounce, Trail Maker displays live climb and enables Elevation coloring.
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_CLIMB),
                timeoutMillis = 8_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("on_map")
            composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("elevation"))
                .performScrollTo()
                .assertIsEnabled()
                .performClick()

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailDraftOverlay.value?.shadedStretches?.isNotEmpty() == true
            }

            // Saving the drawn trail persists the DEM heights so Hike Detail shows the elevation chart immediately.
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_NAME))
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_NAME).performTextInput("Seeded Climb")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_NAME_SAVE).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ELEVATION_CHART),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastOverlayState.value?.routes?.firstOrNull { it.isSelected }
                    ?.shadedStretches?.isNotEmpty() == true
            }
        }
    }

    @Test
    fun flatHikeFillsElevationAutomaticallyWhenSeededAndShadesMapSteepness() {
        runBlocking {
            hikeRepository.saveDrawnTrail(
                title = "Flat Ridge",
                distanceMeters = 900.0,
                route = listOf(
                    RouteCoordinate(47.7160, 12.8300),
                    RouteCoordinate(47.7200, 12.8320),
                    RouteCoordinate(47.7240, 12.8350)
                ),
                madeOnEpochMillis = 1_780_000_000_000L
            ).getOrThrow()
        }

        ActivityScenario.launch<MainActivity>(
            baseIntent(elevationScenario = ElevationScenarios.SEEDED)
        ).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            composeRule.waitUntilAtLeastOneExists(hasText("Flat Ridge"), timeoutMillis = 5_000L)
            composeRule.onNodeWithText("Flat Ridge").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ELEVATION_CHART),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                val stretches = renderer.lastOverlayState.value?.routes
                    ?.firstOrNull { it.isSelected }
                    ?.shadedStretches
                !stretches.isNullOrEmpty()
            }
            assertThat(
                renderer.lastOverlayState.value?.routes?.firstOrNull { it.isSelected }?.shadedStretches
            ).isNotEmpty()
        }
    }

    @Test
    fun flatHikeShowsPlaceholderWhenRefusedAndSettingsShowsCopernicusCredit() {
        runBlocking {
            hikeRepository.saveDrawnTrail(
                title = "Offline Flat Trail",
                distanceMeters = 600.0,
                route = listOf(
                    RouteCoordinate(47.7160, 12.8300),
                    RouteCoordinate(47.7200, 12.8320)
                ),
                madeOnEpochMillis = 1_780_000_000_000L
            ).getOrThrow()
        }

        ActivityScenario.launch<MainActivity>(
            baseIntent(elevationScenario = ElevationScenarios.REFUSED)
        ).use { _ ->
            composeRule.waitUntilAtLeastOneExists(hasText("Offline Flat Trail"), timeoutMillis = 5_000L)
            composeRule.onNodeWithText("Offline Flat Trail").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.ELEVATION_PLACEHOLDER),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithText("No elevation data for this hike").assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("about")
            composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CREDIT_LINK).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(ElevationCredit.NOTICE).assertIsDisplayed()
        }
    }

    private fun addStopViaMapDrop(renderer: FakeTrailMapRenderer, coordinate: GeoCoordinate) {
        composeRule.runOnIdle { renderer.simulateTrailDraftLongPress(coordinate) }
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP))
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP).performClick()
        composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_SHEET))
    }

    private fun baseIntent(trailGraph: String? = null, elevationScenario: String? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            if (trailGraph != null) putExtra(LaunchConfigReader.EXTRA_TRAIL_GRAPH, trailGraph)
            if (elevationScenario != null) putExtra(LaunchConfigReader.EXTRA_ELEVATION, elevationScenario)
        }
    }
}
