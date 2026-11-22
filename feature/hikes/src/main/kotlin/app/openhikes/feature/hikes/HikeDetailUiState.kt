package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.RouteProfile

/**
 * Coarse UI state for [HikeDetailScreen]. High-frequency chart scrub distance lives in a separate
 * state holder so scrubbing never recomposes the detail scroll tree.
 */
@Immutable
data class HikeDetailUiState(
    val hikeId: HikeId,
    val hike: Hike? = null,
    val profile: RouteProfile? = null,
    val statistics: HikeRouteStatistics? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val isLoaded: Boolean = false,
    val isRenaming: Boolean = false,
    val titleDraft: String = "",
    val showDeleteDialog: Boolean = false,
    val actionError: HikeDetailError? = null,
    val pendingExport: ExportedGpx? = null,
    val exportSuccess: Boolean = false
)

enum class HikeDetailError {
    RENAME_FAILED,
    DELETE_FAILED,
    EXPORT_FAILED
}
