package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineRouteSaving
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteSegment

/**
 * Offline country readiness for the currently opened hike on the OpenHikes vector map.
 */
@Immutable
data class HikeOfflineCoverageState(
    val isOpenHikesMap: Boolean = true,
    val crossed: List<OfflineCountry> = emptyList(),
    val missing: List<OfflineCountry> = emptyList(),
    val activeDownload: OfflineCountryDownload? = null,
    val saving: OfflineRouteSaving = OfflineRouteSaving.UNSAVED,
    val includesDetail: Boolean = true,
    val showDialog: Boolean = false
) {
    val canOpenDialog: Boolean
        get() = activeDownload != null || missing.isNotEmpty()
}

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
    val routeSegments: List<RouteSegment> = emptyList(),
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val offlineCoverage: HikeOfflineCoverageState = HikeOfflineCoverageState(),
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

sealed interface HikeOfflineAction {
    data object OpenDialog : HikeOfflineAction
    data object DismissDialog : HikeOfflineAction
    data class Download(val countryId: String) : HikeOfflineAction
    data class Retry(val countryId: String) : HikeOfflineAction
    data class Cancel(val countryId: String) : HikeOfflineAction
}
