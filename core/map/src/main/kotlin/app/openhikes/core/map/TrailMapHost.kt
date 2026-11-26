package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    modifier: Modifier = Modifier,
    highlightState: StateFlow<MapRouteHighlight?> = remember { MutableStateFlow(null) },
    liveRecordingOverlayState: StateFlow<MapRouteOverlay?> = remember { MutableStateFlow(null) },
    walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?> = remember { MutableStateFlow(null) },
    routeEditOverlayState: StateFlow<MapRouteEditOverlay?> = remember { MutableStateFlow(null) },
    routeEditHandlerProvider: () -> RouteEditMapInteractionHandler? = { null },
    trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?> = remember { MutableStateFlow(null) },
    trailDraftHandlerProvider: () -> TrailDraftMapInteractionHandler? = { null },
    trailPlacesOverlayState: StateFlow<MapTrailPlacesOverlay?> = remember { MutableStateFlow(null) },
    trailPlacesHandlerProvider: () -> TrailPlacesMapInteractionHandler? = { null },
    trailPhotosOverlayState: StateFlow<MapTrailPhotosOverlay?> = remember { MutableStateFlow(null) },
    trailPhotosHandlerProvider: () -> TrailPhotosMapInteractionHandler? = { null }
) {
    renderer.Render(
        cameraState = cameraState,
        cameraCommand = cameraCommand,
        overlayState = overlayState,
        viewportPadding = viewportPadding,
        interactions = interactions,
        onCameraCommandHandled = onCameraCommandHandled,
        highlightState = highlightState,
        liveRecordingOverlayState = liveRecordingOverlayState,
        modifier = modifier,
        walkCoverageOverlayState = walkCoverageOverlayState,
        routeEditOverlayState = routeEditOverlayState,
        routeEditHandlerProvider = routeEditHandlerProvider,
        trailDraftOverlayState = trailDraftOverlayState,
        trailDraftHandlerProvider = trailDraftHandlerProvider,
        trailPlacesOverlayState = trailPlacesOverlayState,
        trailPlacesHandlerProvider = trailPlacesHandlerProvider,
        trailPhotosOverlayState = trailPhotosOverlayState,
        trailPhotosHandlerProvider = trailPhotosHandlerProvider
    )
}
