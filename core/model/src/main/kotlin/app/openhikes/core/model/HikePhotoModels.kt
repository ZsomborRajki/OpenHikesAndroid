package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * How a library photo was automatically matched to a spot along a hike's trail.
 * `null` on [HikePhoto.matchEvidence] denotes a photo anchored directly by the app
 * (camera shutter fix, elevation graph anchor, or place screen).
 */
@Serializable
enum class PhotoMatchEvidence(val storedId: String) {
    PLACE("place"),
    TIME("time"),
    TIME_AND_PLACE("timeAndPlace"),
    WALK("walk");

    companion object {
        fun fromStoredId(storedId: String?): PhotoMatchEvidence? = entries.firstOrNull { it.storedId == storedId }
    }
}

/**
 * Open license attribution carried by photos copied from Wikimedia Commons.
 */
@Serializable
data class HikePhotoLicense(
    val name: String,
    val url: String? = null,
    val sourceUrl: String,
    val fileSha1: String? = null
)

/**
 * Portable metadata for a single photograph attached to a [Hike] (and optionally filed under a
 * [TrailPoint]). Pixel files live separately on local disk in `HikePhotoStore`.
 */
@Serializable
data class HikePhoto(
    val id: String,
    val hikeId: HikeId,
    val capturedAtEpochMillis: Long,
    val pathExtension: String = DEFAULT_PATH_EXTENSION,
    val coordinate: GeoCoordinate? = null,
    val assetLocalIdentifier: String? = null,
    val matchEvidence: PhotoMatchEvidence? = null,
    val importedFromListingId: String? = null,
    val importedAuthorName: String? = null,
    val sentToCommunityAtEpochMillis: Long? = null,
    val isPlaceOnly: Boolean = false,
    val placeId: String? = null,
    val license: HikePhotoLicense? = null
) {
    val isAnchored: Boolean
        get() = coordinate != null

    val recordsPlaceOnly: Boolean
        get() = isPlaceOnly

    val isOwn: Boolean
        get() = importedFromListingId == null && license == null

    val fileName: String
        get() = "$id.$pathExtension"

    val thumbnailFileName: String
        get() = "$id.$THUMBNAIL_EXTENSION"

    companion object {
        const val DEFAULT_PATH_EXTENSION = "jpeg"
        const val THUMBNAIL_EXTENSION = "jpeg"
    }
}

/**
 * Chronologically ordered view over a hike's photos (`capturedAt` ascending, tie-broken by `id`
 * ascending) with O(1) index lookup so gallery paging and map pin synchronization avoid repeated
 * sorting.
 */
class OrderedHikePhotos private constructor(val photos: List<HikePhoto>) {
    private val indicesById: Map<String, Int> = buildMap(photos.size) {
        photos.forEachIndexed { index, photo -> put(photo.id, index) }
    }

    val isEmpty: Boolean
        get() = photos.isEmpty()

    val size: Int
        get() = photos.size

    val anchoredCount: Int
        get() = photos.count { it.isAnchored }

    val importedAssetIdentifiers: Set<String>
        get() = photos.mapNotNullTo(HashSet()) { it.assetLocalIdentifier?.takeIf(String::isNotBlank) }

    fun indexOf(photoId: String): Int? = indicesById[photoId]

    fun photo(photoId: String): HikePhoto? = indicesById[photoId]?.let { photos[it] }

    fun photosOfPlace(placeId: String): List<HikePhoto> = photos.filter { it.placeId == placeId }

    fun nextIdAfterDeleting(photoId: String): String? {
        val index = indicesById[photoId] ?: return null
        return when {
            photos.size <= 1 -> null
            index + 1 < photos.size -> photos[index + 1].id
            else -> photos[index - 1].id
        }
    }

    companion object {
        val Empty = OrderedHikePhotos(emptyList())

        val PhotoComparator: Comparator<HikePhoto> =
            compareBy<HikePhoto> { it.capturedAtEpochMillis }.thenBy { it.id }

        fun from(photos: List<HikePhoto>): OrderedHikePhotos =
            if (photos.isEmpty()) Empty else OrderedHikePhotos(photos.sortedWith(PhotoComparator))
    }
}

/**
 * Reasons a photo's full-resolution pixels cannot be shown on this device.
 */
enum class PhotoUnavailableReason {
    PLACE_ONLY,
    NOT_ON_THIS_DEVICE,
    NOT_DOWNLOADED,
    GONE_FROM_COMMONS,
    UNREADABLE
}

/**
 * Adaptive layout placement for the gallery attribution pill based on viewport aspect ratio.
 */
enum class PhotoCreditPlacement {
    BESIDE,
    UNDERNEATH;

    companion object {
        private const val WIDE_ASPECT_NUMERATOR = 3f
        private const val WIDE_ASPECT_DENOMINATOR = 2f

        fun resolve(viewportWidth: Float, viewportHeight: Float): PhotoCreditPlacement = if (viewportHeight > 0f &&
            viewportWidth * WIDE_ASPECT_DENOMINATOR > viewportHeight * WIDE_ASPECT_NUMERATOR
        ) {
            BESIDE
        } else {
            UNDERNEATH
        }
    }
}

/**
 * Single map marker grouping one or more hike photos sharing an exact coordinate in gallery order.
 *
 * [photo] is always the first photo in gallery order at [coordinate]; [photoIds] holds all grouped
 * photo IDs so paging to a sibling photo at the same spot keeps this pin selected.
 */
data class PhotoMapPin(val photo: HikePhoto, val photoIds: List<String>, val coordinate: GeoCoordinate) {
    val id: String
        get() = photo.id

    val count: Int
        get() = photoIds.size

    fun contains(photoId: String): Boolean = photoIds.contains(photoId)

    companion object {
        fun pinsFor(orderedPhotos: List<HikePhoto>): List<PhotoMapPin> {
            if (orderedPhotos.isEmpty()) return emptyList()
            val grouped = LinkedHashMap<GeoCoordinate, MutableList<HikePhoto>>()
            for (photo in orderedPhotos) {
                val coordinate = photo.coordinate ?: continue
                grouped.getOrPut(coordinate) { mutableListOf() }.add(photo)
            }
            return grouped.map { (coordinate, group) ->
                PhotoMapPin(
                    photo = group.first(),
                    photoIds = group.map { it.id },
                    coordinate = coordinate
                )
            }
        }
    }
}

/**
 * Result of building a `.zip` archive bundling a hike's GPX track, original photo files, and
 * attribution credits (for Wikimedia Commons copies).
 */
data class ExportedHikeArchive(val fileName: String, val zipBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportedHikeArchive) return false
        return fileName == other.fileName && zipBytes.contentEquals(other.zipBytes)
    }

    override fun hashCode(): Int = 31 * fileName.hashCode() + zipBytes.contentHashCode()
}
