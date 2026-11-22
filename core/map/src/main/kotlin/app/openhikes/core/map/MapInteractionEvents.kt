package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate

/**
 * Callbacks emitted by [TrailMapHost] into the hosting map shell or active route owner.
 */
@Immutable
data class MapInteractionEvents(
    val onMapTap: (GeoCoordinate) -> Unit = {},
    val onCameraChanged: (MapCameraState) -> Unit = {},
    val onStyleReady: () -> Unit = {}
)
