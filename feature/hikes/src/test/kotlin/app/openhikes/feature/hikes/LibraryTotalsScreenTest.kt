package app.openhikes.feature.hikes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LibraryRecordCandidate
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryTotalsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersErrorRetryLoadedSectionsRecordsAndRedrawnFootnote() {
        val longestId = HikeId("h-longest")
        val highestId = HikeId("h-highest")
        val steepestId = HikeId("h-steepest")
        val totals = sampleTotals(
            longestId = longestId,
            highestId = highestId,
            steepestId = steepestId,
            outingsWithoutClimb = 1
        )
        var uiState by mutableStateOf(LibraryTotalsUiState(loadState = LibraryTotalsLoadState.Error))
        var retryClicks = 0
        var backClicks = 0
        val opened = mutableListOf<HikeId>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                LibraryTotalsScreen(
                    uiState = uiState,
                    onBack = { backClicks += 1 },
                    onRetry = { retryClicks += 1 },
                    onOpenHike = { opened.add(it) }
                )
            }
        }

        composeRule.onNodeWithText("Couldn’t Add Up Your Hikes").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_RETRY).performClick()
        assertThat(retryClicks).isEqualTo(1)

        uiState = LibraryTotalsUiState(
            loadState = LibraryTotalsLoadState.Loaded(totals),
            distanceUnit = DistanceUnit.METRIC
        )
        composeRule.waitForIdle()

        val list = composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN)
        list.assertIsDisplayed()
        composeRule.onAllNodesWithText("2026").onFirst().assertIsDisplayed()

        list.performScrollToKey("month_chart")
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_MONTH_CHART).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("May: 2026 18.5 km, 2025 12.0 km").assertExists()
        composeRule.onNodeWithContentDescription("January: 2026 4.0 km, 2025 2.0 km").assertExists()

        list.performScrollToKey("all_time")
        composeRule.onNodeWithText("All Time").assertIsDisplayed()

        list.performScrollToKey("records")
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_RECORD_LONGEST).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_RECORD_HIGHEST).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_RECORD_STEEPEST).assertIsDisplayed()
        assertThat(opened).containsExactly(longestId)

        list.performScrollToKey("footnotes")
        composeRule.onNodeWithText("changed since you hiked them", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.LIBRARY_TOTALS_BACK).performClick()
        assertThat(backClicks).isEqualTo(1)
    }

    @Test
    fun niceChartCeilingRoundsCleanlyForZeroAndPositivePeaks() {
        assertThat(niceChartCeiling(0.0)).isEqualTo(10.0)
        assertThat(niceChartCeiling(14.5)).isEqualTo(20.0)
        assertThat(niceChartCeiling(42.0)).isEqualTo(50.0)
        assertThat(niceChartCeiling(88.0)).isEqualTo(100.0)
    }

    private fun sampleTotals(
        longestId: HikeId,
        highestId: HikeId,
        steepestId: HikeId,
        outingsWithoutClimb: Int
    ): LibraryTotals {
        val monthsThisYear = List(12) { idx ->
            LibraryTotals.MonthDistance(
                monthNumber = idx + 1,
                startEpochMillis = idx.toLong(),
                endEpochMillis = (idx + 1).toLong(),
                distanceMeters = if (idx == 4) 18_500.0 else 4_000.0
            )
        }
        val monthsLastYear = List(12) { idx ->
            LibraryTotals.MonthDistance(
                monthNumber = idx + 1,
                startEpochMillis = idx.toLong(),
                endEpochMillis = (idx + 1).toLong(),
                distanceMeters = if (idx == 4) 12_000.0 else 2_000.0
            )
        }
        return LibraryTotals(
            year = 2026,
            previousYear = 2025,
            thisYear = LibraryTotals.Figures(
                distanceMeters = 62_500.0,
                climbMeters = 2_480.0,
                movingSeconds = 54_000.0,
                outings = 8,
                trails = 5,
                outingsWithoutClimb = outingsWithoutClimb
            ),
            lastYear = LibraryTotals.Figures(
                distanceMeters = 34_000.0,
                climbMeters = 1_200.0,
                movingSeconds = 28_800.0,
                outings = 4,
                trails = 3
            ),
            allTime = LibraryTotals.Figures(
                distanceMeters = 96_500.0,
                climbMeters = 3_680.0,
                movingSeconds = 82_800.0,
                outings = 12,
                trails = 7,
                outingsWithoutClimb = outingsWithoutClimb
            ),
            monthsThisYear = monthsThisYear,
            monthsLastYear = monthsLastYear,
            longest = LibraryRecordCandidate(longestId, "Alpine Traverse", 24_300.0, 24_300.0, 1_120.0, 2_150.0),
            highest = LibraryRecordCandidate(highestId, "Watzmann Ridge", 15_200.0, 15_200.0, 1_400.0, 2_713.0),
            steepest = LibraryRecordCandidate(steepestId, "Rinnkendlsteig", 5_100.0, 5_100.0, 820.0, 1_400.0)
        )
    }
}
