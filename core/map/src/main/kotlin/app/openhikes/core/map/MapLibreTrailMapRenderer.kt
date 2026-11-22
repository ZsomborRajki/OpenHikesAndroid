package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.ui.testing.OpenHikesTestTags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.ConnectivityMode
import org.maplibre.compose.resource.MapConnectivity
import org.maplibre.compose.sources.rememberRasterTileSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

internal data class MapStylePlan(val baseStyle: BaseStyle, val hikingRoutesTileUrl: String?)

internal fun resolveMapStylePlan(
    overlayState: MapOverlayState,
    allowRemoteRequests: Boolean,
    hasLoadedRemoteStyle: Boolean
): MapStylePlan {
    if (!allowRemoteRequests && !hasLoadedRemoteStyle) {
        return MapStylePlan(baseStyle = BaseStyle.Empty, hikingRoutesTileUrl = null)
    }
    val baseStyle = when (overlayState.mapStyleId) {
        else -> BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL)
    }
    val hikingTileUrl = if (overlayState.showsHikingRoutes && (allowRemoteRequests || hasLoadedRemoteStyle)) {
        WAYMARKED_HIKING_TILES_URL
    } else {
        null
    }
    return MapStylePlan(baseStyle = baseStyle, hikingRoutesTileUrl = hikingTileUrl)
}

/**
 * Production [TrailMapRenderer] backed by MapLibre Compose.
 */
@Singleton
internal class MapLibreTrailMapRenderer @Inject constructor(private val mapNetworkPolicy: MapNetworkPolicy) :
    TrailMapRenderer {
    @Composable
    override fun Render(
        cameraState: MapCameraState,
        cameraCommand: MapCameraCommand?,
        overlayState: MapOverlayState,
        viewportPadding: PaddingValues,
        interactions: MapInteractionEvents,
        onCameraCommandHandled: (Long) -> Unit,
        highlightState: StateFlow<MapRouteHighlight?>,
        modifier: Modifier
    ) {
        val currentInteractions by rememberUpdatedState(interactions)
        val allowRemoteRequests by mapNetworkPolicy.allowsInteractiveMapRequests.collectAsStateWithLifecycle()
        var hasLoadedRemoteStyle by remember { mutableStateOf(false) }

        LaunchedEffect(allowRemoteRequests) {
            MapConnectivity.mode = if (allowRemoteRequests) {
                ConnectivityMode.Automatic
            } else {
                ConnectivityMode.ForceOffline
            }
        }

        val stylePlan = resolveMapStylePlan(
            overlayState = overlayState,
            allowRemoteRequests = allowRemoteRequests,
            hasLoadedRemoteStyle = hasLoadedRemoteStyle
        )
        val mapState = rememberTrailMapState(
            stylePlan = stylePlan,
            routes = overlayState.routes,
            highlightState = highlightState,
            initialCameraState = cameraState
        )

        BindTrailMapEffects(
            mapState = mapState,
            cameraCommand = cameraCommand,
            allowRemoteRequests = allowRemoteRequests,
            onRemoteStyleLoaded = { hasLoadedRemoteStyle = true },
            onStyleReady = { currentInteractions.onStyleReady() },
            onCameraChanged = { currentInteractions.onCameraChanged(it) },
            onCameraCommandHandled = onCameraCommandHandled
        )

        val mapContentDescription = stringResource(R.string.trail_map_content_description)
        MaplibreMap(
            modifier = modifier
                .fillMaxSize()
                .semantics { contentDescription = mapContentDescription }
                .testTag(OpenHikesTestTags.TRAIL_MAP),
            state = mapState,
            viewportInsets = viewportPadding,
            interactions = createMapInteractions { coordinate ->
                currentInteractions.onMapTap(coordinate)
            },
            overlay = {}
        )
    }
}

@Composable
private fun rememberTrailMapState(
    stylePlan: MapStylePlan,
    routes: List<MapRouteOverlay>,
    highlightState: StateFlow<MapRouteHighlight?>,
    initialCameraState: MapCameraState
) = rememberMapState(
    baseStyle = stylePlan.baseStyle,
    initialCameraPosition = initialCameraState.toMapLibreCameraPosition()
) {
    stylePlan.hikingRoutesTileUrl?.let { tileUrl ->
        val hikingSource = rememberRasterTileSource(
            tiles = listOf(tileUrl),
            tileSize = WAYMARKED_TILE_SIZE
        ) {
            minZoom = WAYMARKED_MIN_ZOOM
            maxZoom = WAYMARKED_MAX_ZOOM
        }
        RasterLayer(
            id = HIKING_ROUTES_LAYER_ID,
            source = hikingSource,
            opacity = const(HIKING_ROUTES_OPACITY)
        )
    }
    RouteOverlayLayers(routes = routes)
    HighlightMarkerLayers(highlightState = highlightState)
}

