package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.navigation.HikeDetailRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Scoped ViewModel for [HikeDetailRoute] managing off-main route profile preparation,
 * map selection/bounds claims, scrub position highlight isolation, rename, GPX export, and delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = HikeDetailViewModel.Factory::class)
class HikeDetailViewModel @AssistedInject constructor(
    @Assisted rawHikeId: String,
    private val hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val routeHighlightState: RouteHighlightState,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawHikeId: String): HikeDetailViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)

    private val routeKey = HikeDetailRoute(hikeId)
    private val hasAutoFitted = AtomicBoolean(false)

    private val isRenamingState = MutableStateFlow(false)
    private val titleDraftState = MutableStateFlow("")
    private val showDeleteDialogState = MutableStateFlow(false)
    private val actionErrorState = MutableStateFlow<HikeDetailError?>(null)
    private val pendingExportState = MutableStateFlow<ExportedGpx?>(null)
    private val exportSuccessState = MutableStateFlow(false)
    private val scrubDistanceState = MutableStateFlow(0.0)

    @Volatile
    private var latestProfile: RouteProfile? = null

    @Volatile
    private var latestTintHex: String = ""

    val scrubDistanceMeters: StateFlow<Double> = scrubDistanceState.asStateFlow()

    private val hikeFlow = hikeRepository.observeHike(hikeId).onEach { hike ->
        if (hike != null) {
            latestTintHex = hike.tintHex
        }
    }

    private val preparedFlow = hikeRepository.observeHike(hikeId)
        .map { hike -> hike?.let { it.distanceMeters to it.route } }
        .distinctUntilChanged()
        .mapLatest { pair ->
            if (pair == null) {
                null
            } else {
                val (distanceMeters, route) = pair
                val profile = RouteProfile.from(route)
                val stats = HikeRouteStatistics.from(distanceMeters, route)
                profile to stats
            }
        }
        .flowOn(defaultDispatcher)
        .onEach { prepared ->
            latestProfile = prepared?.first
            val bounds = prepared?.first?.boundingBox
            if (bounds != null && hasAutoFitted.compareAndSet(false, true)) {
                dispatchFitBounds(bounds)
            }
        }

    private val editUiStateFlow = combine(
        isRenamingState,
        titleDraftState,
        showDeleteDialogState,
        actionErrorState,
        combine(pendingExportState, exportSuccessState) { pending, success -> pending to success }
    ) { isRenaming, draft, showDelete, error, exportPair ->
        TransientDetailState(
            isRenaming = isRenaming,
            titleDraft = draft,
            showDeleteDialog = showDelete,
            actionError = error,
            pendingExport = exportPair.first,
            exportSuccess = exportPair.second
        )
    }

    val uiState: StateFlow<HikeDetailUiState> = combine(
        hikeFlow,
        preparedFlow,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        },
        editUiStateFlow
    ) { hike: Hike?, prepared, settings, transient ->
        HikeDetailUiState(
            hikeId = hikeId,
            hike = hike,
            profile = prepared?.first,
            statistics = prepared?.second,
            distanceUnit = settings.distanceUnit,
            isLoaded = true,
            isRenaming = transient.isRenaming,
            titleDraft = transient.titleDraft,
            showDeleteDialog = transient.showDeleteDialog,
            actionError = transient.actionError,
            pendingExport = transient.pendingExport,
            exportSuccess = transient.exportSuccess
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HikeDetailUiState(hikeId = hikeId)
    )

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = routeKey,
                depth = INITIAL_CLAIM_DEPTH,
                selectedHikeId = hikeId
            )
        )
    }

    override fun onCleared() {
        routeHighlightState.clearIfHike(hikeId)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onScrubDistance(distanceMeters: Double) {
        val profile = latestProfile ?: return
        val clamped = distanceMeters.coerceIn(0.0, profile.totalDistanceMeters)
        scrubDistanceState.value = clamped
        val coordinate = profile.coordinateAt(clamped) ?: return
        val tint = latestTintHex.ifBlank { RouteTint.DEFAULT_HEX }
        routeHighlightState.move(
            MapRouteHighlight(
                hikeId = hikeId,
                coordinate = coordinate,
                tintHex = tint
            )
        )
    }

    fun onZoomToRoute() {
        val bounds = latestProfile?.boundingBox ?: return
        dispatchFitBounds(bounds)
    }

    fun onStartRename() {
        val currentTitle = uiState.value.hike?.displayTitle.orEmpty()
        titleDraftState.value = currentTitle
        isRenamingState.value = true
        actionErrorState.value = null
    }

    fun onTitleDraftChange(draft: String) {
        titleDraftState.value = draft
    }

    fun onCancelRename() {
        isRenamingState.value = false
    }

    fun onCommitRename() {
        if (!isRenamingState.value) return
        val draft = titleDraftState.value
        viewModelScope.launch {
            val result = hikeRepository.renameHike(hikeId, draft)
            if (result.isSuccess) {
                isRenamingState.value = false
                actionErrorState.value = null
            } else {
                actionErrorState.value = HikeDetailError.RENAME_FAILED
            }
        }
    }

    fun onRequestExportGpx() {
        actionErrorState.value = null
        exportSuccessState.value = false
        viewModelScope.launch {
            val result = hikeRepository.exportHike(hikeId)
            result.onSuccess { exported ->
                pendingExportState.value = exported
            }.onFailure {
                actionErrorState.value = HikeDetailError.EXPORT_FAILED
            }
        }
    }

    fun onExportDestinationPicked(uriString: String?) {
        val pending = pendingExportState.value
        pendingExportState.value = null
        if (uriString == null || pending == null) return
        viewModelScope.launch {
            val result = hikeRepository.exportHikeToUri(hikeId, uriString)
            if (result.isSuccess) {
                exportSuccessState.value = true
            } else {
                actionErrorState.value = HikeDetailError.EXPORT_FAILED
            }
        }
    }

    fun onRequestDelete() {
        showDeleteDialogState.value = true
    }

    fun onCancelDelete() {
        showDeleteDialogState.value = false
    }

    fun onConfirmDelete(onDeleted: (HikeId) -> Unit) {
        showDeleteDialogState.value = false
        viewModelScope.launch {
            val result = hikeRepository.deleteHike(hikeId)
            if (result.isSuccess) {
                routeHighlightState.clearIfHike(hikeId)
                actionErrorState.value = null
                onDeleted(hikeId)
            } else {
                actionErrorState.value = HikeDetailError.DELETE_FAILED
            }
        }
    }

    fun onDismissMessages() {
        actionErrorState.value = null
        exportSuccessState.value = false
    }

    private fun dispatchFitBounds(bounds: GeoBoundingBox) {
        mapClaimCoordinator.requestCameraCommand(
            routeKey = routeKey,
            command = MapCameraCommand.FitBounds(
                id = mapClaimCoordinator.nextCommandId(),
                bounds = bounds
            )
        )
    }

    private data class TransientDetailState(
        val isRenaming: Boolean,
        val titleDraft: String,
        val showDeleteDialog: Boolean,
        val actionError: HikeDetailError?,
        val pendingExport: ExportedGpx?,
        val exportSuccess: Boolean
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 1
    }
}
