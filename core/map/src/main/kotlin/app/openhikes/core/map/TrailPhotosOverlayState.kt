package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PhotoMapPin
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single rendered photo marker on the live map, grouping one or more photos sharing an exact
 * coordinate in gallery order.
 */
@Immutable
data class MapPhotoPinItem(
    val pin: PhotoMapPin,
    val tintHex: String,
    val spokenLabel: String,
    val subtitle: String? = null,
    val previewAccessibilityLabel: String = spokenLabel
) {
    val id: String
        get() = pin.id

    val coordinate: GeoCoordinate
        get() = pin.coordinate

    val count: Int
        get() = pin.count

    fun contains(photoId: String): Boolean = pin.contains(photoId)
}

/**
 * Map photo pins and open callout selection published by the deepest photo-aware route.
 */
@Immutable
data class MapTrailPhotosOverlay(
    val pins: List<MapPhotoPinItem> = emptyList(),
    val selectedPinId: String? = null,
    val selectionToken: Long = 0L
) {
    val hasVisibleContent: Boolean
        get() = pins.isNotEmpty()

    val selectedPin: MapPhotoPinItem?
        get() = selectedPinId?.let { id -> pins.firstOrNull { it.id == id } }
}

/**
 * Callbacks delivering photo pin taps, callout preview taps, and off-main thumbnail loads back to
 * the owning feature ViewModel.
 */
interface TrailPhotosMapInteractionHandler {
    fun onTapPhotoPin(pinId: String) = Unit

    fun onTapPhotoCallout(photoId: String) = Unit

    fun onDismissPhotoCallout() = Unit

    suspend fun loadThumbnailBytes(photo: HikePhoto): ByteArray? = null
}

/**
 * Per-route registration stored inside [TrailPhotosOverlayState].
 */
data class TrailPhotosClaim(
    val routeKey: Any,
    val overlay: MapTrailPhotosOverlay,
    val handler: TrailPhotosMapInteractionHandler? = null
)

/**
 * Isolated state holder keyed by Nav3 `routeKey` so photo pins and callout selections always
 * belong to the deepest active map owner (hike detail, place detail, photo viewer, or active
 * recording) and withdraw immediately on returning to the library list.
 */
@Singleton
class TrailPhotosOverlayState @Inject constructor() {
    private val lock = Any()
    private val tokenGenerator = AtomicLong(0L)
    private val claimsByRoute = LinkedHashMap<Any, TrailPhotosClaim>()
    private val claimsFlow = MutableStateFlow<Map<Any, TrailPhotosClaim>>(emptyMap())

    val claims: StateFlow<Map<Any, TrailPhotosClaim>> = claimsFlow.asStateFlow()

    fun setClaim(claim: TrailPhotosClaim) {
        synchronized(lock) {
            claimsByRoute[claim.routeKey] = claim
            claimsFlow.value = HashMap(claimsByRoute)
        }
    }

    fun clearClaim(routeKey: Any) {
        synchronized(lock) {
            if (claimsByRoute.remove(routeKey) != null) {
                claimsFlow.value = HashMap(claimsByRoute)
            }
        }
    }

    fun claimFor(routeKey: Any?): TrailPhotosClaim? = if (routeKey == null) {
        null
    } else {
        synchronized(lock) { claimsByRoute[routeKey] }
    }

    fun selectPhoto(routeKey: Any, photoId: String?) {
        synchronized(lock) {
            val current = claimsByRoute[routeKey] ?: return
            val targetPinId = photoId?.let { id ->
                current.overlay.pins.firstOrNull { it.contains(id) }?.id
            }
            val updated = current.copy(
                overlay = current.overlay.copy(
                    selectedPinId = targetPinId,
                    selectionToken = tokenGenerator.incrementAndGet()
                )
            )
            claimsByRoute[routeKey] = updated
            claimsFlow.value = HashMap(claimsByRoute)
        }
    }

    companion object {
        private const val PHOTO_SPOT_HALF_SPAN_METERS = 250.0
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        private const val MIN_COS_LAT = 0.05
        private const val MAX_LAT = 85.0
        private const val MIN_LON = -180.0
        private const val MAX_LON = 180.0
        private const val RAD_PER_DEG = PI / 180.0

        /**
         * Builds a ~500 m × 500 m bounding box centered on [coordinate] matching iOS's
         * `MapController.showPhotoSpot` camera framing.
         */
        fun photoSpotBounds(coordinate: GeoCoordinate): GeoBoundingBox {
            val latDelta = PHOTO_SPOT_HALF_SPAN_METERS / METERS_PER_LATITUDE_DEGREE
            val cosLat = cos(coordinate.latitude * RAD_PER_DEG).coerceAtLeast(MIN_COS_LAT)
            val lonDelta = PHOTO_SPOT_HALF_SPAN_METERS / (METERS_PER_LATITUDE_DEGREE * cosLat)
            val south = (coordinate.latitude - latDelta).coerceIn(-MAX_LAT, MAX_LAT)
            val north = (coordinate.latitude + latDelta).coerceIn(south, MAX_LAT)
            val west = (coordinate.longitude - lonDelta).coerceIn(MIN_LON, MAX_LON)
            val east = (coordinate.longitude + lonDelta).coerceIn(west, MAX_LON)
            return GeoBoundingBox(
                southLat = south,
                westLon = west,
                northLat = north,
                eastLon = east
            )
        }
    }
}
