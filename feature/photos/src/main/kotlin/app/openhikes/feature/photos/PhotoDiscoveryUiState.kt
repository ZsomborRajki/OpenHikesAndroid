package app.openhikes.feature.photos

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.LibraryPhotoMatch

/**
 * State machine phases for *Find Photos of This Hike* matching iOS `PhotoDiscoveryController.Phase`.
 */
sealed interface PhotoDiscoveryPhase {
    data object Searching : PhotoDiscoveryPhase

    data object Unsupported : PhotoDiscoveryPhase

    data object ReadFailed : PhotoDiscoveryPhase

    data object AccessDenied : PhotoDiscoveryPhase

    data object AccessRestricted : PhotoDiscoveryPhase

    data class Empty(val isLimitedAccess: Boolean) : PhotoDiscoveryPhase

    data class Results(
        val matches: List<LibraryPhotoMatch>,
        val selectedIds: Set<String>,
        val importFailed: Boolean = false
    ) : PhotoDiscoveryPhase {
        val selectedCount: Int
            get() = selectedIds.size

        val allSelected: Boolean
            get() = matches.isNotEmpty() && selectedIds.size == matches.size
    }

    data class Importing(val completed: Int, val total: Int) : PhotoDiscoveryPhase
}

/**
 * Immutable UI state for [PhotoDiscoveryScreen].
 */
@Immutable
data class PhotoDiscoveryUiState(
    val phase: PhotoDiscoveryPhase = PhotoDiscoveryPhase.Searching,
    val shouldRequestPermissionsToken: Long = 0L,
    val shouldRequestLimitedMoreToken: Long = 0L,
    val shouldClose: Boolean = false
)

sealed interface PhotoDiscoveryAction {
    data class ToggleSelection(val assetId: String) : PhotoDiscoveryAction

    data object ToggleSelectAll : PhotoDiscoveryAction

    data object AddSelected : PhotoDiscoveryAction

    data object RetrySearch : PhotoDiscoveryAction

    data object SelectMorePhotos : PhotoDiscoveryAction

    data object PermissionResultReceived : PhotoDiscoveryAction

    data object ConsumePermissionRequest : PhotoDiscoveryAction

    data object ConsumeLimitedMoreRequest : PhotoDiscoveryAction
}
