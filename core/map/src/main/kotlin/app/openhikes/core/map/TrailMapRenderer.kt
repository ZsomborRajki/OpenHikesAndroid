package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the map rendering engine so production binds MapLibre Compose and
 * JVM/Compose tests bind a lightweight semantic fake without native GL setup.
 */
interface TrailMapRenderer {
    @Composable
    fun Render(
        cameraState: MapCameraState,
        cameraCommand: MapCameraCommand?,
        overlayState: MapOverlayState,
        viewportPadding: PaddingValues,
        interactions: MapInteractionEvents,
        onCameraCommandHandled: (Long) -> Unit,
        highlightState: StateFlow<MapRouteHighlight?>,
        liveRecordingOverlayState: StateFlow<MapRouteOverlay?>,
        modifier: Modifier
    )
}
