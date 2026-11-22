package app.openhikes.feature.hikes

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteTint
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Hike Library Empty Light", showBackground = true, widthDp = 411, heightDp = 640)
@Composable
fun HikeLibraryEmptyLightPreview() {
    HikeLibraryPreviewSurface(uiState = HikeLibraryUiState(), darkTheme = false)
}

@PreviewTest
@Preview(name = "Hike Library Populated Dark", showBackground = true, widthDp = 411, heightDp = 640)
@Composable
fun HikeLibraryPopulatedDarkPreview() {
    HikeLibraryPreviewSurface(uiState = samplePopulatedLibraryState(), darkTheme = true)
}

@PreviewTest
@Preview(
    name = "Hike Library Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 640,
    fontScale = 2.0f
)
@Composable
fun HikeLibraryPopulatedLargeFontPreview() {
    HikeLibraryPreviewSurface(uiState = samplePopulatedLibraryState(), darkTheme = false)
}

@Composable
private fun HikeLibraryPreviewSurface(uiState: HikeLibraryUiState, darkTheme: Boolean) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            HikeLibraryScreen(
                uiState = uiState,
                searchQuery = "",
                onSearchQueryChange = {},
                onClearSearch = {},
                onSearchFocusChanged = {},
                onOpenSettings = {},
                onImportGpxClick = {},
                onOpenHike = {},
                onRequestDelete = {},
                onConfirmDelete = {},
                onCancelDelete = {}
            )
        }
    }
}

private fun samplePopulatedLibraryState(): HikeLibraryUiState = HikeLibraryUiState(
    hikes = listOf(
        HikeSummary(
            id = HikeId("preview-thumsee"),
            title = "Thumsee Loop (fast, simulated)",
            distanceMeters = 5_420.0,
            durationSeconds = 4_200.0,
            dateEpochMillis = 1_767_268_800_000L,
            tintHex = RouteTint.DEFAULT_HEX,
            climbMeters = 142.0
        ),
        HikeSummary(
            id = HikeId("preview-ridge"),
            title = "Evening Alpine Crest",
            distanceMeters = 9_150.0,
            durationSeconds = 8_100.0,
            dateEpochMillis = 1_767_182_400_000L,
            tintHex = RouteTint.palette[1],
            climbMeters = 380.0
        )
    ),
    distanceUnit = DistanceUnit.METRIC
)
