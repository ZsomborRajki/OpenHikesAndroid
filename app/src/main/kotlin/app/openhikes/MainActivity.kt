package app.openhikes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingEntryCoordinator
import app.openhikes.core.location.RecordingForegroundService
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.HikeId
import app.openhikes.core.network.VectorTileArchivesManager
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.launch.LaunchConfigReader
import app.openhikes.shell.OpenHikesApp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.launch

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

    @Inject
    lateinit var recordingController: RecordingController

    @Inject
    lateinit var recordingEntryCoordinator: RecordingEntryCoordinator

    @Inject
    lateinit var trailWalkController: TrailWalkController

    @Inject
    lateinit var vectorTileArchivesManager: VectorTileArchivesManager

    private var openRecordingRequestCount by mutableIntStateOf(0)
    private var openWalkRequest by mutableStateOf<Pair<Int, HikeId>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply debug launch Intent overrides before super.onCreate constructs Hilt ViewModels
        // and DataStore instances that depend on the effective LaunchConfig.
        EntryPointAccessors
            .fromApplication(applicationContext, LaunchConfigEntryPoint::class.java)
            .launchConfigHolder()
            .mergeWith { base -> LaunchConfigReader.merge(base, intent) }

        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleLaunchNavigationIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            val effectiveLaunchConfig by launchConfigHolder.current.collectAsStateWithLifecycle()
            OpenHikesTheme {
                OpenHikesApp(
                    trailMapRenderer = trailMapRenderer,
                    entryProviderInstallers = entryProviderInstallers,
                    launchConfig = effectiveLaunchConfig,
                    openRecordingRequestCount = openRecordingRequestCount,
                    openWalkHikeRequest = openWalkRequest,
                    onRequestAutoStartRecording = recordingEntryCoordinator::requestAutoStart
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchNavigationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        trailWalkController.onAppForegrounded()
        lifecycleScope.launch {
            vectorTileArchivesManager.refresh()
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            recordingController.flushJournal()
        }
    }

    private fun handleLaunchNavigationIntent(targetIntent: Intent?) {
        if (targetIntent?.getBooleanExtra(RecordingForegroundService.EXTRA_OPEN_RECORDING, false) == true) {
            openRecordingRequestCount += 1
        }
        val walkHikeId = targetIntent?.getStringExtra(RecordingForegroundService.EXTRA_OPEN_WALK_HIKE_ID)
            ?.takeIf { it.isNotBlank() }
        if (walkHikeId != null) {
            val nextSeq = (openWalkRequest?.first ?: 0) + 1
            openWalkRequest = nextSeq to HikeId(walkHikeId)
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface LaunchConfigEntryPoint {
        fun launchConfigHolder(): LaunchConfigHolder
    }
}
