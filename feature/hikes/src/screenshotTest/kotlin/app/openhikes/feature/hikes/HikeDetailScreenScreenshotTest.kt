package app.openhikes.feature.hikes

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteProfile
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Hike Detail Light", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun HikeDetailLightPreview() {
    HikeDetailPreviewSurface(darkTheme = false)
}

@PreviewTest
@Preview(name = "Hike Detail Dark", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun HikeDetailDarkPreview() {
    HikeDetailPreviewSurface(darkTheme = true)
}

@PreviewTest
@Preview(
    name = "Hike Detail Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun HikeDetailLargeFontPreview() {
    HikeDetailPreviewSurface(darkTheme = false)
}

@Composable
private fun HikeDetailPreviewSurface(darkTheme: Boolean) {
    val route = sampleDetailRoute()
    val distance = RouteGeometry.routeLengthMeters(route)
    val hike = Hike(
        id = HikeId("preview-thumsee"),
        title = "Thumsee Loop",
        distanceMeters = distance,
        durationSeconds = 3_600.0,
        dateEpochMillis = 1_767_268_800_000L,
        trackDescription = "Forest loop circling the emerald mountain lake.",
        author = "OpenHikes",
        keywords = "lake,forest,loop",
        route = route
    )
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            HikeDetailScreen(
                uiState = HikeDetailUiState(
                    hikeId = hike.id,
                    hike = hike,
                    profile = RouteProfile.from(route),
                    statistics = HikeRouteStatistics.from(distance, route),
                    distanceUnit = DistanceUnit.METRIC,
                    isLoaded = true
                ),
                scrubDistanceProvider = { distance * 0.4 },
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
}

private fun sampleDetailRoute(): List<RouteCoordinate> = listOf(
    RouteCoordinate(47.7176, 12.8257, elevationMeters = 532.0, timestampEpochMillis = 1_767_268_800_000L),
    RouteCoordinate(47.7195, 12.8290, elevationMeters = 568.0, timestampEpochMillis = 1_767_269_700_000L),
    RouteCoordinate(47.7215, 12.8330, elevationMeters = 612.0, timestampEpochMillis = 1_767_270_600_000L),
    RouteCoordinate(47.7200, 12.8365, elevationMeters = 584.0, timestampEpochMillis = 1_767_271_500_000L),
    RouteCoordinate(47.7178, 12.8300, elevationMeters = 534.0, timestampEpochMillis = 1_767_272_400_000L)
)
