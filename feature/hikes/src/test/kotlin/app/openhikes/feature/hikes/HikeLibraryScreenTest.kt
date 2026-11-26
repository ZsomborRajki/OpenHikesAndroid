package app.openhikes.feature.hikes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HikeLibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersEmptyPopulatedSearchErrorAndDeleteDialogStates() {
        val sample = HikeSummary(
            id = HikeId("h1"),
            title = "Thumsee Loop",
            distanceMeters = 5200.0,
            dateEpochMillis = 1_767_225_600_000L
        )
        var uiState by mutableStateOf(HikeLibraryUiState())
        var searchQuery by mutableStateOf("")
        var openedId: HikeId? = null
        var confirmedDelete = false
        var importDismissed = false

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeLibraryScreen(
                    uiState = uiState,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onClearSearch = { searchQuery = "" },
                    onSearchFocusChanged = {},
                    onOpenSettings = {},
                    onImportGpxClick = {},
                    onOpenHike = { openedId = it },
                    onRequestDelete = { uiState = uiState.copy(pendingDeleteHike = it) },
                    onConfirmDelete = { confirmedDelete = true },
                    onCancelDelete = { uiState = uiState.copy(pendingDeleteHike = null) },
                    onDismissImportError = { importDismissed = true }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_EMPTY_STATE).assertIsDisplayed()

        uiState = HikeLibraryUiState(
            hikes = listOf(sample),
            importError = GpxImportFailure.TOO_LARGE
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_IMPORT_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertThat(importDismissed).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRow("h1")).performClick()
        assertThat(openedId).isEqualTo(sample.id)

        searchQuery = "missing"
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_SEARCH_EMPTY_STATE).assertIsDisplayed()
        searchQuery = "Thumsee"
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_SEARCH_SECTION_HEADER).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowDelete("h1")).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_DELETE_CONFIRM_BUTTON).performClick()
        assertThat(confirmedDelete).isTrue()
    }

    @Test
    fun sortMenuTotalsButtonAndReorderControlsWorkWithPinnedActiveHike() {
        val (active, first, second) = sampleReorderHikes()
        var uiState by mutableStateOf(
            HikeLibraryUiState(
                hikes = listOf(active, first, second),
                activeHikeId = active.id,
                recordingHikeId = active.id
            )
        )
        var selectedSort: HikeListSort? = null
        var openedTotals = false
        val moves = mutableListOf<Pair<Int, Int>>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikeLibraryScreen(
                    uiState = uiState,
                    searchQuery = "",
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSearchFocusChanged = {},
                    onOpenSettings = {},
                    onOpenTotals = { openedTotals = true },
                    onImportGpxClick = {},
                    onOpenHike = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    onSelectSort = { selectedSort = it },
                    onEnterReorderMode = { uiState = uiState.copy(isReordering = true) },
                    onExitReorderMode = { uiState = uiState.copy(isReordering = false) },
                    onMoveHike = { from, to -> moves.add(from to to) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BUTTON).performClick()
        assertThat(openedTotals).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeSortOption(HikeListSort.HILLIEST.rawValue)).performClick()
        assertThat(selectedSort).isEqualTo(HikeListSort.HILLIEST)

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_REORDER_ACTION).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveUp(active.id.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveDown(active.id.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveUp(first.id.value)).assertIsNotEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveDown(first.id.value)).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveUp(second.id.value)).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeRowMoveDown(second.id.value)).assertIsNotEnabled()
        assertThat(moves).containsExactly(1 to 2, 2 to 1).inOrder()

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ORDER_BUTTON).assertIsDisplayed()
    }

    private fun sampleReorderHikes(): Triple<HikeSummary, HikeSummary, HikeSummary> = Triple(
        HikeSummary(
            id = HikeId("h-active"),
            title = "Active Walk",
            distanceMeters = 4_000.0,
            dateEpochMillis = 30_000L
        ),
        HikeSummary(id = HikeId("h-1"), title = "First Ridge", distanceMeters = 6_000.0, dateEpochMillis = 20_000L),
        HikeSummary(id = HikeId("h-2"), title = "Second Valley", distanceMeters = 9_000.0, dateEpochMillis = 10_000L)
    )
}
