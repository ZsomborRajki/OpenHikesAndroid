package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineRouteSaving
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteSegment
import app.openhikes.core.model.TrailDifficultyBreakdown
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailSurfaceBreakdown

/**
 * Tabs inside [HikeDetailScreen] separating route metadata from recorded walk history.
 */
enum class HikeDetailTab {
    DETAILS,
    HISTORY
}

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
 * Coarse UI state for [HikeDetailScreen]. High-frequency chart scrub distance and per-fix
 * trail-walk progress live in separate state holders so updates never recompose the scroll tree.
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
    val selectedTab: HikeDetailTab = HikeDetailTab.DETAILS,
    val walks: List<HikeWalk> = emptyList(),
    val walkSession: TrailWalkSessionState = TrailWalkSessionState(),
    val isRecordingActive: Boolean = false,
    val showEndWalkDialog: Boolean = false,
    val showEndWalkRefusedDialog: Boolean = false,
    val requestLocationPermission: Boolean = false,
    val isLoaded: Boolean = false,
    val isRenaming: Boolean = false,
    val titleDraft: String = "",
    val showDeleteDialog: Boolean = false,
    val actionError: HikeDetailError? = null,
    val pendingExport: ExportedGpx? = null,
    val pendingArchiveFileName: String? = null,
    val exportSuccess: Boolean = false,
    val orderedPhotos: List<HikePhoto> = emptyList(),
    val anchoredPhotoCount: Int = 0,
    val canMatchPhotos: Boolean = false,
    val pendingOpenPhotoId: String? = null,
    val placeRows: List<TrailPlaceRow> = emptyList(),
    val showsPlacePins: Boolean = true,
    val canSearchPlaces: Boolean = false,
    val pendingOpenPlaceId: String? = null,
    val pendingAddPlaceCoordinate: GeoCoordinate? = null,
    val surfaceBreakdown: TrailSurfaceBreakdown? = hike?.surfaceBreakdown,
    val difficultyBreakdown: TrailDifficultyBreakdown? = hike?.difficultyBreakdown,
    val isReadingElevation: Boolean = false
) {
    val isWalkingThisHike: Boolean
        get() = walkSession.walkedHikeId == hikeId && walkSession.phase != null

    val isWalkingOtherHike: Boolean
        get() = walkSession.walkedHikeId != null && walkSession.walkedHikeId != hikeId && walkSession.phase != null
}

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

sealed interface HikeWalkAction {
    data object ToggleWalk : HikeWalkAction
    data class PermissionResult(val grantedFine: Boolean, val grantedCoarse: Boolean) : HikeWalkAction
    data object RequestEndWalk : HikeWalkAction
    data object ConfirmEndWalk : HikeWalkAction
    data object CancelEndWalk : HikeWalkAction
    data object DismissEndRefused : HikeWalkAction
    data class SetAutoFollow(val enabled: Boolean) : HikeWalkAction
    data class SelectTab(val tab: HikeDetailTab) : HikeWalkAction
    data class OpenWalkSummary(val walkId: String) : HikeWalkAction
}

sealed interface HikePlaceAction {
    data class OpenPlace(val placeId: String) : HikePlaceAction
    data class SetShowPlacePins(val enabled: Boolean) : HikePlaceAction
    data object OpenPlacesAround : HikePlaceAction
}

sealed interface HikePhotoAction {
    data class OpenPhoto(val photoId: String) : HikePhotoAction
    data object OpenPhotoDiscovery : HikePhotoAction
    data object ShareArchive : HikePhotoAction
}
