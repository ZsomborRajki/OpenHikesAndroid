package app.openhikes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.ui.testing.OpenHikesTestTags
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity entry point for OpenHikes.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenHikesTheme {
                OpenHikesApp()
            }
        }
    }
}

/**
 * Stateless root shell composable hosting the edge-to-edge surface and shared semantics root.
 */
@Composable
fun OpenHikesApp(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(OpenHikesTestTags.APP_ROOT),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
