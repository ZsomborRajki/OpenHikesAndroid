package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.ui.testing.OpenHikesTestTags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Production [TrailMapRenderer] backed by MapLibre Compose.
 */
@Singleton
internal class MapLibreTrailMapRenderer @Inject constructor(private val launchConfig: LaunchConfig) : TrailMapRenderer {
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
        val currentInteractions by rememberUpdatedState(interactions)
        val baseStyle = resolveBaseStyle(overlayState)
        val mapState = rememberMapState(
            baseStyle = baseStyle,
            initialCameraPosition = cameraState.toMapLibreCameraPosition()
        )

        LaunchedEffect(mapState) {
            mapState.events
                .filterIsInstance<MapEvent.StyleLoaded>()
                .collect { currentInteractions.onStyleReady() }
        }

        LaunchedEffect(mapState) {
            snapshotFlow { mapState.cameraPosition.toMapCameraState() }
                .distinctUntilChanged()
                .collect { currentInteractions.onCameraChanged(it) }
        }

        LaunchedEffect(cameraCommand?.id) {
            val command = cameraCommand ?: return@LaunchedEffect
            mapState.animateCamera(
                update = command.toCameraUpdate(mapState.cameraPosition),
                animation = CameraAnimation.Ease.Standard
            )
            onCameraCommandHandled(command.id)
        }

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

    private fun resolveBaseStyle(overlayState: MapOverlayState): BaseStyle {
        if (launchConfig.simulatesOffline) return BaseStyle.Empty
        return when (overlayState.mapStyleId) {
            else -> BaseStyle.Uri(DEFAULT_FREE_STYLE_URL)
        }
    }

    private companion object {
        const val DEFAULT_FREE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }
}

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

    is MapCameraCommand.ZoomIn -> CameraUpdate(
        zoom = (current.zoom + delta).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
    )

    is MapCameraCommand.ZoomOut -> CameraUpdate(
        zoom = (current.zoom - delta).coerceIn(MapCameraState.MIN_ZOOM, MapCameraState.MAX_ZOOM)
    )

    is MapCameraCommand.ResetNorth -> CameraUpdate(bearing = 0.0, pitch = 0.0)
}
