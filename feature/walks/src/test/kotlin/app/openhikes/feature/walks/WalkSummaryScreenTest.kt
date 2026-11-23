package app.openhikes.feature.walks

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalkSummaryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysCompletionSemanticsAndShowOnMapAction() {
        var showedOnMap = false
        val walk = HikeWalk(
            id = "w1",
            hikeId = HikeId("h1"),
            startedAtEpochMillis = 1_767_268_800_000L,
            endedAtEpochMillis = 1_767_272_400_000L,
            distanceMeters = 2_500.0,
            durationSeconds = 3_600.0,
            coveredIntervals = listOf(0.0, 2_500.0),
            furthestDistanceMeters = 2_500.0,
            routeDistanceMeters = 5_000.0,
            endReason = TrailWalkEndReason.ENDED
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                WalkSummaryScreen(
                    uiState = WalkSummaryUiState(
                        hikeId = walk.hikeId,
                        walkId = walk.id,
                        hikeTitle = "Thumsee Loop",
                        walk = walk,
                        trailAscentMeters = 180.0,
                        canShowOnMap = true,
                        isLoaded = true
                    ),
                    onBack = {},
                    onShowOnMap = { showedOnMap = true },
                    zoneId = ZoneId.of("UTC")
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_COMPLETION)
            .assertIsDisplayed()
            .assertContentDescriptionContains("50 percent complete", substring = true)
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SHOW_ON_MAP)
            .assertIsEnabled()
            .performClick()
        assertThat(showedOnMap).isTrue()
    }

    @Test
    fun disablesShowOnMapAndShowsNoticeWhenTrailChanged() {
        val walk = HikeWalk(
            id = "w2",
            hikeId = HikeId("h1"),
            startedAtEpochMillis = 1_767_268_800_000L,
            endedAtEpochMillis = 1_767_272_400_000L,
            distanceMeters = 5_000.0,
            durationSeconds = 3_600.0,
            coveredIntervals = listOf(0.0, 5_000.0),
            furthestDistanceMeters = 5_000.0,
            routeDistanceMeters = 5_000.0,
            endReason = TrailWalkEndReason.ROUTE_CHANGED
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                WalkSummaryScreen(
                    uiState = WalkSummaryUiState(
                        hikeId = walk.hikeId,
                        walkId = walk.id,
                        hikeTitle = "Thumsee Loop",
                        walk = walk,
                        isRouteChanged = true,
                        canShowOnMap = false,
                        isLoaded = true
                    ),
                    onBack = {},
                    onShowOnMap = {},
                    zoneId = ZoneId.of("UTC")
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SHOW_ON_MAP).assertIsNotEnabled()
        composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TRAIL_CHANGED).assertIsDisplayed()
    }
}
