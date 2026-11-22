package app.openhikes.core.map

import app.openhikes.core.model.HikeId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overlay, selection, and camera claim owned by a route entry on the Navigation 3 back stack.
 */
data class MapClaim(
    val routeKey: Any,
    val depth: Int,
    val overlayOverride: MapOverlayState? = null,
    val cameraCommand: MapCameraCommand? = null,
    val selectedHikeId: HikeId? = null
)

/**
 * Arbitrates map camera, route selection, and overlay ownership so the deepest active
 * back-stack entry always owns the map, independent of composition entry/exit timing.
 */
@Singleton
class MapClaimCoordinator @Inject constructor() {
    private val lock = Any()
    private val commandIdGenerator = AtomicLong(0L)
    private val claimsByRoute = LinkedHashMap<Any, MapClaim>()
    private val lastKnownDepthByKey = HashMap<Any, Int>()
    private val activeClaimState = MutableStateFlow<MapClaim?>(null)
    private val pendingCameraCommandState = MutableStateFlow<MapCameraCommand?>(null)

    val activeClaim: StateFlow<MapClaim?> = activeClaimState.asStateFlow()
    val pendingCameraCommand: StateFlow<MapCameraCommand?> = pendingCameraCommandState.asStateFlow()

    fun nextCommandId(): Long = commandIdGenerator.incrementAndGet()

    fun registerClaim(claim: MapClaim) {
        synchronized(lock) {
            val resolvedDepth = lastKnownDepthByKey[claim.routeKey] ?: claim.depth
            claimsByRoute[claim.routeKey] = claim.copy(depth = resolvedDepth)
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
     * Enqueues a one-shot [command] only when [routeKey] is the current deepest map owner
     * (or equals [ROOT_CAMERA_KEY]).
     */
    fun requestCameraCommand(routeKey: Any, command: MapCameraCommand) {
        synchronized(lock) {
            val active = activeClaimState.value
            val isOwner = routeKey == ROOT_CAMERA_KEY || active?.routeKey == routeKey
            if (isOwner) {
                pendingCameraCommandState.value = command
            }
        }
    }

    fun consumeCameraCommand(commandId: Long) {
        synchronized(lock) {
            if (pendingCameraCommandState.value?.id == commandId) {
                pendingCameraCommandState.value = null
            }
        }
    }

    /**
     * Synchronizes claim depths and membership against [activeKeysInOrder] (bottom to top),
     * atomically removing claims whose routes left the back stack.
     */
    fun syncBackStack(activeKeysInOrder: List<Any>) {
        synchronized(lock) {
            lastKnownDepthByKey.clear()
            activeKeysInOrder.forEachIndexed { index, key ->
                lastKnownDepthByKey[key] = index
            }
            val iterator = claimsByRoute.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val newDepth = lastKnownDepthByKey[entry.key]
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

    companion object {
        const val ROOT_CAMERA_KEY = "openhikes-root-camera"
    }
}
