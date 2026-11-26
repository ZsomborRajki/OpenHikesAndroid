package app.openhikes.feature.routeedit

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteProfile
import com.android.tools.screenshot.PreviewTest
import kotlinx.coroutines.flow.MutableStateFlow

private fun previewRouteProfile(): RouteProfile {
    val startMillis = 1_780_000_000_000L
    val points = List(24) { step ->
        val elev = 530.0 + if (step <= 12) step * 18.0 else (24 - step) * 18.0
        RouteCoordinate(
            latitude = 47.71842 + step * 0.0007,
            longitude = 12.83177 + step * 0.0004,
            elevationMeters = elev,
            timestampEpochMillis = startMillis + step * 90_000L
        )
    }
    return RouteProfile.from(points)
}

@PreviewTest
@Preview(name = "Route Edit Light With Drive Offer", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun RouteEditLightDriveOfferPreview() {
    val profile = previewRouteProfile()
    RouteEditPreviewHost(
        darkTheme = false,
        uiState = RouteEditUiState(
            hikeId = HikeId("preview-hike"),
            isLoading = false,
            profile = profile,
            trailingCarriedOfferMeters = 5_800.0,
            hasChanges = false
        ),
        rangeState = RouteTrimRangeState(
            startMeters = 0.0,
            endMeters = profile.totalDistanceMeters,
            totalMeters = profile.totalDistanceMeters,
            displayedDistanceMeters = profile.totalDistanceMeters,
            removedMeters = 0.0,
            keptDurationSeconds = 2_070.0
        )
    )
}

@PreviewTest
@Preview(name = "Route Edit Dark Stretch Mode", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun RouteEditDarkStretchModePreview() {
    val profile = previewRouteProfile()
    val start = profile.totalDistanceMeters / 3.0
    val end = profile.totalDistanceMeters * 2.0 / 3.0
    RouteEditPreviewHost(
        darkTheme = true,
        uiState = RouteEditUiState(
            hikeId = HikeId("preview-hike"),
            isLoading = false,
            profile = profile,
            isSelectingStretch = true,
            canFollowTrails = true,
            hasOriginal = true,
            hasSteps = true,
            canUndo = false
        ),
        rangeState = RouteTrimRangeState(
            startMeters = start,
            endMeters = end,
            totalMeters = profile.totalDistanceMeters,
            displayedDistanceMeters = end - start,
            removedMeters = 0.0
        )
    )
}

@PreviewTest
@Preview(name = "Route Edit Side Panel Trimmed", showBackground = true, widthDp = 340, heightDp = 640)
@Composable
fun RouteEditSidePanelTrimmedPreview() {
    val profile = previewRouteProfile()
    val start = profile.totalDistanceMeters * 0.1
    val end = profile.totalDistanceMeters * 0.72
    RouteEditPreviewHost(
        darkTheme = false,
        uiState = RouteEditUiState(
            hikeId = HikeId("preview-hike"),
            isLoading = false,
            profile = profile,
            hasOriginal = true,
            hasSteps = true,
            canUndo = true,
            hasChanges = true
        ),
        rangeState = RouteTrimRangeState(
            startMeters = start,
            endMeters = end,
            totalMeters = profile.totalDistanceMeters,
            displayedDistanceMeters = end - start,
            removedMeters = profile.totalDistanceMeters - (end - start),
            keptDurationSeconds = 1_320.0
        )
    )
}

@PreviewTest
@Preview(
    name = "Route Edit Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun RouteEditLargeFontPreview() {
    val profile = previewRouteProfile()
    RouteEditPreviewHost(
        darkTheme = false,
        uiState = RouteEditUiState(
            hikeId = HikeId("preview-hike"),
            isLoading = false,
            profile = profile,
            trailingCarriedOfferMeters = 5_800.0,
            hasOriginal = true
        ),
        rangeState = RouteTrimRangeState(
            startMeters = 0.0,
            endMeters = profile.totalDistanceMeters,
            totalMeters = profile.totalDistanceMeters,
            displayedDistanceMeters = profile.totalDistanceMeters,
            removedMeters = 0.0,
            keptDurationSeconds = 2_070.0
        )
    )
}

@Composable
private fun RouteEditPreviewHost(darkTheme: Boolean, uiState: RouteEditUiState, rangeState: RouteTrimRangeState) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            RouteEditScreen(
                uiState = uiState,
                rangeStateFlow = MutableStateFlow(rangeState),
                onBack = {},
                onSave = {},
                onConfirmRevert = {},
                onAction = {}
            )
        }
    }
}
