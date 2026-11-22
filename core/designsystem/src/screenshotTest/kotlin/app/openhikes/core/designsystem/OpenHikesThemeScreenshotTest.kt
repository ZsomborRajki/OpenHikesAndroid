package app.openhikes.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Light Fallback Theme", showBackground = true)
@Composable
fun LightFallbackThemePreview() {
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Text(
                text = "OpenHikes",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Dark Fallback Theme", showBackground = true)
@Composable
fun DarkFallbackThemePreview() {
    OpenHikesTheme(darkTheme = true, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Text(
                text = "OpenHikes",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
