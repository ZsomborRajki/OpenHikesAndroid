package app.openhikes.core.testing.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.ui.testing.OpenHikesTestTags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight Compose semantic fake for [TrailMapRenderer] used in JVM, screenshot, and UI tests.
 *
 * Records camera state, overlay configuration, route highlights, route-edit handles, viewport padding,
 * and applied commands without initializing MapLibre native libraries or OpenGL surfaces.
 */
@Singleton
class FakeTrailMapRenderer @Inject constructor() : TrailMapRenderer {
    private val cameraStateFlow = MutableStateFlow(MapCameraState())
    private val overlayStateFlow = MutableStateFlow<MapOverlayState?>(null)
    private val highlightStateFlow = MutableStateFlow<MapRouteHighlight?>(null)
    private val liveRecordingOverlayFlow = MutableStateFlow<MapRouteOverlay?>(null)
    private val walkCoverageOverlayFlow = MutableStateFlow<MapWalkCoverageOverlay?>(null)
    private val routeEditOverlayFlow = MutableStateFlow<MapRouteEditOverlay?>(null)
    private val viewportPaddingFlow = MutableStateFlow<PaddingValues?>(null)
    private val handledCommandsFlow = MutableStateFlow<List<MapCameraCommand>>(emptyList())

    val lastCameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()
    val lastOverlayState: StateFlow<MapOverlayState?> = overlayStateFlow.asStateFlow()
    val lastHighlight: StateFlow<MapRouteHighlight?> = highlightStateFlow.asStateFlow()
    val lastLiveRecordingOverlay: StateFlow<MapRouteOverlay?> = liveRecordingOverlayFlow.asStateFlow()
    val lastWalkCoverageOverlay: StateFlow<MapWalkCoverageOverlay?> = walkCoverageOverlayFlow.asStateFlow()
    val lastRouteEditOverlay: StateFlow<MapRouteEditOverlay?> = routeEditOverlayFlow.asStateFlow()
    val lastViewportPadding: StateFlow<PaddingValues?> = viewportPaddingFlow.asStateFlow()
    val handledCommands: StateFlow<List<MapCameraCommand>> = handledCommandsFlow.asStateFlow()

    @Volatile
    private var latestOnMapTap: ((GeoCoordinate) -> Unit)? = null

    @Volatile
    private var latestRouteEditHandlerProvider: () -> RouteEditMapInteractionHandler? = { null }

    fun simulateMapTap(coordinate: GeoCoordinate) {
        latestOnMapTap?.invoke(coordinate)
    }

    fun simulateHandleDrag(handle: RouteTrimHandle, towards: GeoCoordinate) {
        latestRouteEditHandlerProvider()?.onDragHandle(handle, towards)
    }

    fun simulateRoutePull(
        startOnRoute: GeoCoordinate,
        pullTo: GeoCoordinate,
        reachMeters: Double = DEFAULT_PULL_REACH_METERS
    ): Boolean {
        val handler = latestRouteEditHandlerProvider()
        val accepted = handler != null && handler.onBeginPull(startOnRoute, reachMeters)
        if (accepted) {
            handler.onMovePull(pullTo)
            handler.onEndPull(pullTo, movedEnough = true)
        }
        return accepted
    }

    @Composable
    override fun Render(
        cameraState: MapCameraState,
        cameraCommand: MapCameraCommand?,
        overlayState: MapOverlayState,
        viewportPadding: PaddingValues,
        interactions: MapInteractionEvents,
        onCameraCommandHandled: (Long) -> Unit,
        highlightState: StateFlow<MapRouteHighlight?>,
        liveRecordingOverlayState: StateFlow<MapRouteOverlay?>,
        modifier: Modifier,
        walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?>,
        routeEditOverlayState: StateFlow<MapRouteEditOverlay?>,
        routeEditHandlerProvider: () -> RouteEditMapInteractionHandler?
    ) {
        latestOnMapTap = interactions.onMapTap
        latestRouteEditHandlerProvider = routeEditHandlerProvider

        LaunchedEffect(cameraState) {
            cameraStateFlow.value = cameraState
        }
        LaunchedEffect(highlightState) {
            highlightState.collect { highlightStateFlow.value = it }
        }
        LaunchedEffect(liveRecordingOverlayState) {
            liveRecordingOverlayState.collect { liveRecordingOverlayFlow.value = it }
        }
        LaunchedEffect(walkCoverageOverlayState) {
            walkCoverageOverlayState.collect { walkCoverageOverlayFlow.value = it }
        }
        LaunchedEffect(routeEditOverlayState) {
            routeEditOverlayState.collect { routeEditOverlayFlow.value = it }
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

        val editOverlay by routeEditOverlayState.collectAsStateWithLifecycle()
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = "Trail map" }
                .testTag(OpenHikesTestTags.TRAIL_MAP)
        ) {
            if (editOverlay != null) {
                FakeRouteEditHandles(
                    isSelectingStretch = editOverlay?.isSelectingStretch == true,
                    handlerProvider = routeEditHandlerProvider
                )
            }
        }
    }

    @Composable
    private fun FakeRouteEditHandles(
        isSelectingStretch: Boolean,
        handlerProvider: () -> RouteEditMapInteractionHandler?
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FakeHandleNode(
                handle = RouteTrimHandle.START,
                label = if (isSelectingStretch) "Start of stretch" else "Start of route",
                tag = OpenHikesTestTags.ROUTE_TRIM_START_HANDLE,
                handlerProvider = handlerProvider,
                modifier = Modifier.align(Alignment.TopStart)
            )
            FakeHandleNode(
                handle = RouteTrimHandle.END,
                label = if (isSelectingStretch) "End of stretch" else "End of route",
                tag = OpenHikesTestTags.ROUTE_TRIM_END_HANDLE,
                handlerProvider = handlerProvider,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }

    @Composable
    private fun FakeHandleNode(
        handle: RouteTrimHandle,
        label: String,
        tag: String,
        handlerProvider: () -> RouteEditMapInteractionHandler?,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .size(48.dp)
                .semantics {
                    contentDescription = label
                    customActions = listOf(
                        CustomAccessibilityAction("Move forward along route") {
                            handlerProvider()?.onAdjustHandle(handle, 1)
                            true
                        },
                        CustomAccessibilityAction("Move backward along route") {
                            handlerProvider()?.onAdjustHandle(handle, -1)
                            true
                        }
                    )
                }
                .testTag(tag)
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

        is MapCameraCommand.FitBounds -> current.copy(
            center = command.bounds.center,
            zoom = if (command.bounds.isSinglePoint) FIT_POINT_ZOOM else current.zoom
        )

        is MapCameraCommand.ZoomIn -> current.zoomedIn(command.delta)
        is MapCameraCommand.ZoomOut -> current.zoomedOut(command.delta)
        is MapCameraCommand.ResetNorth -> current.resetNorth()
    }

    private companion object {
        const val FIT_POINT_ZOOM = 14.0
        const val DEFAULT_PULL_REACH_METERS = 500.0
    }
}
