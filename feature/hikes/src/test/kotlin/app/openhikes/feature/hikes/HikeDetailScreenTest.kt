package app.openhikes.feature.hikes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class HikeDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysChartActionsStyleRowRenameFlowAndDeleteDialog() {
        var uiState by mutableStateOf(sampleDetailUiState())
        val hike = checkNotNull(uiState.hike)
        var scrubDistance by mutableDoubleStateOf(0.0)
        var zoomed = false
        var exported = false
        var openedStyle = false

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeDetailScreen(
                    uiState = uiState,
                    scrubDistanceProvider = { scrubDistance },
                    onScrubDistance = { scrubDistance = it },
                    onBack = {},
                    onZoomToRoute = { zoomed = true },
                    onExportGpx = { exported = true },
                    onOpenRouteStyle = { openedStyle = true },
                    onStartRename = { uiState = uiState.copy(isRenaming = true, titleDraft = hike.displayTitle) },
                    onTitleDraftChange = { uiState = uiState.copy(titleDraft = it) },
                    onCommitRename = {
                        uiState = uiState.copy(isRenaming = false, hike = hike.copy(customName = uiState.titleDraft))
                    },
                    onCancelRename = { uiState = uiState.copy(isRenaming = false) },
                    onRequestDelete = { uiState = uiState.copy(showDeleteDialog = true) },
                    onConfirmDelete = {},
                    onCancelDelete = { uiState = uiState.copy(showDeleteDialog = false) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CHART).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ZOOM_BUTTON).performClick()
        assertThat(zoomed).isTrue()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_EXPORT_GPX_BUTTON).performClick()
        assertThat(exported).isTrue()

        val list = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
        list.performScrollToKey("on_map")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_ROW).performScrollTo().performClick()
        assertThat(openedStyle).isTrue()

        list.performScrollToKey("closing_actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_BUTTON).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_TITLE_FIELD))
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_TITLE_FIELD).performTextReplacement("Evening Ridge")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_RENAME_SAVE_BUTTON).performClick()
        composeRule.waitForIdle()
        assertThat(uiState.hike?.customName).isEqualTo("Evening Ridge")

        list.performScrollToKey("closing_actions")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_BUTTON).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_DIALOG).assertIsDisplayed()
    }

    private fun sampleDetailUiState(): HikeDetailUiState {
        val route = listOf(
            RouteCoordinate(47.718, 12.830, elevationMeters = 530.0, timestampEpochMillis = 0L),
            RouteCoordinate(47.722, 12.835, elevationMeters = 580.0, timestampEpochMillis = 600_000L)
        )
        val hike = Hike(
            id = HikeId("h1"),
            title = "Thumsee Loop",
            distanceMeters = 850.0,
            durationSeconds = 600.0,
            dateEpochMillis = 1_767_225_600_000L,
            trackDescription = "Lakeside path",
            route = route
        )
        return HikeDetailUiState(
            hikeId = hike.id,
            hike = hike,
            profile = RouteProfile.from(route),
            statistics = HikeRouteStatistics.from(hike.distanceMeters, route),
            isLoaded = true
        )
    }
}
