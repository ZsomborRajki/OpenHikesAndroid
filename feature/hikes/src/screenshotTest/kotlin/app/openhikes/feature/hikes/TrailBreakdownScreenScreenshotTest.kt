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
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.TrailBreakdown
import app.openhikes.core.model.TrailDifficulty
import app.openhikes.core.model.TrailSurface
import app.openhikes.core.ui.components.RouteColoringPicker
import app.openhikes.core.ui.components.TrailDifficultySection
import app.openhikes.core.ui.components.TrailSurfaceSection
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Trail Breakdowns Light", showBackground = true, widthDp = 411, heightDp = 820)
@Composable
fun TrailBreakdownsLightPreview() {
    TrailBreakdownsPreviewSurface(darkTheme = false)
}

@PreviewTest
@Preview(name = "Trail Breakdowns Dark", showBackground = true, widthDp = 411, heightDp = 820)
@Composable
fun TrailBreakdownsDarkPreview() {
    TrailBreakdownsPreviewSurface(darkTheme = true)
}

@PreviewTest
@Preview(
    name = "Trail Breakdowns Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 1120,
    fontScale = 2.0f
)
@Composable
fun TrailBreakdownsLargeFontPreview() {
    TrailBreakdownsPreviewSurface(darkTheme = false)
}

@Composable
private fun TrailBreakdownsPreviewSurface(darkTheme: Boolean) {
    val surface = TrailBreakdown.fromMeters(
        mapOf(
            TrailSurface.GRAVEL to 2_840.0,
            TrailSurface.GROUND to 1_360.0,
            TrailSurface.PAVED to 600.0
        )
    )
    val difficulty = TrailBreakdown.fromMeters(
        mapOf(
            TrailDifficulty.HIKING to 2_400.0,
            TrailDifficulty.MOUNTAIN_HIKING to 1_440.0,
            TrailDifficulty.DEMANDING_MOUNTAIN_HIKING to 480.0,
            TrailDifficulty.UNKNOWN to 480.0
        )
    )
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TrailSurfaceSection(breakdown = surface, distanceUnit = DistanceUnit.METRIC)
                TrailDifficultySection(breakdown = difficulty, distanceUnit = DistanceUnit.METRIC)
                RouteColoringPicker(
                    selectedColoring = RouteColoring.ELEVATION,
                    onSelectColoring = {}
                )
            }
        }
    }
}
