package app.openhikes.core.map

import app.openhikes.core.model.HikeId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Isolated high-frequency holder for the active map route scrub/follow highlight marker so
 * scrubbing an elevation chart never recomposes root shell or detail layouts.
 */
@Singleton
class RouteHighlightState @Inject constructor() {
    private val highlightState = MutableStateFlow<MapRouteHighlight?>(null)

    val highlight: StateFlow<MapRouteHighlight?> = highlightState.asStateFlow()

    fun move(highlight: MapRouteHighlight?) {
        highlightState.value = highlight
    }

    fun clear() {
        highlightState.value = null
    }

    fun clearIfHike(hikeId: HikeId) {
        if (highlightState.value?.hikeId == hikeId) {
            highlightState.value = null
        }
    }
}
