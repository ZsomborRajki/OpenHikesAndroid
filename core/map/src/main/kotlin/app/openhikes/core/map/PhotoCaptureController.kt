package app.openhikes.core.map

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Target hike/place and dynamic trail anchor claimed by the deepest photo-capable screen.
 */
data class PhotoCaptureSubject(
    val routeKey: Any,
    val depth: Int,
    val hikeId: HikeId,
    val placeId: String? = null,
    val showFloatingMapButtons: Boolean = true,
    val anchorProvider: (shutterEpochMillis: Long?) -> GeoCoordinate? = { null }
)

/**
 * Source of a user-initiated photo capture or import action.
 */
enum class PhotoCaptureRequestKind {
    CAMERA,
    LIBRARY
}

/**
 * One-shot capture/picker request routed from any screen or floating map control to the shell's
 * `TakePicture` / `PickMultipleVisualMedia` launcher host.
 */
data class PhotoCaptureRequest(val token: Long, val kind: PhotoCaptureRequestKind, val subject: PhotoCaptureSubject)

/**
 * User-visible alert surfaced when camera permission, capture storage, or library import fails.
 */
enum class PhotoCaptureAlert {
    CAMERA_PERMISSION_DENIED,
    CAMERA_SAVE_FAILED,
    LIBRARY_IMPORT_FAILED
}

/**
 * Arbitrates photo capture/library ownership across the Navigation 3 back stack so the deepest
 * photo-capable route (hike detail, place detail, or active recording) owns the floating camera
 * and library buttons, immediately withdrawing them when returning to the library root.
 */
@Singleton
class PhotoCaptureController @Inject constructor() {
    private val lock = Any()
    private val tokenGenerator = AtomicLong(0L)
    private val subjectsByRoute = LinkedHashMap<Any, PhotoCaptureSubject>()
    private val lastKnownDepthByKey = HashMap<Any, Int>()
    private var topBackStackKey: Any? = null
    private val activeSubjectFlow = MutableStateFlow<PhotoCaptureSubject?>(null)
    private val showFloatingButtonsFlow = MutableStateFlow(false)
    private val pendingRequestFlow = MutableStateFlow<PhotoCaptureRequest?>(null)
    private val alertFlow = MutableStateFlow<PhotoCaptureAlert?>(null)
    private val isBusyFlow = MutableStateFlow(false)
    private val importCancellationEpochFlow = MutableStateFlow(0L)

    val activeSubject: StateFlow<PhotoCaptureSubject?> = activeSubjectFlow.asStateFlow()
    val showFloatingButtons: StateFlow<Boolean> = showFloatingButtonsFlow.asStateFlow()
    val pendingRequest: StateFlow<PhotoCaptureRequest?> = pendingRequestFlow.asStateFlow()
    val alert: StateFlow<PhotoCaptureAlert?> = alertFlow.asStateFlow()
    val isBusy: StateFlow<Boolean> = isBusyFlow.asStateFlow()
    val importCancellationEpoch: StateFlow<Long> = importCancellationEpochFlow.asStateFlow()

    fun registerSubject(subject: PhotoCaptureSubject) {
        synchronized(lock) {
            val resolvedDepth = lastKnownDepthByKey[subject.routeKey] ?: subject.depth
            subjectsByRoute[subject.routeKey] = subject.copy(depth = resolvedDepth)
            recomputeLocked()
        }
    }

    fun releaseSubject(routeKey: Any) {
        synchronized(lock) {
            if (subjectsByRoute.remove(routeKey) != null) {
                recomputeLocked()
            }
        }
    }

    fun syncBackStack(activeKeysInOrder: List<Any>) {
        synchronized(lock) {
            topBackStackKey = activeKeysInOrder.lastOrNull()
            lastKnownDepthByKey.clear()
            activeKeysInOrder.forEachIndexed { index, key ->
                lastKnownDepthByKey[key] = index
            }
            val iterator = subjectsByRoute.entries.iterator()
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
            if (activeKeysInOrder.size <= 1) {
                importCancellationEpochFlow.value += 1L
            }
        }
    }

    fun subjectFor(hikeId: HikeId, placeId: String?): PhotoCaptureSubject? = synchronized(lock) {
        subjectsByRoute.values
            .filter { it.hikeId == hikeId && it.placeId == placeId }
            .maxByOrNull { it.depth }
    }

    fun requestCamera(explicitSubject: PhotoCaptureSubject? = null): Boolean =
        enqueueRequest(PhotoCaptureRequestKind.CAMERA, explicitSubject)

    fun requestLibrary(explicitSubject: PhotoCaptureSubject? = null): Boolean =
        enqueueRequest(PhotoCaptureRequestKind.LIBRARY, explicitSubject)

    fun consumeRequest(token: Long) {
        synchronized(lock) {
            if (pendingRequestFlow.value?.token == token) {
                pendingRequestFlow.value = null
            }
        }
    }

    fun setBusy(busy: Boolean) {
        isBusyFlow.value = busy
    }

    fun showAlert(alert: PhotoCaptureAlert) {
        alertFlow.value = alert
    }

    fun dismissAlert() {
        alertFlow.value = null
    }

    private fun enqueueRequest(kind: PhotoCaptureRequestKind, explicitSubject: PhotoCaptureSubject?): Boolean {
        synchronized(lock) {
            val target = explicitSubject ?: activeSubjectFlow.value ?: return false
            pendingRequestFlow.value = PhotoCaptureRequest(
                token = tokenGenerator.incrementAndGet(),
                kind = kind,
                subject = target
            )
            return true
        }
    }

    private fun recomputeLocked() {
        var winner: PhotoCaptureSubject? = null
        for (subject in subjectsByRoute.values) {
            val current = winner
            if (current == null || subject.depth >= current.depth) {
                winner = subject
            }
        }
        activeSubjectFlow.value = winner
        val topKey = topBackStackKey
        showFloatingButtonsFlow.value = if (topKey != null) {
            subjectsByRoute[topKey]?.showFloatingMapButtons == true
        } else {
            winner?.showFloatingMapButtons == true
        }
    }
}
