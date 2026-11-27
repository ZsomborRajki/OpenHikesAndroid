package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteShadeStretch
import app.openhikes.core.model.TrailDraftDroppedPin
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Selectable alternative path drawn beneath a Trail Maker leg.
 */
@Immutable
data class MapTrailDraftAlternative(
    val legIndex: Int,
    val alternativeIndex: Int,
    val coordinates: List<GeoCoordinate>,
    val midpoint: GeoCoordinate,
    val formattedTime: String,
    val spokenDescription: String
)

/**
 * One leg rendered by the Trail Maker overlay.
 */
@Immutable
data class MapTrailDraftLeg(
    val id: String,
    val ends: TrailLegEnds,
    val coordinates: List<GeoCoordinate>,
    val snap: TrailLegSnap,
    val alternatives: List<MapTrailDraftAlternative> = emptyList()
)

/**
 * One numbered/labeled waypoint pin rendered by the Trail Maker overlay.
 */
@Immutable
data class MapTrailDraftPin(
    val id: String,
    val index: Int,
    val coordinate: GeoCoordinate,
    val role: TrailStopRole,
    val label: String,
    val badgeText: String
)

/**
 * Total trip duration pill rendered along the longest leg when no leg is routing.
 */
@Immutable
data class MapTrailDraftTimeBubble(val coordinate: GeoCoordinate, val label: String, val spokenDescription: String)

/**
 * Live map rendering snapshot for an active Trail Maker session.
 */
@Immutable
data class MapTrailDraftOverlay(
    val legs: List<MapTrailDraftLeg> = emptyList(),
    val pins: List<MapTrailDraftPin> = emptyList(),
    val droppedPin: TrailDraftDroppedPin? = null,
    val routeTimeBubble: MapTrailDraftTimeBubble? = null,
    val snapsToPaths: Boolean = true,
    val draggedPinIndex: Int? = null,
    val draggedCoordinate: GeoCoordinate? = null,
    val shadedStretches: List<RouteShadeStretch> = emptyList()
)

/**
 * Callbacks bridging map long-presses, pin drags, pin taps, and alternative taps into Trail Maker.
 */
interface TrailDraftMapInteractionHandler {
    fun onDropPin(coordinate: GeoCoordinate, legEnds: TrailLegEnds?)

    fun onTapStopPin(waypointId: String)

    fun onTapDroppedPin()

    fun onChooseAlternative(legIndex: Int, alternativeIndex: Int)

    fun onBeginPinDrag(index: Int): Boolean

    fun onMovePinDrag(coordinate: GeoCoordinate)

    fun onEndPinDrag()

    fun onCancelPinDrag()
}

/**
 * Isolated state holder for active Trail Maker map geometry and drags so drag-rate updates
 * only redraw map overlays without recomposing root shell chrome.
 */
@Singleton
class TrailDraftOverlayState @Inject constructor() {
    private val overlayFlow = MutableStateFlow<MapTrailDraftOverlay?>(null)

    @Volatile
    var interactionHandler: TrailDraftMapInteractionHandler? = null
        private set

    val overlay: StateFlow<MapTrailDraftOverlay?> = overlayFlow.asStateFlow()

    fun show(overlay: MapTrailDraftOverlay) {
        overlayFlow.value = overlay
    }

    fun bindHandler(handler: TrailDraftMapInteractionHandler) {
        interactionHandler = handler
    }

    fun clearOverlay() {
        overlayFlow.value = null
    }

    fun clear() {
        overlayFlow.value = null
        interactionHandler = null
    }
}
