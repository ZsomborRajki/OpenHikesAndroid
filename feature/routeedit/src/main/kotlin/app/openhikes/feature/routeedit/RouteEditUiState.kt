package app.openhikes.feature.routeedit

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteTint

/**
 * Coarse UI state for Edit Route controls, cards, menus, and dialogs.
 *
 * Drag-rate handle positions live in [RouteTrimRangeState] so moving a handle only recomposes
 * the trimmer handles and figures strip rather than the entire screen.
 */
@Immutable
data class RouteEditUiState(
    val hikeId: HikeId,
    val isLoading: Boolean = true,
    val tintHex: String = RouteTint.DEFAULT_HEX,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val profile: RouteProfile? = null,
    val lineRevision: Int = 0,
    val hasOriginal: Boolean = false,
    val isSelectingStretch: Boolean = false,
    val hasSteps: Boolean = false,
    val canUndo: Boolean = false,
    val canMakeSteps: Boolean = true,
    val isRouting: Boolean = false,
    val isSaving: Boolean = false,
    val isLoop: Boolean = false,
    val canStartLoopAtCut: Boolean = false,
    val keptEndsMeet: Boolean = false,
    val picksWholeLine: Boolean = false,
    val hasChanges: Boolean = false,
    val leadingCarriedOfferMeters: Double? = null,
    val trailingCarriedOfferMeters: Double? = null,
    val showRevertDialog: Boolean = false,
    val refusal: RouteEditRefusal? = null
) {
    val canSave: Boolean
        get() = hasChanges && !isSaving && !isRouting && canMakeSteps
}

/**
 * High-frequency trimmer handle positions and live figures read only by leaf composables.
 */
@Immutable
data class RouteTrimRangeState(
    val startMeters: Double = 0.0,
    val endMeters: Double = 0.0,
    val totalMeters: Double = 0.0,
    val displayedDistanceMeters: Double = 0.0,
    val removedMeters: Double = 0.0,
    val keptDurationSeconds: Double? = null
)

/**
 * User intents forwarded from [RouteEditScreen] to [RouteEditViewModel].
 */
sealed interface RouteEditAction {
    data class MoveHandle(val isStart: Boolean, val meters: Double) : RouteEditAction

    data class AdjustHandle(val isStart: Boolean, val direction: Int) : RouteEditAction

    data object RemoveCarriedStart : RouteEditAction

    data object RemoveCarriedEnd : RouteEditAction

    data object BeginStretch : RouteEditAction

    data object EndStretch : RouteEditAction

    data object FollowTrails : RouteEditAction

    data object StraightenStretch : RouteEditAction

    data object RemoveStretch : RouteEditAction

    data object ReverseRoute : RouteEditAction

    data object OutAndBack : RouteEditAction

    data object CloseLoop : RouteEditAction

    data object StartLoopHere : RouteEditAction

    data object UndoStep : RouteEditAction

    data object RequestRevert : RouteEditAction

    data object DismissRevert : RouteEditAction

    data object DismissRefusal : RouteEditAction
}