@Composable
private fun BindTrailMapEffects(
    mapState: MapState,
    cameraCommand: MapCameraCommand?,
    allowRemoteRequests: Boolean,
    onRemoteStyleLoaded: () -> Unit,
    onStyleReady: () -> Unit,
    onCameraChanged: (MapCameraState) -> Unit,
    onCameraCommandHandled: (Long) -> Unit
) {
    val currentAllowRemote by rememberUpdatedState(allowRemoteRequests)
    val currentOnRemoteStyleLoaded by rememberUpdatedState(onRemoteStyleLoaded)
    val currentOnStyleReady by rememberUpdatedState(onStyleReady)
    val currentOnCameraChanged by rememberUpdatedState(onCameraChanged)

    LaunchedEffect(mapState) {
        mapState.events
            .filterIsInstance<MapEvent.StyleLoaded>()
            .collect {
                if (currentAllowRemote) {
                    currentOnRemoteStyleLoaded()
                }
                currentOnStyleReady()
            }
    }

    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition.toMapCameraState() }
            .distinctUntilChanged()
            .collect { currentOnCameraChanged(it) }
    }

    LaunchedEffect(cameraCommand?.id) {
        val command = cameraCommand ?: return@LaunchedEffect
        executeCameraCommand(mapState, command)
        onCameraCommandHandled(command.id)
    }
}

private suspend fun executeCameraCommand(mapState: MapState, command: MapCameraCommand) {
    if (command is MapCameraCommand.FitBounds) {
        if (command.bounds.isSinglePoint) {
            val center = command.bounds.center
            mapState.animateCamera(
                update = CameraUpdate(
                    center = Position(longitude = center.longitude, latitude = center.latitude),
                    zoom = FIT_SINGLE_POINT_ZOOM
                ),
                animation = CameraAnimation.Ease.Standard
            )
        } else {
            mapState.fitCameraToBounds(
                boundingBox = BoundingBox(
                    west = command.bounds.westLon,
                    south = command.bounds.southLat,
                    east = command.bounds.eastLon,
                    north = command.bounds.northLat
                ),
                fit = CameraFit(
                    fitPadding = DpPadding(
                        left = FIT_BOUNDS_PADDING_DP.dp,
                        top = FIT_BOUNDS_PADDING_DP.dp,
                        right = FIT_BOUNDS_PADDING_DP.dp,
                        bottom = FIT_BOUNDS_PADDING_DP.dp
                    )
                )
            )
        }
        return
    }

    mapState.animateCamera(
        update = command.toCameraUpdate(mapState.cameraPosition),
        animation = CameraAnimation.Ease.Standard
    )
}

internal const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
internal const val WAYMARKED_HIKING_TILES_URL = "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png"
private const val HIKING_ROUTES_LAYER_ID = "openhikes-waymarked-hiking-routes"
private const val WAYMARKED_TILE_SIZE = 256
private const val WAYMARKED_MIN_ZOOM = 0
private const val WAYMARKED_MAX_ZOOM = 18
private const val HIKING_ROUTES_OPACITY = 0.85f
private const val FIT_BOUNDS_PADDING_DP = 32
private const val FIT_SINGLE_POINT_ZOOM = 14.0

private const val FULL_CIRCLE_DEGREES = 360.0
private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0

private fun createMapInteractions(onMapTap: (GeoCoordinate) -> Unit): MapInteractions = MapInteractions {
    callbacks {
        click {
            onEvent { event ->
                event.position?.toGeoCoordinateOrNull()?.let(onMapTap)
                ClickResult.Pass
            }
        }
    }
}

private fun MapCameraState.toMapLibreCameraPosition(): CameraPosition = CameraPosition(
    center = Position(longitude = center.longitude, latitude = center.latitude),
    zoom = zoom,
    bearing = bearing,
    pitch = pitch
)

private fun CameraPosition.toMapCameraState(): MapCameraState = MapCameraState(
    center = center.toGeoCoordinateOrNull() ?: MapCameraState.DEFAULT_CENTER,
    zoom = zoom.coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM),
    bearing = bearing,
    pitch = pitch
)

private fun Position.toGeoCoordinateOrNull(): GeoCoordinate? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    val clampedLat = latitude.coerceIn(MIN_LATITUDE, MAX_LATITUDE)
    val wrappedLon = normalizeLongitude(longitude)
    return GeoCoordinate(latitude = clampedLat, longitude = wrappedLon)
}

private fun normalizeLongitude(longitude: Double): Double {
    var lon = ((longitude - MIN_LONGITUDE) % FULL_CIRCLE_DEGREES)
    if (lon < 0.0) lon += FULL_CIRCLE_DEGREES
    lon += MIN_LONGITUDE
    return lon.coerceIn(MIN_LONGITUDE, MAX_LONGITUDE)
}

private fun MapCameraCommand.toCameraUpdate(current: CameraPosition): CameraUpdate = when (this) {
    is MapCameraCommand.MoveTo -> CameraUpdate(
        center = Position(longitude = center.longitude, latitude = center.latitude),
        zoom = (zoom ?: current.zoom).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM),
        bearing = bearing ?: current.bearing,
        pitch = pitch ?: current.pitch
    )

    is MapCameraCommand.FitBounds -> CameraUpdate(
        center = Position(longitude = bounds.center.longitude, latitude = bounds.center.latitude)
    )

    is MapCameraCommand.ZoomIn -> CameraUpdate(
        zoom = (current.zoom + delta).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
    )

    is MapCameraCommand.ZoomOut -> CameraUpdate(
        zoom = (current.zoom - delta).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
    )

    is MapCameraCommand.ResetNorth -> CameraUpdate(bearing = 0.0, pitch = 0.0)
}
