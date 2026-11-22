package app.openhikes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.launch.LaunchConfigReader
import app.openhikes.shell.OpenHikesApp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
    lateinit var launchConfigHolder: LaunchConfigHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply debug launch Intent overrides before super.onCreate constructs Hilt ViewModels
        // and DataStore instances that depend on the effective LaunchConfig.
        EntryPointAccessors
            .fromApplication(applicationContext, LaunchConfigEntryPoint::class.java)
            .launchConfigHolder()
            .mergeWith { base -> LaunchConfigReader.merge(base, intent) }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val effectiveLaunchConfig by launchConfigHolder.current.collectAsStateWithLifecycle()
            OpenHikesTheme {
                OpenHikesApp(
                    trailMapRenderer = trailMapRenderer,
                    entryProviderInstallers = entryProviderInstallers,
                    launchConfig = effectiveLaunchConfig
                )
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface LaunchConfigEntryPoint {
        fun launchConfigHolder(): LaunchConfigHolder
    }
}
