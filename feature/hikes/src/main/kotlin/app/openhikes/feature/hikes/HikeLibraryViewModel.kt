package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeListOrder
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinates the hike library list, sorting, custom reordering, background Hilliest elevation
 * validation, GPX import flow, and delete confirmation state.
 */
@HiltViewModel
@Suppress("LongParameterList")
class HikeLibraryViewModel @Inject constructor(
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    recordingController: RecordingController,
    private val trailWalkController: TrailWalkController,
    mapClaimCoordinator: MapClaimCoordinator,
    private val routeHighlightState: RouteHighlightState
) : ViewModel() {
    private val isImportingState = MutableStateFlow(false)
    private val importErrorState = MutableStateFlow<GpxImportFailure?>(null)
    private val actionErrorState = MutableStateFlow<String?>(null)
    private val pendingDeleteHikeState = MutableStateFlow<HikeSummary?>(null)
    private val isReorderingState = MutableStateFlow(false)

    private val settingsFlow = userSettingsRepository.userSettings.catch { exception ->
        if (exception is IOException) {
            emit(UserSettings())
        } else {
            throw exception
        }
    }

    private val dialogAndModeState = combine(
        isImportingState,
        importErrorState,
        actionErrorState,
        pendingDeleteHikeState,
        isReorderingState
    ) { isImporting, importError, actionError, pendingDelete, isReordering ->
        TransientLibraryState(
            isImporting = isImporting,
            importError = importError,
            actionError = actionError,
            pendingDeleteHike = pendingDelete,
            isReordering = isReordering
        )
    }

    private val activityState = combine(
        recordingController.state.map { session ->
            session.sessionId?.takeIf { session.isActive }?.let(::HikeId)
        }.distinctUntilChanged(),
        trailWalkController.sessionState,
        mapClaimCoordinator.activeClaim
    ) { recordingHikeId, walkSession, activeClaim ->
        ActivityProjection(
            recordingHikeId = recordingHikeId,
            walkedHikeId = walkSession.walkedHikeId,
            walkedPhase = walkSession.phase,
            selectedHikeId = activeClaim?.selectedHikeId
        )
    }

    val uiState: StateFlow<HikeLibraryUiState> = combine(
        hikeRepository.hikes,
        settingsFlow,
        activityState,
        dialogAndModeState
    ) { rawHikes, settings, activity, transient ->
        val activeHikeId = activity.recordingHikeId ?: activity.walkedHikeId
        val isCustomOrder = HikeListOrder.isCustom(rawHikes)
        val arranged = HikeListOrder.arrange(
            hikes = rawHikes,
            activeHikeId = activeHikeId,
            sort = settings.hikeListSort
        )
        val effectiveReordering = transient.isReordering && movableHikeCount(arranged, activeHikeId) > 1
        HikeLibraryUiState(
            hikes = arranged,
            distanceUnit = settings.distanceUnit,
            sort = settings.hikeListSort,
            isCustomOrder = isCustomOrder,
            isReordering = effectiveReordering,
            activeHikeId = activeHikeId,
            recordingHikeId = activity.recordingHikeId,
            selectedHikeId = activity.selectedHikeId,
            walkedHikeId = activity.walkedHikeId,
            walkedPhase = activity.walkedPhase,
            isImporting = transient.isImporting,
            importError = transient.importError,
            actionError = transient.actionError,
            pendingDeleteHike = transient.pendingDeleteHike
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HikeLibraryUiState()
    )

    init {
        observeElevationValidation()
    }

    private fun observeElevationValidation() {
        viewModelScope.launch {
            combine(
                settingsFlow.map { it.hikeListSort }.distinctUntilChanged(),
                hikeRepository.hikes.map { list ->
                    HikeListOrder.isCustom(list) to list.map { it.id }
                }.distinctUntilChanged()
            ) { sort, (isCustom, hikeIds) ->
                if (isCustom || !sort.needsElevation) emptyList() else hikeIds
            }
                .distinctUntilChanged()
                .collectLatest { candidateIds ->
                    if (candidateIds.isNotEmpty()) {
                        hikeRepository.validateElevationMetrics(candidateIds)
                    }
                }
        }
    }

    fun onSelectSort(sort: HikeListSort) {
        isReorderingState.value = false
        viewModelScope.launch {
            if (uiState.value.isCustomOrder) {
                hikeRepository.clearCustomHikeOrder()
            }
            try {
                userSettingsRepository.setHikeListSort(sort)
            } catch (_: IOException) {
                actionErrorState.value = SORT_ERROR_KEY
            }
        }
    }

    fun onEnterReorderMode() {
        val current = uiState.value
        if (movableHikeCount(current.hikes, current.activeHikeId) > 1) {
            isReorderingState.value = true
        }
    }

    private fun movableHikeCount(hikes: List<HikeSummary>, activeHikeId: HikeId?): Int =
        if (activeHikeId != null && hikes.any { it.id == activeHikeId }) hikes.size - 1 else hikes.size

    fun onExitReorderMode() {
        isReorderingState.value = false
    }

    fun onMoveHike(fromIndex: Int, toIndex: Int) {
        val current = uiState.value
        val nextIds = HikeListOrder.move(
            displayed = current.hikes,
            fromIndex = fromIndex,
            toIndex = toIndex,
            activeHikeId = current.activeHikeId
        ) ?: return
        viewModelScope.launch {
            val result = hikeRepository.saveCustomHikeOrder(nextIds)
            if (result.isFailure) {
                actionErrorState.value = SORT_ERROR_KEY
            }
        }
    }

    fun onImportGpxUri(uriString: String) {
        if (isImportingState.value) return
        viewModelScope.launch {
            isImportingState.value = true
            importErrorState.value = null
            when (val result = hikeRepository.importGpxUri(uriString)) {
                is GpxImportResult.Success -> Unit
                is GpxImportResult.Failure -> importErrorState.value = result.reason
            }
            isImportingState.value = false
        }
    }

    fun onDismissImportError() {
        importErrorState.value = null
    }

    fun onDismissActionError() {
        actionErrorState.value = null
    }

    fun onRequestDelete(hike: HikeSummary) {
        pendingDeleteHikeState.value = hike
    }

    fun onCancelDelete() {
        pendingDeleteHikeState.value = null
    }

    fun onConfirmDelete(onHikeDeleted: (HikeId) -> Unit) {
        val target = pendingDeleteHikeState.value ?: return
        pendingDeleteHikeState.value = null
        viewModelScope.launch {
            val result = hikeRepository.deleteHike(target.id)
            if (result.isSuccess) {
                trailWalkController.onHikeDeleted(target.id)
                routeHighlightState.clearIfHike(target.id)
                actionErrorState.value = null
                onHikeDeleted(target.id)
            } else {
                actionErrorState.value = DELETE_ERROR_KEY
            }
        }
    }

    private data class ActivityProjection(
        val recordingHikeId: HikeId?,
        val walkedHikeId: HikeId?,
        val walkedPhase: TrailWalkPhase?,
        val selectedHikeId: HikeId?
    )

    private data class TransientLibraryState(
        val isImporting: Boolean,
        val importError: GpxImportFailure?,
        val actionError: String?,
        val pendingDeleteHike: HikeSummary?,
        val isReordering: Boolean
    )

    companion object {
        const val DELETE_ERROR_KEY = "delete_failed"
        const val SORT_ERROR_KEY = "sort_failed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
