package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Highlighted covered stretches of a finished trail walk drawn over its route when the user
 * selects "Show on Map" from a walk summary.
 */
@Immutable
data class MapWalkCoverageOverlay(val hikeId: HikeId, val segments: List<List<GeoCoordinate>>, val tintHex: String)

/**
 * Isolated state holder for highlighted walk coverage stretches so updating map highlights
 * never recomposes root shell chrome.
 */
@Singleton
class WalkOverlayState @Inject constructor() {
    private val coverageOverlayFlow = MutableStateFlow<MapWalkCoverageOverlay?>(null)

    val coverageOverlay: StateFlow<MapWalkCoverageOverlay?> = coverageOverlayFlow.asStateFlow()

    fun show(overlay: MapWalkCoverageOverlay) {
        coverageOverlayFlow.value = overlay
    }

    fun clear() {
        coverageOverlayFlow.value = null
    }

    fun clearIfHike(hikeId: HikeId) {
        if (coverageOverlayFlow.value?.hikeId == hikeId) {
            coverageOverlayFlow.value = null
        }
    }
}
