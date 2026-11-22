package app.openhikes.core.map

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overlay and camera claim owned by a route entry on the Navigation 3 back stack.
 */
data class MapClaim(
    val routeKey: Any,
    val depth: Int,
    val overlayOverride: MapOverlayState? = null,
    val cameraCommand: MapCameraCommand? = null
)

/**
 * Arbitrates map camera/overlay ownership so the deepest active back-stack entry
 * always owns the map, independent of composition entry/exit timing.
 */
@Singleton
class MapClaimCoordinator @Inject constructor() {
    private val lock = Any()
    private val claimsByRoute = LinkedHashMap<Any, MapClaim>()
    private val activeClaimState = MutableStateFlow<MapClaim?>(null)

    val activeClaim: StateFlow<MapClaim?> = activeClaimState.asStateFlow()

    fun registerClaim(claim: MapClaim) {
        synchronized(lock) {
            claimsByRoute[claim.routeKey] = claim
            recomputeLocked()
        }
    }

    fun releaseClaim(routeKey: Any) {
        synchronized(lock) {
            if (claimsByRoute.remove(routeKey) != null) {
                recomputeLocked()
            }
        }
    }

    /**
     * Synchronizes claim depths and membership against [activeKeysInOrder] (bottom to top),
     * atomically removing claims whose routes left the back stack.
     */
    fun syncBackStack(activeKeysInOrder: List<Any>) {
        synchronized(lock) {
            val depthByKey = HashMap<Any, Int>(activeKeysInOrder.size)
            activeKeysInOrder.forEachIndexed { index, key ->
                depthByKey[key] = index
            }
            val iterator = claimsByRoute.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val newDepth = depthByKey[entry.key]
                if (newDepth == null) {
                    iterator.remove()
                } else if (entry.value.depth != newDepth) {
                    entry.setValue(entry.value.copy(depth = newDepth))
                }
            }
            recomputeLocked()
        }
    }

    private fun recomputeLocked() {
        var winner: MapClaim? = null
        for (claim in claimsByRoute.values) {
            val current = winner
            if (current == null || claim.depth >= current.depth) {
                winner = claim
            }
        }
        activeClaimState.value = winner
    }
}
