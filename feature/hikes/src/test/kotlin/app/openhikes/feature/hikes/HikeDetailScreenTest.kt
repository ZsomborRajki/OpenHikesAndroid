package app.openhikes.feature.hikes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailBreakdown
import app.openhikes.core.model.TrailDifficulty
import app.openhikes.core.model.TrailSurface
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test
    fun displaysActiveWalkAcrossDetailsAndHistoryTabsAndOpensWalkSummary() {
        var uiState by mutableStateOf(samplePausedWalkUiState())
        val progressFlow = MutableStateFlow(sampleWalkProgress())
        var openedWalkId: String? = null
        var toggledWalk = false

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeDetailScreen(
                    uiState = uiState,
                    scrubDistanceProvider = { 0.0 },
                    onScrubDistance = {},
                    onBack = {},
                    onZoomToRoute = {},
                    onExportGpx = {},
                    onOpenRouteStyle = {},
                    onStartRename = {},
                    onTitleDraftChange = {},
                    onCommitRename = {},
                    onCancelRename = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    walkProgressFlow = progressFlow,
                    activeSecondsProvider = { 900.0 },
                    onWalkAction = { action ->
                        when (action) {
                            HikeWalkAction.ToggleWalk -> toggledWalk = true
                            HikeWalkAction.RequestEndWalk -> uiState = uiState.copy(showEndWalkDialog = true)
                            is HikeWalkAction.SelectTab -> uiState = uiState.copy(selectedTab = action.tab)
                            is HikeWalkAction.OpenWalkSummary -> openedWalkId = action.walkId
                            else -> Unit
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText("Resume Hike").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
        assertThat(toggledWalk).isTrue()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PROGRESS).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.walkRow("w1")).assertIsDisplayed().performClick()
        assertThat(openedWalkId).isEqualTo("w1")

        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_CONTROLS).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_END_DIALOG).assertIsDisplayed()
    }

    @Test
    fun displaysSurfaceAndDifficultySectionsWithSpokenSummaryAndCoverageFootnotes() {
        val surface = TrailBreakdown.fromMeters(
            mapOf(TrailSurface.GRAVEL to 750.0, TrailSurface.PAVED to 250.0)
        )
        val difficulty = TrailBreakdown.fromMeters(
            mapOf(TrailDifficulty.HIKING to 600.0, TrailDifficulty.UNKNOWN to 400.0)
        )
        val uiState = sampleDetailUiState().copy(
            surfaceBreakdown = surface,
            difficultyBreakdown = difficulty
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeDetailScreen(
                    uiState = uiState,
                    scrubDistanceProvider = { 0.0 },
                    onScrubDistance = {},
                    onBack = {},
                    onZoomToRoute = {},
                    onExportGpx = {},
                    onOpenRouteStyle = {},
                    onStartRename = {},
                    onTitleDraftChange = {},
                    onCommitRename = {},
                    onCancelRename = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {}
                )
            }
        }

        val list = composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN)
        list.performScrollToKey("surface")
        composeRule.onNodeWithTag(OpenHikesTestTags.SURFACE_BAR)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher("surface state description") { node ->
                    val state = node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
                    state?.contains("Gravel") == true && state.contains("Paved")
                }
            )
        composeRule.onNodeWithText("Surfaces from OpenStreetMap.").assertIsDisplayed()

        list.performScrollToKey("difficulty")
        composeRule.onNodeWithTag(OpenHikesTestTags.DIFFICULTY_BAR)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher("difficulty state description") { node ->
                    val state = node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
                    state?.contains("Hiking") == true && state.contains("Unknown")
                }
            )
        composeRule.onNodeWithText(
            "Difficulty grades from OpenStreetMap (SAC scale), which describes 60% of this route."
        ).assertIsDisplayed()
    }

    @Test
    fun offlineDialogDisplaysQuotedMissingByteCountOverSwitchFallback() {
        val basemap = app.openhikes.core.model.OfflineCountryFile(
            role = app.openhikes.core.model.OfflineFileRole.BASEMAP,
            path = "countries/AT-20261126.pmtiles",
            byteCount = 10_000_000L,
            sha256 = "0".repeat(64),
            minimumZoom = 0,
            maximumZoom = 14
        )
        val elevation = app.openhikes.core.model.OfflineCountryFile(
            role = app.openhikes.core.model.OfflineFileRole.ELEVATION,
            path = "countries/AT-elevation-20261126.pmtiles",
            byteCount = 5_000_000L,
            sha256 = "1".repeat(64),
            minimumZoom = 12,
            maximumZoom = 12
        )
        val austria = app.openhikes.core.model.OfflineCountry(
            id = "AT",
            build = "20261126",
            schema = 4,
            bounds = app.openhikes.core.model.OfflineBounds(9.0, 46.0, 17.0, 49.0),
            files = listOf(basemap, elevation)
        )
        val uiState = sampleDetailUiState().copy(
            offlineCoverage = HikeOfflineCoverageState(
                crossed = listOf(austria),
                missing = listOf(austria),
                missingByteCounts = mapOf("AT" to 15_000_000L),
                includesDetail = false,
                includesElevation = false,
                showDialog = true
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeDetailScreen(
                    uiState = uiState,
                    scrubDistanceProvider = { 0.0 },
                    onScrubDistance = {},
                    onBack = {},
                    onZoomToRoute = {},
                    onExportGpx = {},
                    onOpenRouteStyle = {},
                    onStartRename = {},
                    onTitleDraftChange = {},
                    onCommitRename = {},
                    onCancelRename = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {}
                )
            }
        }

        val expectedSize = app.openhikes.core.ui.format.StorageSizeFormatter.formatBytes(15_000_000L)
        composeRule.onNodeWithText("Download ${austria.fullName()} · $expectedSize").assertIsDisplayed()
    }

    private fun samplePausedWalkUiState(): HikeDetailUiState {
        val walk = HikeWalk(
            id = "w1",
            hikeId = HikeId("h1"),
            startedAtEpochMillis = 1_767_225_600_000L,
            endedAtEpochMillis = 1_767_227_400_000L,
            distanceMeters = 425.0,
            durationSeconds = 1_800.0,
            coveredIntervals = listOf(0.0, 425.0),
            furthestDistanceMeters = 425.0,
            routeDistanceMeters = 850.0,
            endReason = TrailWalkEndReason.ENDED
        )
        return sampleDetailUiState().copy(
            walks = listOf(walk),
            walkSession = TrailWalkSessionState(
                walkedHikeId = HikeId("h1"),
                walkedHikeTitle = "Thumsee Loop",
                phase = TrailWalkPhase.PAUSED
            )
        )
    }

    private fun sampleWalkProgress(): TrailWalkProgressState = TrailWalkProgressState(
        hikeId = HikeId("h1"),
        coveredFraction = 0.5,
        metersLeft = 425.0,
        secondsLeft = 600.0
    )

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
