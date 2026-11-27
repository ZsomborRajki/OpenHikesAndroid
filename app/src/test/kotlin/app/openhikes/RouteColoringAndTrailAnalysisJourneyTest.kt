package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteShade
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
class RouteColoringAndTrailAnalysisJourneyTest {
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
    fun showsSurfaceAndDifficultyBreakdownsAndSwitchesRouteColoringOnMap() {
        ActivityScenario.launch<MainActivity>(
            baseIntent(
                importGpx = TestFixtures.THUMSEE_LOOP_FAST_NAME,
                trailGraph = TestFixtures.THUMSEE_LOOP_TRAILS_GRAPH_NAME
            )
        ).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededThumseeLoopDetail()
            verifySurfaceAndDifficultySections()
            verifyRouteColoringOverlaySwitches(renderer)
            verifyRouteColoringPersistsAcrossReopen()
        }
    }

    private fun openSeededThumseeLoopDetail() {
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

    private fun verifySurfaceAndDifficultySections() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("surface")
            }.isSuccess
        }
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SURFACE_BAR), timeoutMillis = 5_000L)
        composeRule.onNodeWithTag(OpenHikesTestTags.SURFACE_BAR)
            .assert(stateDescriptionContains("Gravel"))
            .assert(stateDescriptionContains("Ground"))
            .assert(stateDescriptionContains("Paved"))

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("difficulty")
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.DIFFICULTY_BAR), timeoutMillis = 5_000L)
        composeRule.onNodeWithTag(OpenHikesTestTags.DIFFICULTY_BAR)
            .assert(stateDescriptionContains("Hiking"))
            .assert(stateDescriptionContains("Mountain Hiking"))
    }

    private fun verifyRouteColoringOverlaySwitches(renderer: FakeTrailMapRenderer) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            renderer.lastOverlayState.value?.routes?.firstOrNull { it.isSelected }?.shadedStretches?.isNotEmpty() ==
                true
        }
        openRouteStyleAndScrollToColoring()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_SHADE_KEY)
            .performScrollTo()
            .assertContentDescriptionEquals("Steepness colors")

        composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("difficulty"))
            .performScrollTo()
            .performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.ROUTE_SHADE_KEY) and hasContentDescription("Difficulty colors"),
            timeoutMillis = 5_000L
        )
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            val shades = selectedRouteShades(renderer)
            RouteShade.EASIEST in shades && RouteShade.EASY in shades
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("off"))
            .performScrollTo()
            .performClick()
        composeRule.waitUntilAtLeastOneExists(
            hasText("Every hike is drawn in its own route color."),
            timeoutMillis = 5_000L
        )
        composeRule.waitUntil(timeoutMillis = 5_000L) { selectedRouteShades(renderer).isEmpty() }

        composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("difficulty"))
            .performScrollTo()
            .performClick()
        composeRule.waitUntilAtLeastOneExists(
            hasText("Colors the line by its OpenStreetMap difficulty grade. Applies to every hike."),
            timeoutMillis = 5_000L
        )
        composeRule.waitUntil(timeoutMillis = 5_000L) { selectedRouteShades(renderer).isNotEmpty() }
    }

    private fun verifyRouteColoringPersistsAcrossReopen() {
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_BACK).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        openRouteStyleAndScrollToColoring()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.ROUTE_SHADE_KEY) and hasContentDescription("Difficulty colors"),
            timeoutMillis = 5_000L
        )
    }

    private fun selectedRouteShades(renderer: FakeTrailMapRenderer): Set<RouteShade> = renderer.lastOverlayState.value
        ?.routes
        ?.firstOrNull { it.isSelected }
        ?.shadedStretches
        ?.map { it.shade }
        ?.toSet()
        .orEmpty()

    private fun openRouteStyleAndScrollToColoring() {
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("on_map")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_ROW).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN))
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN).performScrollToKey("all_hikes")
    }

    @Test
    fun trailMakerShadesSnappedHikingRouteFromCacheAndHonorsSharedColoringToggle() {
        ActivityScenario.launch<MainActivity>(
            baseIntent(trailGraph = TestFixtures.THUMSEE_LOOP_TRAILS_GRAPH_NAME)
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

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.TRAIL_DRAFT_ROUTE_TIME),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailDraftOverlay.value?.shadedStretches?.isNotEmpty() == true
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("on_map")
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_COLORING).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("elevation")).assertIsNotEnabled()

            composeRule.onNodeWithTag(OpenHikesTestTags.routeColoringOption("off"))
                .performScrollTo()
                .performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasText("Every hike is drawn in its own route color."),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailDraftOverlay.value?.shadedStretches?.isEmpty() == true
            }
            assertThat(renderer.lastTrailDraftOverlay.value?.shadedStretches).isEmpty()
        }
    }

    private fun addStopViaMapDrop(renderer: FakeTrailMapRenderer, coordinate: GeoCoordinate) {
        composeRule.runOnIdle { renderer.simulateTrailDraftLongPress(coordinate) }
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP))
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP).performClick()
        composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.TRAIL_PLACE_SHEET))
    }

    private fun stateDescriptionContains(substring: String): SemanticsMatcher =
        SemanticsMatcher("StateDescription contains '$substring'") { node ->
            val state = node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
            state?.contains(substring) == true
        }

    private fun baseIntent(importGpx: String? = null, trailGraph: String? = null): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            if (importGpx != null) putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, importGpx)
            if (trailGraph != null) putExtra(LaunchConfigReader.EXTRA_TRAIL_GRAPH, trailGraph)
        }
    }
}
