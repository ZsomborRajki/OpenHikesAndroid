package app.openhikes.feature.routeedit

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleProfile(): RouteProfile {
        val parsed = GpxParser.parseAll(TestFixtures.thumseeWalkThenDriveGpxBytes()) as GpxParseResult.Success
        return RouteProfile.from(parsed.value.first().route)
    }

    @Test
    fun displaysDriveOfferAndDisabledSaveUntilChanged() {
        val actions = mutableListOf<RouteEditAction>()
        val profile = sampleProfile()
        val range = MutableStateFlow(
            RouteTrimRangeState(
                startMeters = 0.0,
                endMeters = profile.totalDistanceMeters,
                totalMeters = profile.totalDistanceMeters,
                displayedDistanceMeters = profile.totalDistanceMeters,
                removedMeters = 0.0,
                keptDurationSeconds = 1_150.0
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RouteEditScreen(
                    uiState = RouteEditUiState(
                        hikeId = HikeId("h1"),
                        isLoading = false,
                        profile = profile,
                        trailingCarriedOfferMeters = 6_000.0,
                        hasChanges = false
                    ),
                    rangeStateFlow = range,
                    onBack = {},
                    onSave = {},
                    onConfirmRevert = {},
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_START).assertContentDescriptionEquals("Start of route")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END).assertContentDescriptionEquals("End of route")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_FIGURES).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REMOVE_END).assertIsDisplayed().performClick()
        assertThat(actions).contains(RouteEditAction.RemoveCarriedEnd)
    }

    @Test
    fun stretchModeShowsVerbsAndUpdatesHandleDescriptions() {
        val actions = mutableListOf<RouteEditAction>()
        val profile = sampleProfile()
        val range = MutableStateFlow(
            RouteTrimRangeState(
                startMeters = profile.totalDistanceMeters / 3.0,
                endMeters = profile.totalDistanceMeters * 2.0 / 3.0,
                totalMeters = profile.totalDistanceMeters,
                displayedDistanceMeters = profile.totalDistanceMeters / 3.0,
                removedMeters = 0.0
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RouteEditScreen(
                    uiState = RouteEditUiState(
                        hikeId = HikeId("h1"),
                        isLoading = false,
                        profile = profile,
                        isSelectingStretch = true,
                        canFollowTrails = true,
                        picksWholeLine = true
                    ),
                    rangeStateFlow = range,
                    onBack = {},
                    onSave = {},
                    onConfirmRevert = {},
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_START).assertContentDescriptionEquals("Start of stretch")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_END).assertContentDescriptionEquals("End of stretch")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_FOLLOW).assertIsEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_REMOVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_CANCEL).assertIsEnabled().performClick()

        assertThat(actions).containsExactly(RouteEditAction.StraightenStretch, RouteEditAction.EndStretch).inOrder()
    }

    @Test
    fun stretchModeHidesFollowTrailsWhenMappedRoutingIsUnavailable() {
        val profile = sampleProfile()
        val range = MutableStateFlow(
            RouteTrimRangeState(
                startMeters = profile.totalDistanceMeters / 3.0,
                endMeters = profile.totalDistanceMeters * 2.0 / 3.0,
                totalMeters = profile.totalDistanceMeters,
                displayedDistanceMeters = profile.totalDistanceMeters / 3.0
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RouteEditScreen(
                    uiState = RouteEditUiState(
                        hikeId = HikeId("h1"),
                        isLoading = false,
                        profile = profile,
                        isSelectingStretch = true,
                        canFollowTrails = false
                    ),
                    rangeStateFlow = range,
                    onBack = {},
                    onSave = {},
                    onConfirmRevert = {},
                    onAction = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN).performScrollToKey("steps")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_FOLLOW).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN).assertIsEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STRETCH_REMOVE).assertIsEnabled()
    }

    @Test
    fun refusalDialogAndRevertDialogEmitCallbacks() {
        val actions = mutableListOf<RouteEditAction>()
        var revertConfirmed = false
        val profile = sampleProfile()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RouteEditScreen(
                    uiState = RouteEditUiState(
                        hikeId = HikeId("h1"),
                        isLoading = false,
                        profile = profile,
                        hasOriginal = true,
                        showRevertDialog = true,
                        refusal = RouteEditRefusal.NO_MAPPED_WAY
                    ),
                    rangeStateFlow = MutableStateFlow(RouteTrimRangeState(totalMeters = profile.totalDistanceMeters)),
                    onBack = {},
                    onSave = {},
                    onConfirmRevert = { revertConfirmed = true },
                    onAction = { actions.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_EDIT_REFUSAL_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_TRIM_REVERT_CONFIRM).performClick()
        assertThat(revertConfirmed).isTrue()
    }
}
