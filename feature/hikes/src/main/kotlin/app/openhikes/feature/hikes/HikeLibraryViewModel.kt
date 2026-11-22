package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinates the hike library list, GPX import flow, and delete confirmation state.
 */
@HiltViewModel
class HikeLibraryViewModel @Inject constructor(
    private val hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    mapClaimCoordinator: MapClaimCoordinator,
    private val routeHighlightState: RouteHighlightState
) : ViewModel() {
    private val isImportingState = MutableStateFlow(false)
    private val importErrorState = MutableStateFlow<GpxImportFailure?>(null)
    private val actionErrorState = MutableStateFlow<String?>(null)
    private val pendingDeleteHikeState = MutableStateFlow<HikeSummary?>(null)

    private val dialogAndErrorState = combine(
        isImportingState,
        importErrorState,
        actionErrorState,
        pendingDeleteHikeState
    ) { isImporting, importError, actionError, pendingDelete ->
        TransientLibraryState(
            isImporting = isImporting,
            importError = importError,
            actionError = actionError,
            pendingDeleteHike = pendingDelete
        )
    }

    val uiState: StateFlow<HikeLibraryUiState> = combine(
        hikeRepository.hikes,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        },
        mapClaimCoordinator.activeClaim,
        dialogAndErrorState
    ) { hikes, settings, activeClaim, transient ->
        HikeLibraryUiState(
            hikes = hikes,
            distanceUnit = settings.distanceUnit,
            selectedHikeId = activeClaim?.selectedHikeId,
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
                routeHighlightState.clearIfHike(target.id)
                actionErrorState.value = null
                onHikeDeleted(target.id)
            } else {
                actionErrorState.value = DELETE_ERROR_KEY
            }
        }
    }

    private data class TransientLibraryState(
        val isImporting: Boolean,
        val importError: GpxImportFailure?,
        val actionError: String?,
        val pendingDeleteHike: HikeSummary?
    )

    companion object {
        const val DELETE_ERROR_KEY = "delete_failed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
