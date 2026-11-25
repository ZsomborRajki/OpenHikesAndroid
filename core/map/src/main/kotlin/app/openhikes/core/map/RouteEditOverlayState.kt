package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTrimHandle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live map rendering snapshot for an active Edit Route visit: kept/paused segments, dimmed
 * cut-off ends, dashed orange picked-stretch or pull rubber-band, and draggable handle positions.
 */
@Immutable
data class MapRouteEditOverlay(
    val hikeId: HikeId,
    val tintHex: String,
    val borderHex: String = RouteBorder.NONE_HEX,
    val widthDp: Float,
    val linePattern: RouteLinePattern = RouteLinePattern.Default,
    val keptWalkedSegments: List<List<GeoCoordinate>>,
    val keptPausedSegments: List<List<GeoCoordinate>> = emptyList(),
    val cutLeadingSegments: List<List<GeoCoordinate>> = emptyList(),
    val cutTrailingSegments: List<List<GeoCoordinate>> = emptyList(),
    val pickedStretchSegments: List<List<GeoCoordinate>> = emptyList(),
    val pullCoverSegments: List<List<GeoCoordinate>> = emptyList(),
    val pullBandCoordinates: List<GeoCoordinate> = emptyList(),
    val startHandle: GeoCoordinate,
    val endHandle: GeoCoordinate,
    val isSelectingStretch: Boolean = false
)

/**
 * Callbacks bridging map handle drags, TalkBack handle adjustments, and long-press line pulls
 * back into the owning Edit Route session.
 */
interface RouteEditMapInteractionHandler {
    fun onDragHandle(handle: RouteTrimHandle, towards: GeoCoordinate)

    fun onAdjustHandle(handle: RouteTrimHandle, direction: Int)

    fun onBeginPull(coordinate: GeoCoordinate, reachMeters: Double): Boolean

    fun onMovePull(coordinate: GeoCoordinate)

    fun onEndPull(coordinate: GeoCoordinate, movedEnough: Boolean)

    fun onCancelPull()
}

/**
 * Isolated state holder for active route-editing map geometry and handle drags so drag-rate updates
 * only redraw map overlay layers and handles without recomposing root shell chrome.
 */
@Singleton
class RouteEditOverlayState @Inject constructor() {
    private val overlayFlow = MutableStateFlow<MapRouteEditOverlay?>(null)

    @Volatile
    private var handlerOwner: HikeId? = null

    @Volatile
    var interactionHandler: RouteEditMapInteractionHandler? = null
        private set

    val overlay: StateFlow<MapRouteEditOverlay?> = overlayFlow.asStateFlow()

    fun show(overlay: MapRouteEditOverlay) {
        overlayFlow.value = overlay
    }

    fun bindHandler(hikeId: HikeId, handler: RouteEditMapInteractionHandler) {
        handlerOwner = hikeId
        interactionHandler = handler
    }

    fun clearIfHike(hikeId: HikeId) {
        if (overlayFlow.value?.hikeId == hikeId) {
            overlayFlow.value = null
        }
        if (handlerOwner == hikeId) {
            handlerOwner = null
            interactionHandler = null
        }
    }
}
