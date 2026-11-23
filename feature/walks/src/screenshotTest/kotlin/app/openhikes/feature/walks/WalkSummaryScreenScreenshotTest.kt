package app.openhikes.feature.walks

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.TrailWalkEndReason
import com.android.tools.screenshot.PreviewTest
import java.time.ZoneId

@PreviewTest
@Preview(name = "Walk Summary Light", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun WalkSummaryLightPreview() {
    WalkSummaryPreviewHost(darkTheme = false, uiState = sampleHalfWalkState())
}

@PreviewTest
@Preview(name = "Walk Summary Dark", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun WalkSummaryDarkPreview() {
    WalkSummaryPreviewHost(darkTheme = true, uiState = sampleCompletedWalkState())
}

@PreviewTest
@Preview(name = "Walk Summary Route Changed", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun WalkSummaryRouteChangedPreview() {
    WalkSummaryPreviewHost(
        darkTheme = false,
        uiState = sampleHalfWalkState().copy(
            isRouteChanged = true,
            canShowOnMap = false
        )
    )
}

@PreviewTest
@Preview(
    name = "Walk Summary Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun WalkSummaryLargeFontPreview() {
    WalkSummaryPreviewHost(darkTheme = false, uiState = sampleHalfWalkState())
}

@Composable
private fun WalkSummaryPreviewHost(darkTheme: Boolean, uiState: WalkSummaryUiState) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            WalkSummaryScreen(
                uiState = uiState,
                onBack = {},
                onShowOnMap = {},
                zoneId = ZoneId.of("UTC")
            )
        }
    }
}

private fun sampleHalfWalkState(): WalkSummaryUiState {
    val walk = HikeWalk(
        id = "preview-half",
        hikeId = HikeId("preview-thumsee"),
        startedAtEpochMillis = 1_767_268_800_000L,
        endedAtEpochMillis = 1_767_271_500_000L,
        distanceMeters = 2_710.0,
        durationSeconds = 2_700.0,
        coveredIntervals = listOf(0.0, 2_710.0),
        furthestDistanceMeters = 2_710.0,
        routeDistanceMeters = 5_420.0,
        endReason = TrailWalkEndReason.ENDED
    )
    return WalkSummaryUiState(
        hikeId = walk.hikeId,
        walkId = walk.id,
        hikeTitle = "Thumsee Loop",
        walk = walk,
        trailAscentMeters = 142.0,
        distanceUnit = DistanceUnit.METRIC,
        canShowOnMap = true,
        isLoaded = true
    )
}

private fun sampleCompletedWalkState(): WalkSummaryUiState {
    val walk = HikeWalk(
        id = "preview-complete",
        hikeId = HikeId("preview-thumsee"),
        startedAtEpochMillis = 1_767_268_800_000L,
        endedAtEpochMillis = 1_767_274_200_000L,
        distanceMeters = 5_420.0,
        durationSeconds = 5_400.0,
        coveredIntervals = listOf(0.0, 5_420.0),
        furthestDistanceMeters = 5_420.0,
        routeDistanceMeters = 5_420.0,
        endReason = TrailWalkEndReason.REACHED_END
    )
    return WalkSummaryUiState(
        hikeId = walk.hikeId,
        walkId = walk.id,
        hikeTitle = "Thumsee Loop",
        walk = walk,
        trailAscentMeters = 142.0,
        distanceUnit = DistanceUnit.METRIC,
        canShowOnMap = true,
        isLoaded = true
    )
}
