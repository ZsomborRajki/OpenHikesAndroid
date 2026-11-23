package app.openhikes.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingEntryCoordinator
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.navigation.RecordingRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [RecordingRoute] owning coarse recording chrome, stop/discard dialog flows,
 * map-claim camera tracking, and delegation into [RecordingController].
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recordingController: RecordingController,
    recordingEntryCoordinator: RecordingEntryCoordinator,
    userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val launchConfigHolder: LaunchConfigHolder
) : ViewModel() {
    private val dialogState = MutableStateFlow(RecordingDialogState())

    val stats: StateFlow<RecordingStatsSnapshot> = recordingController.stats

    val uiState: StateFlow<RecordingUiState> = combine(
        recordingController.state,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) emit(UserSettings()) else throw exception
        },
        launchConfigHolder.current,
        dialogState
    ) { session, settings, launchConfig, dialog ->
        RecordingUiState(
            session = session,
            distanceUnit = settings.distanceUnit,
            keepScreenAwake = settings.keepScreenAwake,
            isSimulatedLocationEnabled = launchConfig.enableSimulatedLocation,
            stopDialogStep = dialog.stopDialogStep,
            offeredTitle = dialog.offeredTitle,
            stopNameDraft = dialog.stopNameDraft,
            showDiscardDialog = dialog.showDiscardDialog,
            savedHikeId = dialog.savedHikeId,
            discardCompleted = dialog.discardCompleted,
            requestLocationPermission = dialog.requestLocationPermission
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = RecordingUiState(
            session = recordingController.state.value,
            isSimulatedLocationEnabled = launchConfigHolder.current.value.enableSimulatedLocation
        )
    )

    init {
        mapClaimCoordinator.registerClaim(MapClaim(routeKey = RecordingRoute, depth = DEFAULT_CLAIM_DEPTH))
        observeLiveCameraTracking()
        if (recordingEntryCoordinator.consumeAutoStart() && !recordingController.state.value.isActive) {
            dispatchStart()
        }
    }

    override fun onCleared() {
        mapClaimCoordinator.releaseClaim(RecordingRoute)
    }

    fun currentElapsedSeconds(): Double = recordingController.elapsedSeconds()

    fun consumeNavigation() {
        dialogState.update { it.copy(savedHikeId = null, discardCompleted = false) }
    }

    @Suppress("CyclomaticComplexMethod")
    fun onAction(action: RecordingAction) {
        when (action) {
            RecordingAction.Start -> dispatchStart()
            is RecordingAction.PermissionResult -> handlePermissionResult(action.grantedFine)
            RecordingAction.Pause -> viewModelScope.launch { recordingController.pause() }
            RecordingAction.Resume -> viewModelScope.launch { recordingController.resume() }
            RecordingAction.Stop -> handleStopRequested()
            is RecordingAction.StopNameChanged -> dialogState.update { it.copy(stopNameDraft = action.value) }
            RecordingAction.ConfirmStopSave -> confirmStopSave()
            RecordingAction.ConfirmStopDiscard -> confirmDiscard()
            RecordingAction.DismissStopDialog -> dialogState.update { it.copy(stopDialogStep = null) }
            RecordingAction.RequestDiscard -> dialogState.update { it.copy(showDiscardDialog = true) }
            RecordingAction.ConfirmDiscard -> confirmDiscard()
            RecordingAction.DismissDiscardDialog -> dialogState.update { it.copy(showDiscardDialog = false) }
            RecordingAction.RetrySave -> retrySave()
            RecordingAction.TryAgain -> {
                recordingController.dismissFailure()
                dispatchStart()
            }
            RecordingAction.DismissFailure -> recordingController.dismissFailure()
            RecordingAction.DismissRecoveryNotice -> recordingController.dismissRecoveryNotice()
        }
    }

    private fun observeLiveCameraTracking() {
        viewModelScope.launch {
            recordingController.trace
                .map { it.points.lastOrNull()?.coordinate }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { coordinate ->
                    mapClaimCoordinator.requestCameraCommand(
                        routeKey = RecordingRoute,
                        command = MapCameraCommand.MoveTo(
                            id = mapClaimCoordinator.nextCommandId(),
                            center = coordinate,
                            zoom = FOLLOW_ZOOM
                        )
                    )
                }
        }
    }

    private fun dispatchStart() {
        if (launchConfigHolder.current.value.enableSimulatedLocation) {
            viewModelScope.launch { recordingController.start() }
        } else {
            dialogState.update { it.copy(requestLocationPermission = true) }
        }
    }

    private fun handlePermissionResult(grantedFine: Boolean) {
        dialogState.update { it.copy(requestLocationPermission = false) }
        viewModelScope.launch { recordingController.onPermissionResult(grantedFine) }
    }

    private fun handleStopRequested() {
        viewModelScope.launch {
            when (recordingController.settledStopStep()) {
                RecordingStopStep.END_EMPTY -> {
                    recordingController.discard()
                    dialogState.update { it.copy(stopDialogStep = null, discardCompleted = true) }
                }
                RecordingStopStep.CONFIRM_DISCARD -> {
                    dialogState.update { it.copy(stopDialogStep = RecordingStopStep.CONFIRM_DISCARD) }
                }
                RecordingStopStep.ASK_NAME -> {
                    val title = recordingController.suggestedTitle()
                    dialogState.update {
                        it.copy(
                            stopDialogStep = RecordingStopStep.ASK_NAME,
                            offeredTitle = title,
                            stopNameDraft = title
                        )
                    }
                }
                null -> Unit
            }
        }
    }

    private fun confirmStopSave() {
        val current = dialogState.value
        dialogState.update { it.copy(stopDialogStep = null) }
        viewModelScope.launch {
            val saved = recordingController.stop(
                customName = current.stopNameDraft,
                offeredTitle = current.offeredTitle
            )
            saved.onSuccess { hike ->
                dialogState.update { it.copy(savedHikeId = hike.id) }
            }
        }
    }

    private fun confirmDiscard() {
        dialogState.update { it.copy(stopDialogStep = null, showDiscardDialog = false) }
        viewModelScope.launch {
            recordingController.discard()
            dialogState.update { it.copy(discardCompleted = true) }
        }
    }

    private fun retrySave() {
        viewModelScope.launch {
            recordingController.retrySave().onSuccess { hike ->
                dialogState.update { it.copy(savedHikeId = hike.id) }
            }
        }
    }

    private data class RecordingDialogState(
        val stopDialogStep: RecordingStopStep? = null,
        val offeredTitle: String = "",
        val stopNameDraft: String = "",
        val showDiscardDialog: Boolean = false,
        val savedHikeId: HikeId? = null,
        val discardCompleted: Boolean = false,
        val requestLocationPermission: Boolean = false
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_CLAIM_DEPTH = 1
        const val FOLLOW_ZOOM = 15.5
    }
}
