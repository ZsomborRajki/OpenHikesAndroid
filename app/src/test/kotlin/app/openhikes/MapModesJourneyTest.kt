package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.MapStyleId
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

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class MapModesJourneyTest {
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
    fun choosingAMapAndHikingRoutesUpdatesLiveMapAndPersistsAcrossReopen() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON)
                .assertIsDisplayed()
                .assertContentDescriptionEquals("Map Modes")
                .performClick()

            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENHIKES)).assertIsSelected()

            // Switch to Clean Map and verify map overlay & live attribution update immediately.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_ON).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKING_ROUTES_OFF) and isSelected(),
                timeoutMillis = 5_000L
            )
            assertThat(renderer.lastOverlayState.value?.showsHikingRoutes).isFalse()
            composeRule.onNodeWithText("© OpenStreetMap, ESA WorldCover").assertIsDisplayed()

            // Switch to OpenFreeMap and OpenStreetMap raster styles.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("map-styles")
            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENFREEMAP)).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENFREEMAP)) and isSelected(),
                timeoutMillis = 5_000L
            )
            assertThat(renderer.lastOverlayState.value?.mapStyleId).isEqualTo(MapStyleId.OPENFREEMAP)
            composeRule.onNodeWithText("© OpenFreeMap, OpenMapTiles, OpenStreetMap").assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OSM_RASTER)).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OSM_RASTER)) and isSelected(),
                timeoutMillis = 5_000L
            )
            assertThat(renderer.lastOverlayState.value?.mapStyleId).isEqualTo(MapStyleId.OSM_RASTER)
            composeRule.onNodeWithText("© OpenStreetMap").assertIsDisplayed()

            // Missing-key commercial card is disabled and footer explains why.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_STYLE_ROW).performScrollToKey(MapStyleId.STADIA_OUTDOORS)
            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.STADIA_OUTDOORS)).assertIsNotEnabled()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("footer")
            composeRule.onNodeWithTag(OpenHikesTestTags.TILE_ATTRIBUTION).assertIsDisplayed()

            // Closing Map Modes keeps the map sheet up; reopening preserves both selections.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_STYLE_ROW).performScrollToKey(MapStyleId.OSM_RASTER)
            composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OSM_RASTER)).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF).assertIsSelected()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_ON).assertIsNotSelected()
        }
    }

    @Test
    fun mapModesLeavesLiveMapInViewInPortraitAndLandscape() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { scenario ->
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))

            // In portrait, Map Modes rests at HALF detent leaving the top of the live map visible.
            val portraitRoot = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
            val portraitSheet = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).getUnclippedBoundsInRoot()
            assertThat(portraitSheet.top).isGreaterThan(portraitRoot.height * 0.25f)

            // Rotate to landscape phone (800x400dp): Map Modes stays open in the side panel beside the map.
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
            scenario.recreate()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_TITLE).assertIsDisplayed()

            val landscapeRoot = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
            val panelBounds = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).getUnclippedBoundsInRoot()
            assertThat(panelBounds.width).isLessThan(landscapeRoot.width * 0.5f)

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))
        }
    }

    @Test
    fun mapModesOverHikeDetailPreservesRouteAndOfflineMapsReplacesMapModes() {
        val intent = baseIntent().apply {
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            putExtra(LaunchConfigReader.EXTRA_OFFLINE_COUNTRIES, "seeded")
        }

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            composeRule.waitUntilAtLeastOneExists(
                hasText(TestFixtures.THUMSEE_LOOP_FAST_TITLE),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_FAST_TITLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            // Open Map Modes over Hike Detail: the hike's route stays selected on the live map.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
            assertThat(renderer.lastOverlayState.value?.routes?.any { it.isSelected }).isTrue()

            // Closing returns straight to Hike Detail.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            // Jumping to Offline Maps from Map Modes replaces Map Modes so Back returns to Hike Detail.
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("offline-maps")
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS).performClick()
            composeRule.waitUntilAtLeastOneExists(
                hasTestTag(OpenHikesTestTags.offlineCountry("AT")),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
        }
    }

    @Test
    fun openingRecordFromMapModesAtPeekKeepsRecordingControlsVisible() {
        val intent = baseIntent(expandedSheet = false)

        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            val rootBounds = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
            val peekSheetTop = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).getUnclippedBoundsInRoot().top
            assertThat(peekSheetTop).isGreaterThan(rootBounds.height * 0.75f)

            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))

            // Pushing Recording over Map Modes must keep the sheet at HALF rather than restoring PEEK.
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORD_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_SCREEN))
            composeRule.waitForIdle()
            val recordingSheetTop = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET)
                .getUnclippedBoundsInRoot().top
            assertThat(recordingSheetTop).isLessThan(rootBounds.height * 0.65f)

            // Popping Recording returns to Map Modes at HALF; closing Map Modes restores PEEK.
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_MODES_TITLE))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SEARCH))
            composeRule.waitForIdle()
            val restoredSheetTop = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET)
                .getUnclippedBoundsInRoot().top
            assertThat(restoredSheetTop).isGreaterThan(rootBounds.height * 0.75f)
        }
    }

    private fun baseIntent(expandedSheet: Boolean = true): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, expandedSheet.toString())
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
        }
    }
}
