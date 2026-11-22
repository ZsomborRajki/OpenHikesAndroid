package app.openhikes.feature.hikes

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Route Style Light", showBackground = true, widthDp = 411, heightDp = 720)
@Composable
fun RouteStyleLightPreview() {
    RouteStylePreviewSurface(darkTheme = false)
}

@PreviewTest
@Preview(name = "Route Style Dark", showBackground = true, widthDp = 411, heightDp = 720)
@Composable
fun RouteStyleDarkPreview() {
    RouteStylePreviewSurface(darkTheme = true)
}

@PreviewTest
@Preview(
    name = "Route Style Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 720,
    fontScale = 2.0f
)
@Composable
fun RouteStyleLargeFontPreview() {
    RouteStylePreviewSurface(darkTheme = false)
}

@Composable
private fun RouteStylePreviewSurface(darkTheme: Boolean) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            RouteStyleScreen(
                uiState = RouteStyleUiState(
                    hikeId = HikeId("preview-thumsee"),
                    title = "Thumsee Loop",
                    tintHex = RouteTint.DEFAULT_HEX,
                    borderHex = RouteBorder.DEFAULT_HEX,
                    widthDp = 4.0,
                    linePattern = RouteLinePattern.DIRECTIONAL,
                    isLoaded = true
                ),
                onBack = {},
                onTintSelected = {},
                onBorderSelected = {},
                onWidthChanged = {},
                onPatternSelected = {}
            )
        }
    }
}
