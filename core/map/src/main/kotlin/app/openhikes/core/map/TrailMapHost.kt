package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Single public map surface entry point hosted permanently underneath adaptive sheets/panels.
 */
@Composable
fun TrailMapHost(
    renderer: TrailMapRenderer,
    cameraState: MapCameraState,
    cameraCommand: MapCameraCommand?,
    overlayState: MapOverlayState,
    viewportPadding: PaddingValues,
    interactions: MapInteractionEvents,
    onCameraCommandHandled: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    renderer.Render(
        cameraState = cameraState,
        cameraCommand = cameraCommand,
        overlayState = overlayState,
        viewportPadding = viewportPadding,
        interactions = interactions,
        onCameraCommandHandled = onCameraCommandHandled,
        modifier = modifier
    )
}
