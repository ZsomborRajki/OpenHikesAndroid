package app.openhikes.feature.hikes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.ui.components.ElevationPlaceholderCard
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Elevation Placeholder Light", showBackground = true, widthDp = 411, heightDp = 440)
@Composable
fun ElevationPlaceholderLightPreview() {
    ElevationPlaceholderPreviewHost(darkTheme = false)
}

@PreviewTest
@Preview(name = "Elevation Placeholder Dark", showBackground = true, widthDp = 411, heightDp = 440)
@Composable
fun ElevationPlaceholderDarkPreview() {
    ElevationPlaceholderPreviewHost(darkTheme = true)
}

@PreviewTest
@Preview(
    name = "Elevation Placeholder Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 500,
    fontScale = 2.0f
)
@Composable
fun ElevationPlaceholderLargeFontPreview() {
    ElevationPlaceholderPreviewHost(darkTheme = false)
}

@Composable
private fun ElevationPlaceholderPreviewHost(darkTheme: Boolean) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ElevationPlaceholderCard(tintHex = "#1E7F4F", isReading = true)
                ElevationPlaceholderCard(tintHex = "#1E7F4F", isReading = false)
            }
        }
    }
}
