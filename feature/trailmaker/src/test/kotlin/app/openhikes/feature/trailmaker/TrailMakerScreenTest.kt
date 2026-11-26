package app.openhikes.feature.trailmaker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrailMakerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyDraftShowsOpenFieldsDisabledSaveAndHidesSnapWhenUnsupported() {
        val actions = mutableListOf<TrailMakerAction>()
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                TrailMakerScreen(
                    uiState = TrailMakerUiState(
                        rows = listOf(
                            TrailStopRowItem(
                                slotId = "open-start",
                                position = 0,
                                isOpen = true,
                                waypointId = null,
                                role = TrailStopRole.Start,
                                name = "",
                                distanceMeters = 0.0,
                                arrivingSnap = null,
                                pointNumber = null,
                                canMoveUp = false,
                                canMoveDown = false
                            ),
                            TrailStopRowItem(
                                slotId = "open-end",
                                position = 1,
                                isOpen = true,
                                waypointId = null,
                                role = TrailStopRole.End,
                                name = "",
                                distanceMeters = 0.0,
                                arrivingSnap = null,
                                pointNumber = null,
                                canMoveUp = false,
                                canMoveDown = false
                            )
                        ),
                        canBeSaved = false,
                        isEmpty = true,
                        canSnapToPaths = false
                    ),
                    onBack = {},
                    onCloseRequested = {},
                    onDiscardTrail = {},
                    onKeepForLater = {},
                    onConfirmSave = {},
                    onSharePlace = { _, _ -> },
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_START).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_OPEN_DESTINATION).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).assertDoesNotExist()
        assertThat(actions).contains(
            TrailMakerAction.OpenStopSearch(TrailStopSearchTarget.Open(TrailStopRole.Start))
        )
    }

    @Test
    fun filledDraftShowsStopsFollowPathsRetryAndModeSelector() {
        val actions = mutableListOf<TrailMakerAction>()
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                TrailMakerScreen(
                    uiState = sampleFilledUiState(),
                    onBack = {},
                    onCloseRequested = {},
                    onDiscardTrail = {},
                    onKeepForLater = {},
                    onConfirmSave = {},
                    onSharePlace = { _, _ -> },
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE).assertIsEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftMode("cycling")).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPoint(1)).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.trailDraftPointDelete(2)).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_RETRY).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN).performScrollToKey("follow_paths")
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP).performClick()

        assertThat(actions).containsAtLeast(
            TrailMakerAction.SelectTravelMode(TrailTravelMode.CYCLING),
            TrailMakerAction.DeleteStop("w2"),
            TrailMakerAction.RetryRefusedLegs,
            TrailMakerAction.ToggleSnapToPaths(false)
        )
    }

    private fun sampleFilledUiState(): TrailMakerUiState = TrailMakerUiState(
        travelMode = TrailTravelMode.HIKING,
        rows = listOf(
            TrailStopRowItem(
                slotId = "p1",
                position = 0,
                isOpen = false,
                waypointId = "w1",
                role = TrailStopRole.Start,
                name = "Thumsee",
                distanceMeters = 0.0,
                arrivingSnap = null,
                pointNumber = 1,
                canMoveUp = false,
                canMoveDown = true
            ),
            TrailStopRowItem(
                slotId = "p2",
                position = 1,
                isOpen = false,
                waypointId = "w2",
                role = TrailStopRole.End,
                name = "Kaitl",
                distanceMeters = 2_400.0,
                arrivingSnap = TrailLegSnap.Refused(TrailLegOutage.OFFLINE),
                pointNumber = 2,
                canMoveUp = true,
                canMoveDown = false
            )
        ),
        distanceMeters = 2_400.0,
        travelTimeSeconds = 2_160.0,
        canBeSaved = true,
        isEmpty = false,
        canSnapToPaths = true,
        snapsToPaths = true,
        hasRetryableLegs = true,
        worstLegSnap = TrailLegSnap.Refused(TrailLegOutage.OFFLINE)
    )

    @Test
    fun stopSearchAndPlaceCardDialogsEmitExpectedActions() {
        val actions = mutableListOf<TrailMakerAction>()
        val recent = TrailStopRecent(
            id = "r1",
            name = "Listsee",
            subtitle = "Bad Reichenhall",
            latitude = 47.73,
            longitude = 12.84
        )
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                TrailMakerScreen(
                    uiState = TrailMakerUiState(
                        stopSearch = TrailStopSearchUiState(
                            target = TrailStopSearchTarget.Open(TrailStopRole.Start),
                            recents = listOf(recent),
                            myLocationAvailable = true
                        ),
                        placeCard = TrailPlaceCardState(
                            isDroppedPin = true,
                            waypointId = null,
                            role = null,
                            title = "Dropped Pin",
                            nameForStop = "",
                            distanceAlongRouteMeters = null,
                            coordinate = GeoCoordinate(47.721, 12.825),
                            coordinatesText = "47.72100° N, 12.82500° E",
                            address = "Thumsee, Bavaria",
                            preferredLegEnds = null
                        )
                    ),
                    onBack = {},
                    onCloseRequested = {},
                    onDiscardTrail = {},
                    onKeepForLater = {},
                    onConfirmSave = {},
                    onSharePlace = { _, _ -> },
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_SHEET).assertIsDisplayed()
        composeRule.onNodeWithText("Thumsee, Bavaria").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_ADD_STOP).performClick()
        assertThat(actions).contains(TrailMakerAction.AddStopFromPlaceCard)
    }
}
