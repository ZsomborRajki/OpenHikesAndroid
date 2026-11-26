package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.di.RecordingJournalDir
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.network.TrailPointsScenarios
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class PlacesJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Inject
    lateinit var locationFeed: FakeLocationFeed

    @Inject
    lateinit var clock: TestClock

    @Inject
    lateinit var recordingController: RecordingController

    @Inject
    @RecordingJournalDir
    lateinit var journalDir: File

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() = runBlocking {
        recordingController.discard()
        journalDir.deleteRecursively()
        Unit
    }

    @Test
    fun viewsEditsRemovesFixturePlacesAndTogglesMapPinsAcrossHistoryTab() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACES))
            assertThat(renderer.lastTrailPlacesOverlay.value?.pins?.map { it.title })
                .containsAtLeast("Boathouse", "Thumsee Spring")

            // Switching to History tab keeps place pins visible on the map.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("segment_tabs")
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            composeRule.waitForIdle()
            assertThat(renderer.lastTrailPlacesOverlay.value?.pins).hasSize(2)

            // Back to Details -> turning off the Pins switch hides map pins without deleting places.
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_DETAILS).performClick()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE).performScrollTo().assertIsOn()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE) and isOff(),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailPlacesOverlay.value?.pins.isNullOrEmpty()
            }
            composeRule.onNodeWithText("Boathouse").assertIsDisplayed()

            // Turn pins back on.
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE) and isOn(),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                renderer.lastTrailPlacesOverlay.value?.pins?.size == 2
            }

            // Open hiker's own place ("Boathouse"): editable, note shown, no OSM link.
            composeRule.onNodeWithText("Boathouse").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_NOTE).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_OSM_LINK).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_EDIT).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_NAME_FIELD))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_NAME_FIELD).performTextReplacement("North Boathouse")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_SAVE).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.HIKE_PLACE_TITLE) and hasText("North Boathouse"),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_BACK).performClick()

            // Open OSM place ("Thumsee Spring"): read-only (no Edit), shows OSM link, removable.
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithText("Thumsee Spring").performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_EDIT).assertDoesNotExist()
            composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_OSM_LINK).performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_REMOVE).performScrollTo().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_REMOVE_CONFIRM))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_REMOVE_CONFIRM).performClick()

            // Removing returns directly to the hike detail screen without Thumsee Spring.
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithText("North Boathouse").assertIsDisplayed()
            composeRule.onNodeWithText("Thumsee Spring").assertDoesNotExist()
        }
    }

    @Test
    fun addsOwnPlaceViaMapButtonAndMapPanAndReturnsDirectlyToHikeOnBack() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            // Floating Add Place button is visible on Hike Detail.
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_ADD_PLACE_BUTTON))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ADD_PLACE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_ADDER))
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ADD_PLACE_BUTTON).assertDoesNotExist()
            assertThat(renderer.lastTrailPlacesOverlay.value?.showCenterPlacementPin).isTrue()

            // Panning the map updates the draft coordinates live under the fixed center pin.
            val panned = GeoCoordinate(latitude = 47.72345, longitude = 12.83456)
            composeRule.runOnIdle { renderer.simulateCameraMove(MapCameraState(center = panned, zoom = 16.0)) }
            composeRule.waitUntilAtLeastOneExists(hasText("47.72345", substring = true))

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_ADDER_NAME).performTextReplacement("Bench View")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_ADDER_ADD).performClick()

            // Replace-top opens the new place's detail screen; Back returns directly to Hike Detail.
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_SCREEN))
            composeRule.waitUntilAtLeastOneExists(hasText("Bench View"))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_BACK).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithText("Bench View").assertIsDisplayed()
        }
    }

    @Test
    fun placesAroundTrailSearchesOpensWikidataCardAddsInPlaceAndSurvivesRotation() {
        ActivityScenario.launch<MainActivity>(baseIntent()).use { scenario ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_SEARCH).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.PLACES_AROUND_ON_TRAIL),
                timeoutMillis = 5_000L
            )
            composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("nearby")
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_NEARBY).assertIsDisplayed()
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SEARCH_AREA_BUTTON).assertIsDisplayed()

            // Narrowing to On Trail hides the Nearby section; widening brings it back.
            composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("filters")
            composeRule.onNodeWithTag(
                OpenHikesTestTags.placesAroundReachOption(TrailPlaceReach.ON_TRAIL.name.lowercase())
            ).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.PLACES_AROUND_NEARBY))
            composeRule.onNodeWithTag(
                OpenHikesTestTags.placesAroundReachOption(TrailPlaceReach.NEARBY.name.lowercase())
            ).performClick()
            composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("nearby")
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PLACES_AROUND_NEARBY))

            // Tapping the pale candidate pin for Thumsee Kopf opens the card with Wikipedia summary.
            val summitPinId = requireNotNull(
                renderer.lastTrailPlacesOverlay.value?.candidates?.firstOrNull { it.title == "Thumsee Kopf" }?.id
            )
            composeRule.runOnIdle { renderer.simulateCandidatePinTap(summitPinId) }
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PLACES_AROUND_CARD))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WIKIDATA_PLACE_EXTRACT))

            // Adding from the card keeps the card open and transitions it to "On this hike".
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_CARD_ADD).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.PLACES_AROUND_CARD_ADD))
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_CARD).assertIsDisplayed()

            // Rotate to landscape phone (side panel): Places Around Trail survives cleanly.
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_CARD_CLOSE).performClick()
            composeRule.waitUntilDoesNotExist(hasTestTag(OpenHikesTestTags.PLACES_AROUND_CARD))
            RuntimeEnvironment.setQualifiers("w800dp-h400dp-land")
            scenario.recreate()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PLACES_AROUND_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_BACK).performClick()

            // Back on Hike Detail: Thumsee Kopf is now in the hike's Places list.
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN).performScrollToKey("places")
            composeRule.waitUntilAtLeastOneExists(hasText("Thumsee Kopf"))
        }
    }

    @Test
    fun longPressOnMapAddsOwnPlaceAndActiveRecordingOpensRecordingPlaceSheet() {
        val intent = baseIntent().apply {
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
        }
        ActivityScenario.launch<MainActivity>(intent).use { _ ->
            val renderer = trailMapRenderer as FakeTrailMapRenderer
            openSeededHike()

            // Long-pressing the map on Hike Detail drops a new place directly in Add Place.
            composeRule.runOnIdle { renderer.simulatePlaceLongPress(GeoCoordinate(47.72111, 12.83222)) }
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_PLACE_ADDER))
            composeRule.waitUntilAtLeastOneExists(hasText("47.72111", substring = true))
            composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_ADDER_CANCEL).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN))

            // Start a live recording + emit a GPS fix -> floating Add Place opens RecordingAddPlaceRoute.
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORD_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.RECORDING_SCREEN),
                timeoutMillis = 5_000L
            )
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.MAP_ADD_PLACE_BUTTON),
                timeoutMillis = 5_000L
            )
            runBlocking {
                locationFeed.emitFix(
                    LocationFix(
                        latitude = 47.7185,
                        longitude = 12.8318,
                        timestampEpochMillis = clock.now().toEpochMilli(),
                        horizontalAccuracyMeters = 4.0,
                        elevationMeters = 532.0,
                        verticalAccuracyMeters = 3.0,
                        speedMps = 1.4
                    )
                )
            }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                recordingController.trace.value.points.isNotEmpty()
            }
            composeRule.onNodeWithTag(OpenHikesTestTags.MAP_ADD_PLACE_BUTTON).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.RECORDING_PLACE_SHEET))
            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.RECORDING_PLACE_ADD_OWN),
                timeoutMillis = 5_000L
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_PLACES_NEARBY).assertIsDisplayed().performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.PLACES_NEARBY_SCREEN))
        }
    }

    private fun openSeededHike() {
        composeRule.waitUntilAtLeastOneExists(
            hasText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE),
            timeoutMillis = 5_000L
        )
        composeRule.onNodeWithText(TestFixtures.THUMSEE_LOOP_PLACES_TITLE).performClick()
        composeRule.waitUntilExactlyOneExists(
            hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
            timeoutMillis = 5_000L
        )
    }

    private fun baseIntent(): Intent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_PLACES_NAME)
            putExtra(LaunchConfigReader.EXTRA_TRAIL_POINTS, TrailPointsScenarios.SEEDED)
        }
    }
}
