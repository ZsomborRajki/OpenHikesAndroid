package app.openhikes.core.testing.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight Compose semantic fake for [TrailMapRenderer] used in JVM, screenshot, and UI tests.
 *
 * Records camera state, overlay configuration, viewport padding, and applied commands without
 * initializing MapLibre native libraries or OpenGL surfaces.
 */
@Singleton
class FakeTrailMapRenderer @Inject constructor() : TrailMapRenderer {
    private val cameraStateFlow = MutableStateFlow(MapCameraState())
    private val overlayStateFlow = MutableStateFlow<MapOverlayState?>(null)
    private val viewportPaddingFlow = MutableStateFlow<PaddingValues?>(null)
    private val handledCommandsFlow = MutableStateFlow<List<MapCameraCommand>>(emptyList())

    val lastCameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()
    val lastOverlayState: StateFlow<MapOverlayState?> = overlayStateFlow.asStateFlow()
    val lastViewportPadding: StateFlow<PaddingValues?> = viewportPaddingFlow.asStateFlow()
    val handledCommands: StateFlow<List<MapCameraCommand>> = handledCommandsFlow.asStateFlow()

    @Composable
    override fun Render(
        cameraState: MapCameraState,
        cameraCommand: MapCameraCommand?,
        overlayState: MapOverlayState,
        viewportPadding: PaddingValues,
        interactions: MapInteractionEvents,
        onCameraCommandHandled: (Long) -> Unit,
        modifier: Modifier
    ) {
        LaunchedEffect(cameraState) {
            cameraStateFlow.value = cameraState
        }

        LaunchedEffect(overlayState, viewportPadding) {
            overlayStateFlow.value = overlayState
            viewportPaddingFlow.value = viewportPadding
            interactions.onStyleReady()
        }

        LaunchedEffect(cameraCommand?.id) {
            val command = cameraCommand ?: return@LaunchedEffect
            val nextCamera = applyCommand(cameraStateFlow.value, command)
            cameraStateFlow.value = nextCamera
            handledCommandsFlow.value = handledCommandsFlow.value + command
            interactions.onCameraChanged(nextCamera)
            onCameraCommandHandled(command.id)
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Trail map" }
                .testTag(OpenHikesTestTags.TRAIL_MAP)
        )
    }

    private fun applyCommand(current: MapCameraState, command: MapCameraCommand): MapCameraState = when (command) {
        is MapCameraCommand.MoveTo -> current.copy(
            center = command.center,
            zoom = (command.zoom ?: current.zoom).coerceIn(
                MapCameraState.MIN_ZOOM,
                MapCameraState.MAX_ZOOM
            ),
            bearing = command.bearing ?: current.bearing,
            pitch = command.pitch ?: current.pitch
        )

        is MapCameraCommand.ZoomIn -> current.zoomedIn(command.delta)
        is MapCameraCommand.ZoomOut -> current.zoomedOut(command.delta)
        is MapCameraCommand.ResetNorth -> current.resetNorth()
    }
}
