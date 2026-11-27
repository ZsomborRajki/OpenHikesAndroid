package app.openhikes.feature.trailmaker

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.TrailDraftRefusal
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailStopSearchResult
import app.openhikes.core.model.TrailTravelMode

/**
 * Which stop slot the stop search sheet was opened to fill or replace.
 */
@Immutable
sealed interface TrailStopSearchTarget {
    val landsOnAnEnd: Boolean

    data class Open(val role: TrailStopRole) : TrailStopSearchTarget {
        override val landsOnAnEnd: Boolean = true
    }

    data class Existing(val waypointId: String, val role: TrailStopRole) : TrailStopSearchTarget {
        override val landsOnAnEnd: Boolean = role != TrailStopRole.Start
    }

    data object NewStop : TrailStopSearchTarget {
        override val landsOnAnEnd: Boolean = true
    }
}

/**
 * Immutable row model rendered inside the Trail Maker stop list.
 */
@Immutable
data class TrailStopRowItem(
    val slotId: String,
    val position: Int,
    val isOpen: Boolean,
    val waypointId: String?,
    val role: TrailStopRole,
    val name: String,
    val distanceMeters: Double,
    val arrivingSnap: TrailLegSnap?,
    val pointNumber: Int?,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

/**
 * Immutable state for the active Stop Search sheet.
 */
@Immutable
data class TrailStopSearchUiState(
    val target: TrailStopSearchTarget,
    val query: String = "",
    val prefill: String = "",
    val results: List<TrailStopSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val didFail: Boolean = false,
    val recents: List<TrailStopRecent> = emptyList(),
    val myLocationAvailable: Boolean = false,
    val myLocationDenied: Boolean = false
)

/**
 * Immutable state for the active Place Card sheet (for a dropped pin or a placed stop).
 */
@Immutable
data class TrailPlaceCardState(
    val isDroppedPin: Boolean,
    val waypointId: String?,
    val role: TrailStopRole?,
    val title: String,
    val nameForStop: String,
    val distanceAlongRouteMeters: Double?,
    val coordinate: GeoCoordinate,
    val coordinatesText: String,
    val address: String?,
    val preferredLegEnds: TrailLegEnds?
)

/**
 * Coarse UI state rendered by [TrailMakerScreen].
 */
@Immutable
data class TrailMakerUiState(
    val travelMode: TrailTravelMode = TrailTravelMode.HIKING,
    val rows: List<TrailStopRowItem> = emptyList(),
    val distanceMeters: Double = 0.0,
    val travelTimeSeconds: Double? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val canBeSaved: Boolean = false,
    val isEmpty: Boolean = true,
    val canSnapToPaths: Boolean = false,
    val snapsToPaths: Boolean = true,
    val routeColoring: RouteColoring = RouteColoring.Default,
    val showsColoringSection: Boolean = false,
    val isRouting: Boolean = false,
    val hasRetryableLegs: Boolean = false,
    val worstLegSnap: TrailLegSnap? = null,
    val isRestoring: Boolean = false,
    val isSaving: Boolean = false,
    val showCloseDialog: Boolean = false,
    val showNameDialog: Boolean = false,
    val namePlaceholder: String = "",
    val nameDraft: String = "",
    val refusal: TrailDraftRefusal? = null,
    val stopSearch: TrailStopSearchUiState? = null,
    val placeCard: TrailPlaceCardState? = null
) {
    val isEditingEnabled: Boolean
        get() = !isRestoring && !isSaving
}

/**
 * User intents emitted from [TrailMakerScreen] and its modal sheets/dialogs.
 */
sealed interface TrailMakerAction {
    data class SelectTravelMode(val mode: TrailTravelMode) : TrailMakerAction

    data class ToggleSnapToPaths(val enabled: Boolean) : TrailMakerAction

    data class SelectRouteColoring(val coloring: RouteColoring) : TrailMakerAction

    data object RetryRefusedLegs : TrailMakerAction

    data class OpenStopSearch(val target: TrailStopSearchTarget) : TrailMakerAction

    data object CloseStopSearch : TrailMakerAction

    data class UpdateSearchQuery(val query: String) : TrailMakerAction

    data class PickSearchResult(val result: TrailStopSearchResult) : TrailMakerAction

    data class PickRecentStop(val recent: TrailStopRecent) : TrailMakerAction

    data object PickMyLocation : TrailMakerAction

    data class RemoveRecentStop(val id: String) : TrailMakerAction

    data class DeleteStop(val waypointId: String) : TrailMakerAction

    data class MoveStop(val waypointId: String, val delta: Int) : TrailMakerAction

    data object ClosePlaceCard : TrailMakerAction

    data object AddStopFromPlaceCard : TrailMakerAction

    data object RemoveDroppedPin : TrailMakerAction

    data class RemoveStopFromPlaceCard(val waypointId: String) : TrailMakerAction

    data object DismissCloseDialog : TrailMakerAction

    data object OpenSaveDialog : TrailMakerAction

    data class UpdateNameDraft(val value: String) : TrailMakerAction

    data object DismissNameDialog : TrailMakerAction

    data object DismissRefusal : TrailMakerAction
}
