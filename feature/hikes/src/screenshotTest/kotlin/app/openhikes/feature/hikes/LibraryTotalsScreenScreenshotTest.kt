package app.openhikes.feature.hikes

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LibraryRecordCandidate
import app.openhikes.core.model.LibraryTotals
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Library Totals Loaded Light", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun LibraryTotalsLoadedLightPreview() {
    LibraryTotalsPreviewSurface(
        uiState = LibraryTotalsUiState(
            loadState = LibraryTotalsLoadState.Loaded(samplePreviewTotals()),
            distanceUnit = DistanceUnit.METRIC
        ),
        darkTheme = false
    )
}

@PreviewTest
@Preview(name = "Library Totals Loaded Dark", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun LibraryTotalsLoadedDarkPreview() {
    LibraryTotalsPreviewSurface(
        uiState = LibraryTotalsUiState(
            loadState = LibraryTotalsLoadState.Loaded(samplePreviewTotals()),
            distanceUnit = DistanceUnit.METRIC
        ),
        darkTheme = true
    )
}

@PreviewTest
@Preview(
    name = "Library Totals Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun LibraryTotalsLargeFontPreview() {
    LibraryTotalsPreviewSurface(
        uiState = LibraryTotalsUiState(
            loadState = LibraryTotalsLoadState.Loaded(samplePreviewTotals()),
            distanceUnit = DistanceUnit.METRIC
        ),
        darkTheme = false
    )
}

@PreviewTest
@Preview(name = "Library Totals Error Light", showBackground = true, widthDp = 411, heightDp = 640)
@Composable
fun LibraryTotalsErrorLightPreview() {
    LibraryTotalsPreviewSurface(
        uiState = LibraryTotalsUiState(loadState = LibraryTotalsLoadState.Error),
        darkTheme = false
    )
}

@Composable
private fun LibraryTotalsPreviewSurface(uiState: LibraryTotalsUiState, darkTheme: Boolean) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            LibraryTotalsScreen(
                uiState = uiState,
                onBack = {},
                onRetry = {},
                onOpenHike = {}
            )
        }
    }
}

private fun samplePreviewTotals(): LibraryTotals {
    val thisYearKm = listOf(8.0, 12.0, 19.0, 26.0, 38.0, 44.0, 31.0, 22.0, 16.0, 0.0, 0.0, 0.0)
    val lastYearKm = listOf(6.0, 9.0, 14.0, 18.0, 24.0, 29.0, 35.0, 28.0, 21.0, 15.0, 10.0, 7.0)
    return LibraryTotals(
        year = 2026,
        previousYear = 2025,
        thisYear = LibraryTotals.Figures(
            distanceMeters = 216_000.0,
            climbMeters = 7_840.0,
            movingSeconds = 198_000.0,
            outings = 19,
            trails = 11,
            outingsWithoutClimb = 1
        ),
        lastYear = LibraryTotals.Figures(
            distanceMeters = 216_000.0,
            climbMeters = 6_920.0,
            movingSeconds = 185_400.0,
            outings = 21,
            trails = 14
        ),
        allTime = LibraryTotals.Figures(
            distanceMeters = 432_000.0,
            climbMeters = 14_760.0,
            movingSeconds = 383_400.0,
            outings = 40,
            trails = 19,
            outingsWithoutClimb = 1
        ),
        monthsThisYear = thisYearKm.mapIndexed { idx, km ->
            LibraryTotals.MonthDistance(idx + 1, idx.toLong(), (idx + 1).toLong(), km * 1_000.0)
        },
        monthsLastYear = lastYearKm.mapIndexed { idx, km ->
            LibraryTotals.MonthDistance(idx + 1, idx.toLong(), (idx + 1).toLong(), km * 1_000.0)
        },
        longest = LibraryRecordCandidate(
            hikeId = HikeId("preview-longest"),
            title = "Berchtesgaden Traverse",
            distanceMeters = 24_600.0,
            walkedMeters = 24_600.0,
            climbMeters = 1_340.0,
            highestMeters = 2_180.0
        ),
        highest = LibraryRecordCandidate(
            hikeId = HikeId("preview-highest"),
            title = "Watzmann Hocheck",
            distanceMeters = 18_400.0,
            walkedMeters = 18_400.0,
            climbMeters = 1_890.0,
            highestMeters = 2_651.0
        ),
        steepest = LibraryRecordCandidate(
            hikeId = HikeId("preview-steepest"),
            title = "Rinnkendlsteig",
            distanceMeters = 4_900.0,
            walkedMeters = 4_900.0,
            climbMeters = 810.0,
            highestMeters = 1_410.0
        )
    )
}
