package app.openhikes.feature.photos

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PhotoUnavailableReason
import java.io.File

/**
 * Load status for a single page inside [HikePhotoViewerScreen].
 */
sealed interface PhotoPageContent {
    data object Loading : PhotoPageContent

    data class Ready(val bytes: ByteArray) : PhotoPageContent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Ready && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Unavailable(val reason: PhotoUnavailableReason) : PhotoPageContent
}

/**
 * Immutable UI state for the full-screen swipeable photograph viewer.
 */
@Immutable
data class HikePhotoViewerUiState(
    val hikeTitle: String = "",
    val photos: List<HikePhoto> = emptyList(),
    val currentPhotoId: String? = null,
    val isMapRevealed: Boolean = false,
    val shareablePhotoIds: Set<String> = emptySet(),
    val showDeleteDialog: Boolean = false,
    val pendingRevealMapToken: Long = 0L,
    val pendingExpandFullHeightToken: Long = 0L,
    val pendingShareFile: File? = null,
    val errorMessage: PhotoViewerError? = null,
    val shouldClose: Boolean = false
) {
    val currentIndex: Int
        get() = currentPhotoId?.let { id -> photos.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: if (photos.isNotEmpty()) 0 else -1

    val currentPhoto: HikePhoto?
        get() = currentIndex.takeIf { it in photos.indices }?.let { photos[it] }

    val canStepPrevious: Boolean
        get() = currentIndex > 0

    val canStepNext: Boolean
        get() = currentIndex in 0 until photos.lastIndex

    val canShareCurrent: Boolean
        get() = currentPhoto?.id?.let { it in shareablePhotoIds } == true
}

enum class PhotoViewerError {
    SHARE_FAILED,
    DELETE_FAILED
}

sealed interface HikePhotoViewerAction {
    data class SelectPhoto(val photoId: String) : HikePhotoViewerAction

    data object StepPrevious : HikePhotoViewerAction

    data object StepNext : HikePhotoViewerAction

    data object ShowOnMap : HikePhotoViewerAction

    data object TapPhotoSurface : HikePhotoViewerAction

    data object ShareCurrent : HikePhotoViewerAction

    data object RequestDelete : HikePhotoViewerAction

    data object ConfirmDelete : HikePhotoViewerAction

    data object DismissDelete : HikePhotoViewerAction

    data object DismissError : HikePhotoViewerAction

    data object ConsumeRevealMap : HikePhotoViewerAction

    data object ConsumeExpandFullHeight : HikePhotoViewerAction

    data object ConsumeShareFile : HikePhotoViewerAction
}
