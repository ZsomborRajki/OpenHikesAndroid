package app.openhikes.feature.trailmaker

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteElevationSummary
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailTravelMode
import com.android.tools.screenshot.PreviewTest

private fun emptyPreviewRows(): List<TrailStopRowItem> = listOf(
    TrailStopRowItem(
        slotId = "open-start",
        position = 0,
        isOpen = true,
        waypointId = null,
        role = TrailStopRole.Start,
        name = "",
        distanceMeters = 0.0,
        arrivingSnap = null,
        pointNumber = null,
        canMoveUp = false,
        canMoveDown = false
    ),
    TrailStopRowItem(
        slotId = "open-end",
        position = 1,
        isOpen = true,
        waypointId = null,
        role = TrailStopRole.End,
        name = "",
        distanceMeters = 0.0,
        arrivingSnap = null,
        pointNumber = null,
        canMoveUp = false,
        canMoveDown = false
    )
)

private fun filledPreviewRows(secondSnap: TrailLegSnap = TrailLegSnap.Snapped): List<TrailStopRowItem> = listOf(
    TrailStopRowItem(
        slotId = "wp-1",
        position = 0,
        isOpen = false,
        waypointId = "wp-1",
        role = TrailStopRole.Start,
        name = "Thumsee Trailhead",
        distanceMeters = 0.0,
        arrivingSnap = null,
        pointNumber = 1,
        canMoveUp = false,
        canMoveDown = true
    ),
    TrailStopRowItem(
        slotId = "wp-2",
        position = 1,
        isOpen = false,
        waypointId = "wp-2",
        role = TrailStopRole.Stop(1),
        name = "Antoniberg Saddle",
        distanceMeters = 2_650.0,
        arrivingSnap = TrailLegSnap.Snapped,
        pointNumber = 2,
        canMoveUp = true,
        canMoveDown = true
    ),
    TrailStopRowItem(
        slotId = "wp-3",
        position = 2,
        isOpen = false,
        waypointId = "wp-3",
        role = TrailStopRole.End,
        name = "Kaitl",
        distanceMeters = 5_420.0,
        arrivingSnap = secondSnap,
        pointNumber = 3,
        canMoveUp = true,
        canMoveDown = false
    )
)

@PreviewTest
@Preview(name = "Trail Maker Light Empty", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun TrailMakerLightEmptyPreview() {
    TrailMakerPreviewHost(
        darkTheme = false,
        uiState = TrailMakerUiState(
            rows = emptyPreviewRows(),
            canBeSaved = false,
            isEmpty = true,
            canSnapToPaths = true,
            snapsToPaths = true
        )
    )
}

@PreviewTest
@Preview(name = "Trail Maker Light Stops Follow Paths", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun TrailMakerLightStopsPreview() {
    TrailMakerPreviewHost(
        darkTheme = false,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 4_878.0,
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true
        )
    )
}

@PreviewTest
@Preview(name = "Trail Maker Dark Offline Notice", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun TrailMakerDarkOfflineNoticePreview() {
    val offline = TrailLegSnap.Refused(TrailLegOutage.OFFLINE)
    TrailMakerPreviewHost(
        darkTheme = true,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(secondSnap = offline),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 4_878.0,
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true,
            hasRetryableLegs = true,
            worstLegSnap = offline
        )
    )
}

@PreviewTest
@Preview(name = "Trail Maker Side Panel", showBackground = true, widthDp = 340, heightDp = 640)
@Composable
fun TrailMakerSidePanelPreview() {
    TrailMakerPreviewHost(
        darkTheme = false,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 4_878.0,
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true
        )
    )
}

@PreviewTest
@Preview(
    name = "Trail Maker Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun TrailMakerLargeFontPreview() {
    TrailMakerPreviewHost(
        darkTheme = false,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 4_878.0,
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true
        )
    )
}

@PreviewTest
@Preview(name = "Trail Maker Light Measured Elevation", showBackground = true, widthDp = 411, heightDp = 820)
@Composable
fun TrailMakerLightMeasuredElevationPreview() {
    TrailMakerPreviewHost(
        darkTheme = false,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 5_880.0,
            elevationSummary = RouteElevationSummary(
                gainMeters = 412.0,
                lossMeters = 185.0,
                highMeters = 1_120.0,
                lowMeters = 708.0
            ),
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true,
            routeColoring = RouteColoring.ELEVATION,
            showsColoringSection = true,
            isElevationColoringAvailable = true
        )
    )
}

@PreviewTest
@Preview(name = "Trail Maker Dark Measured Elevation", showBackground = true, widthDp = 411, heightDp = 820)
@Composable
fun TrailMakerDarkMeasuredElevationPreview() {
    TrailMakerPreviewHost(
        darkTheme = true,
        uiState = TrailMakerUiState(
            travelMode = TrailTravelMode.HIKING,
            rows = filledPreviewRows(),
            distanceMeters = 5_420.0,
            travelTimeSeconds = 5_880.0,
            elevationSummary = RouteElevationSummary(
                gainMeters = 412.0,
                lossMeters = 185.0,
                highMeters = 1_120.0,
                lowMeters = 708.0
            ),
            canBeSaved = true,
            isEmpty = false,
            canSnapToPaths = true,
            snapsToPaths = true,
            routeColoring = RouteColoring.ELEVATION,
            showsColoringSection = true,
            isElevationColoringAvailable = true
        )
    )
}

@Composable
private fun TrailMakerPreviewHost(darkTheme: Boolean, uiState: TrailMakerUiState) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            TrailMakerScreen(
                uiState = uiState,
                onBack = {},
                onCloseRequested = {},
                onDiscardTrail = {},
                onKeepForLater = {},
                onConfirmSave = {},
                onSharePlace = { _, _ -> },
                onAction = {}
            )
        }
    }
}
