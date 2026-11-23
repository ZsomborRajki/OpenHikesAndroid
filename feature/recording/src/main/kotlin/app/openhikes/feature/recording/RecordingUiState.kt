package app.openhikes.feature.recording

import androidx.compose.runtime.Immutable
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingStopStep

/**
 * Coarse UI state for live recording chrome and dialogs.
 *
 * High-frequency per-fix [app.openhikes.core.model.RecordingStatsSnapshot] and 1 Hz duration
 * reads are isolated outside this state class so leaf composables recompose without redrawing
 * the entire recording sheet.
 */
@Immutable
data class RecordingUiState(
    val session: RecordingSessionState = RecordingSessionState(),
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val keepScreenAwake: Boolean = false,
    val isSimulatedLocationEnabled: Boolean = false,
    val stopDialogStep: RecordingStopStep? = null,
    val offeredTitle: String = "",
    val stopNameDraft: String = "",
    val showDiscardDialog: Boolean = false,
    val savedHikeId: HikeId? = null,
    val discardCompleted: Boolean = false,
    val requestLocationPermission: Boolean = false
)

/**
 * User actions dispatched from [RecordingScreen] into [RecordingViewModel].
 */
sealed interface RecordingAction {
    data object Start : RecordingAction
    data class PermissionResult(val grantedFine: Boolean) : RecordingAction
    data object Pause : RecordingAction
    data object Resume : RecordingAction
    data object Stop : RecordingAction
    data class StopNameChanged(val value: String) : RecordingAction
    data object ConfirmStopSave : RecordingAction
    data object ConfirmStopDiscard : RecordingAction
    data object DismissStopDialog : RecordingAction
    data object RequestDiscard : RecordingAction
    data object ConfirmDiscard : RecordingAction
    data object DismissDiscardDialog : RecordingAction
    data object RetrySave : RecordingAction
    data object TryAgain : RecordingAction
    data object DismissFailure : RecordingAction
    data object DismissRecoveryNotice : RecordingAction
}
