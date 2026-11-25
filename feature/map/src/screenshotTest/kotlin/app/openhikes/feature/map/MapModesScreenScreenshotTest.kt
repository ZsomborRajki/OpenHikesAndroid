package app.openhikes.feature.map

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Map Modes Light Installed", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun MapModesLightInstalledPreview() {
    MapModesPreviewHost(
        darkTheme = false,
        uiState = MapModesUiState(
            installedCountryNames = listOf("Austria", "Germany"),
            installedByteCount = 2_410_000_000L
        )
    )
}

@PreviewTest
@Preview(name = "Map Modes Dark Clean Map", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun MapModesDarkCleanMapPreview() {
    MapModesPreviewHost(
        darkTheme = true,
        uiState = MapModesUiState(
            styles = defaultStyleCards(selectedStyle = MapStyleCatalog.openFreeMap),
            selectedStyle = MapStyleCatalog.openFreeMap,
            showsHikingRoutes = false,
            attribution = MapAttribution.forMapModes(
                selected = MapStyleCatalog.openFreeMap,
                showsHikingRoutes = false,
                picturedStyles = MapStyleCatalog.usableStyles(MapProviderKeys.Empty)
            )
        )
    )
}

@PreviewTest
@Preview(name = "Map Modes Side Panel", showBackground = true, widthDp = 340, heightDp = 640)
@Composable
fun MapModesSidePanelPreview() {
    MapModesPreviewHost(
        darkTheme = false,
        uiState = MapModesUiState()
    )
}

@PreviewTest
@Preview(
    name = "Map Modes Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun MapModesLargeFontPreview() {
    MapModesPreviewHost(
        darkTheme = false,
        uiState = MapModesUiState(
            installedCountryNames = listOf("Austria"),
            installedByteCount = 1_470_000_000L
        )
    )
}

@Composable
private fun MapModesPreviewHost(darkTheme: Boolean, uiState: MapModesUiState) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            MapModesScreen(
                uiState = uiState,
                onClose = {},
                onSelectStyle = {},
                onSetShowsHikingRoutes = {},
                onOpenOfflineMaps = {},
                onOpenUrl = {}
            )
        }
    }
}
