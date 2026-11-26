package app.openhikes.feature.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingEntryCoordinator
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPlacePin
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.TrailPlacesClaim
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.navigation.RecordingRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [RecordingRoute] owning coarse recording chrome, stop/discard dialog flows,
 * map-claim camera tracking, and delegation into [RecordingController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList")
@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val recordingController: RecordingController,
    recordingEntryCoordinator: RecordingEntryCoordinator,
    hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val trailPlacesOverlayState: TrailPlacesOverlayState,
    private val launchConfigHolder: LaunchConfigHolder
) : ViewModel() {
    private val dialogState = MutableStateFlow(RecordingDialogState())
    private val pendingOpenPlaceIdState = MutableStateFlow<String?>(null)
    private var pendingSaveSessionId: String? = null

    private val placesInteractionHandler = object : TrailPlacesMapInteractionHandler {
        override fun onTapPlacePin(placeId: String) {
            pendingOpenPlaceIdState.value = placeId
        }
    }

    private val activePlacesFlow = recordingController.state
        .map { session -> session.sessionId?.takeIf { session.isActive } }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(emptyList())
            } else {
                hikeRepository.observeHike(HikeId(sessionId)).map { it?.places.orEmpty() }
            }
        }

    val stats: StateFlow<RecordingStatsSnapshot> = recordingController.stats

    val uiState: StateFlow<RecordingUiState> = combine(
        recordingController.state,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) emit(UserSettings()) else throw exception
        },
        launchConfigHolder.current,
        dialogState,
        combine(activePlacesFlow, pendingOpenPlaceIdState) { places, openId -> places to openId }
    ) { session, settings, launchConfig, dialog, placesPair ->
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
            requestLocationPermission = dialog.requestLocationPermission,
            places = placesPair.first,
            pendingOpenPlaceId = placesPair.second
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
        val initialState = recordingController.state.value
        if (initialState.phase == RecordingPhase.SAVING && initialState.sessionId != null) {
            pendingSaveSessionId = initialState.sessionId
        }
        mapClaimCoordinator.registerClaim(MapClaim(routeKey = RecordingRoute, depth = DEFAULT_CLAIM_DEPTH))
        observeLiveCameraTracking()
        observeCompletedSaves()
        observeRecordingPlaces()
        if (recordingEntryCoordinator.consumeAutoStart() && !initialState.isActive) {
            dispatchStart()
        }
    }

    override fun onCleared() {
        trailPlacesOverlayState.clearClaim(RecordingRoute)
        mapClaimCoordinator.releaseClaim(RecordingRoute)
    }

    fun consumePendingOpenPlace() {
        pendingOpenPlaceIdState.value = null
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
                val hasExistingSession = recordingController.state.value.sessionId != null
                recordingController.dismissFailure()
                if (hasExistingSession) {
                    viewModelScope.launch { recordingController.resume() }
                } else {
                    dispatchStart()
                }
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

    private fun observeCompletedSaves() {
        viewModelScope.launch {
            recordingController.state.collect { session ->
                if (session.phase == RecordingPhase.SAVING && session.sessionId != null) {
                    pendingSaveSessionId = session.sessionId
                }
            }
        }
        viewModelScope.launch {
            recordingController.completedSave
                .filterNotNull()
                .collect { completed ->
                    if (pendingSaveSessionId == completed.sessionId) {
                        pendingSaveSessionId = null
                        recordingController.consumeCompletedSave(completed.hikeId)
                        dialogState.update { it.copy(savedHikeId = completed.hikeId) }
                    }
                }
        }
    }

    private fun observeRecordingPlaces() {
        viewModelScope.launch {
            activePlacesFlow.collect { places ->
                val pins = places.map { place ->
                    MapPlacePin(
                        id = place.id,
                        coordinate = place.coordinate,
                        symbol = place.symbolKind,
                        title = place.name.ifBlank { place.symbol ?: DEFAULT_PIN_TITLE }
                    )
                }
                trailPlacesOverlayState.setClaim(
                    TrailPlacesClaim(
                        routeKey = RecordingRoute,
                        overlay = MapTrailPlacesOverlay(pins = pins),
                        handler = placesInteractionHandler
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
        pendingSaveSessionId = recordingController.state.value.sessionId
        dialogState.update { it.copy(stopDialogStep = null) }
        viewModelScope.launch {
            recordingController.stop(
                customName = current.stopNameDraft,
                offeredTitle = current.offeredTitle
            ).onFailure {
                val state = recordingController.state.value
                if (!state.canRetrySave && state.phase != RecordingPhase.SAVING) {
                    pendingSaveSessionId = null
                }
            }
        }
    }

    private fun confirmDiscard() {
        pendingSaveSessionId = null
        dialogState.update { it.copy(stopDialogStep = null, showDiscardDialog = false) }
        viewModelScope.launch {
            recordingController.discard()
            dialogState.update { it.copy(discardCompleted = true) }
        }
    }

    private fun retrySave() {
        pendingSaveSessionId = recordingController.state.value.sessionId
        viewModelScope.launch {
            recordingController.retrySave()
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
        const val DEFAULT_PIN_TITLE = "Place"
    }
}
