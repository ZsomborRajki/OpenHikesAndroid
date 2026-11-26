package app.openhikes.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.location.TrailWalkEnd
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.map.toMapRouteOverlay
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.WalkTimeLeftMark
import app.openhikes.core.model.toTrailRuns
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coarse UI state for map overlays and attribution chrome, deliberately excluding high-frequency
 * camera/scroll ticks so root shell composables do not recompose during pan/zoom gestures.
 */
@Immutable
data class MapChromeUiState(
    val overlayState: MapOverlayState = MapOverlayState(),
    val showAttributionDialog: Boolean = false
)

/**
 * Root ViewModel coordinating map chrome, route overlays, search query, trail-following chrome,
 * and route-owned map claims.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class MapShellViewModel @Inject constructor(
    userSettingsRepository: UserSettingsRepository,
    private val hikeRepository: HikeRepository,
    private val offlineCountryRepository: OfflineCountryRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val recordingController: RecordingController,
    private val trailWalkController: TrailWalkController,
    routeHighlightState: RouteHighlightState,
    walkOverlayState: WalkOverlayState,
    private val routeEditOverlayState: RouteEditOverlayState,
    private val mapProviderKeys: MapProviderKeys,
    launchConfigHolder: LaunchConfigHolder,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val showAttributionDialog = MutableStateFlow(false)
    private val showEndWalkForRecordingDialogState = MutableStateFlow(false)
    private val searchQueryState = MutableStateFlow("")
    private val cameraStateFlow = MutableStateFlow(
        MapCameraState(center = launchConfigHolder.current.value.mapStart ?: MapCameraState.DEFAULT_CENTER)
    )

    val initialCameraState: MapCameraState = cameraStateFlow.value
    val latestCameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()
    val searchQuery: StateFlow<String> = searchQueryState.asStateFlow()
    val pendingCameraCommand: StateFlow<MapCameraCommand?> = mapClaimCoordinator.pendingCameraCommand
    val routeHighlight: StateFlow<MapRouteHighlight?> = routeHighlightState.highlight
    val walkCoverageOverlay: StateFlow<MapWalkCoverageOverlay?> = walkOverlayState.coverageOverlay
    val routeEditOverlay: StateFlow<MapRouteEditOverlay?> = combine(
        routeEditOverlayState.overlay,
        mapClaimCoordinator.activeClaim
    ) { overlay, activeClaim ->
        overlay?.takeIf { activeClaim?.hiddenRouteHikeId == it.hikeId }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    val walkSession: StateFlow<TrailWalkSessionState> = trailWalkController.sessionState
    val showEndWalkForRecordingDialog: StateFlow<Boolean> = showEndWalkForRecordingDialogState.asStateFlow()
    val walkTimeLeftMark: StateFlow<WalkTimeLeftMark?> = trailWalkController.progressState
        .map { it.timeLeftMark }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )
    val isRecordingActive: StateFlow<Boolean> = recordingController.state
        .map { it.isActive }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = recordingController.state.value.isActive
        )
    val liveRecordingOverlay: StateFlow<MapRouteOverlay?> = recordingController.trace
        .map { snapshot -> snapshot.toLiveOverlayOrNull() }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            hikeRepository.seedFixtureIfConfigured()
            if (!launchConfigHolder.current.value.disableAutomaticRecovery) {
                recordingController.recoverOpenSession()
            }
            trailWalkController.restoreAtLaunch()
        }
        viewModelScope.launch {
            offlineCountryRepository.start()
        }
    }

    private val routeOverlaysFlow = combine(
        hikeRepository.mapRoutes,
        mapClaimCoordinator.activeClaim
    ) { routes, activeClaim ->
        val selectedHikeId = activeClaim?.selectedHikeId
        val hiddenHikeId = activeClaim?.hiddenRouteHikeId
        routes
            .filterNot { it.hikeId == hiddenHikeId }
            .map { route ->
                route.toMapRouteOverlay(isSelected = route.hikeId == selectedHikeId)
            }
    }.flowOn(defaultDispatcher)

    val chromeUiState: StateFlow<MapChromeUiState> = combine(
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        },
        mapClaimCoordinator.activeClaim,
        routeOverlaysFlow,
        showAttributionDialog
    ) { settings, activeClaim, routeOverlays, attributionDialogOpen ->
        val resolvedStyleId = MapStyleCatalog.renderable(settings.mapStyleId, mapProviderKeys).id
        val hiddenHikeId = activeClaim?.hiddenRouteHikeId
        val visibleRoutes = if (hiddenHikeId != null) {
            routeOverlays.filterNot { it.hikeId == hiddenHikeId }
        } else {
            routeOverlays
        }
        val defaultOverlay = MapOverlayState(
            mapStyleId = resolvedStyleId,
            showsHikingRoutes = settings.showsHikingRoutes,
            attribution = MapAttribution.forStyle(resolvedStyleId, settings.showsHikingRoutes),
            routes = visibleRoutes,
            countryOutlines = activeClaim?.countryOutlines.orEmpty()
        )
        MapChromeUiState(
            overlayState = activeClaim?.overlayOverride ?: defaultOverlay,
            showAttributionDialog = attributionDialogOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MapChromeUiState()
    )

    fun routeEditHandler(): RouteEditMapInteractionHandler? {
        val activeHiddenId = mapClaimCoordinator.activeClaim.value?.hiddenRouteHikeId
        val overlayHikeId = routeEditOverlayState.overlay.value?.hikeId
        return if (activeHiddenId != null && activeHiddenId == overlayHikeId) {
            routeEditOverlayState.interactionHandler
        } else {
            null
        }
    }

    fun syncBackStack(keysInOrder: List<Any>) {
        mapClaimCoordinator.syncBackStack(keysInOrder)
    }

    fun onToggleJunctionWarnings() {
        viewModelScope.launch {
            trailWalkController.toggleJunctionWarnings()
        }
    }

    fun onDismissWalkStartNotice() {
        trailWalkController.dismissStartNotice()
    }

    fun onKeepFollowingOffTrail() {
        trailWalkController.keepFollowingAfterOffTrail()
    }

    fun onRequestEndWalkForRecording() {
        showEndWalkForRecordingDialogState.value = true
    }

    fun onCancelEndWalkForRecording() {
        showEndWalkForRecordingDialogState.value = false
    }

    fun onConfirmEndWalkForRecording(onProceedToRecording: () -> Unit) {
        showEndWalkForRecordingDialogState.value = false
        endWalkAndProceedToRecording(onProceedToRecording)
    }

    fun onRecordInsteadFromOffTrail(onProceedToRecording: () -> Unit) {
        endWalkAndProceedToRecording(onProceedToRecording)
    }

    private fun endWalkAndProceedToRecording(onProceedToRecording: () -> Unit) {
        viewModelScope.launch {
            val result = trailWalkController.endWalk()
            if (result !is TrailWalkEnd.Refused) {
                trailWalkController.consumeLastEndedWalk()
                onProceedToRecording()
            }
        }
    }

    fun onZoomIn() {
        mapClaimCoordinator.requestCameraCommand(
            routeKey = MapClaimCoordinator.ROOT_CAMERA_KEY,
            command = MapCameraCommand.ZoomIn(id = mapClaimCoordinator.nextCommandId())
        )
    }

    fun onZoomOut() {
        mapClaimCoordinator.requestCameraCommand(
            routeKey = MapClaimCoordinator.ROOT_CAMERA_KEY,
            command = MapCameraCommand.ZoomOut(id = mapClaimCoordinator.nextCommandId())
        )
    }

    fun onResetNorth() {
        mapClaimCoordinator.requestCameraCommand(
            routeKey = MapClaimCoordinator.ROOT_CAMERA_KEY,
            command = MapCameraCommand.ResetNorth(id = mapClaimCoordinator.nextCommandId())
        )
    }

    fun onCameraCommandHandled(commandId: Long) {
        mapClaimCoordinator.consumeCameraCommand(commandId)
    }

    fun onCameraChanged(cameraState: MapCameraState) {
        cameraStateFlow.value = cameraState
    }

    fun onMapTap(coordinate: GeoCoordinate) {
        mapClaimCoordinator.dispatchMapTap(coordinate)
    }

    fun onSearchQueryChange(query: String) {
        searchQueryState.value = query
    }

    fun onClearSearch() {
        searchQueryState.value = ""
    }

    fun onOpenAttributionDialog() {
        showAttributionDialog.value = true
    }

    fun onDismissAttributionDialog() {
        showAttributionDialog.value = false
    }

    fun onAppBackgrounded() {
        viewModelScope.launch {
            recordingController.flushJournal()
        }
    }

    private fun RecordingTraceSnapshot.toLiveOverlayOrNull(): MapRouteOverlay? {
        val id = hikeId
        if (id == null || points.size < MINIMUM_OVERLAY_POINTS) return null
        val runs = points.toTrailRuns()
        val walked = runs.walkedRuns.map { range ->
            points.subList(range.first, range.last + 1).map { it.coordinate }
        }
        val paused = runs.pausedStretches.map { range ->
            points.subList(range.first, range.last + 1).map { it.coordinate }
        }
        return MapRouteOverlay(
            hikeId = id,
            walkedSegments = walked,
            pausedSegments = paused,
            tintHex = tintHex,
            borderHex = RouteBorder.NONE_HEX,
            widthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP.toFloat(),
            linePattern = RouteLinePattern.Default,
            isSelected = true
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MINIMUM_OVERLAY_POINTS = 2
    }
}
