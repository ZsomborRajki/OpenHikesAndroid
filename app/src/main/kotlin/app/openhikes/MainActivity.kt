package app.openhikes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.launch.LaunchConfigReader
import app.openhikes.shell.OpenHikesApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity entry point for OpenHikes.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var trailMapRenderer: TrailMapRenderer

    @Inject
    lateinit var entryProviderInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    @Inject
    lateinit var injectedLaunchConfig: LaunchConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val effectiveLaunchConfig = LaunchConfigReader.merge(injectedLaunchConfig, intent)
        setContent {
            OpenHikesTheme {
                OpenHikesApp(
                    trailMapRenderer = trailMapRenderer,
                    entryProviderInstallers = entryProviderInstallers,
                    launchConfig = effectiveLaunchConfig
                )
            }
        }
    }
}
