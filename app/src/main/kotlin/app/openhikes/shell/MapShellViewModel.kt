package app.openhikes.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.PhotoMetadataCodec
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.location.TrailWalkEnd
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapLibraryLineOverlay
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapSearchAreaPillState
import app.openhikes.core.map.MapTrailDraftOverlay
import app.openhikes.core.map.MapTrailPhotosOverlay
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.PhotoCaptureAlert
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.PhotoCaptureRequest
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailDraftMapInteractionHandler
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.map.TrailPhotosMapInteractionHandler
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.map.toMapLibraryLineOverlay
import app.openhikes.core.map.toMapRouteOverlay
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.LibraryLine
import app.openhikes.core.model.LibraryLineHitTest
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.WalkTimeLeftMark
import app.openhikes.core.model.toTrailRuns
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.LibraryTotalsRoute
import app.openhikes.core.ui.navigation.MapModesRoute
import app.openhikes.core.ui.navigation.TrailMakerRoute
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
@HiltViewModel
class MapShellViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val hikeRepository: HikeRepository,
    private val offlineCountryRepository: OfflineCountryRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val recordingController: RecordingController,
    private val trailWalkController: TrailWalkController,
    routeHighlightState: RouteHighlightState,
    walkOverlayState: WalkOverlayState,
    private val routeEditOverlayState: RouteEditOverlayState,
    private val trailDraftOverlayState: TrailDraftOverlayState,
    private val mapCameraTracker: MapCameraTracker,
    private val trailPlacesOverlayState: TrailPlacesOverlayState,
    private val trailPhotosOverlayState: TrailPhotosOverlayState,
    private val photoCaptureController: PhotoCaptureController,
    private val mapProviderKeys: MapProviderKeys,
    launchConfigHolder: LaunchConfigHolder,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val showAttributionDialog = MutableStateFlow(false)
    private val showEndWalkForRecordingDialogState = MutableStateFlow(false)
    private val searchQueryState = MutableStateFlow("")
    private val openHikeFromMapRequestState = MutableStateFlow<Pair<Int, HikeId>?>(null)
    private val cameraStateFlow = MutableStateFlow(
        MapCameraState(center = launchConfigHolder.current.value.mapStart ?: MapCameraState.DEFAULT_CENTER)
    )

    @Volatile
    private var hasCameraMoved = false

    @Volatile
    private var latestRouteMidpoints: Map<HikeId, GeoCoordinate> = emptyMap()

    @Volatile
    private var latestVisibleLibraryLines: List<LibraryLine> = emptyList()

    @Volatile
    private var latestSelectedRouteLines: List<LibraryLine> = emptyList()

    val initialCameraState: MapCameraState = cameraStateFlow.value
    val latestCameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()
    val searchQuery: StateFlow<String> = searchQueryState.asStateFlow()
    val openHikeFromMapRequest: StateFlow<Pair<Int, HikeId>?> = openHikeFromMapRequestState.asStateFlow()
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
    val trailDraftOverlay: StateFlow<MapTrailDraftOverlay?> = combine(
        trailDraftOverlayState.overlay,
        mapClaimCoordinator.activeClaim
    ) { overlay, activeClaim ->
        overlay?.takeIf { activeClaim?.routeKey == TrailMakerRoute }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    val trailPlacesOverlay: StateFlow<MapTrailPlacesOverlay?> = combine(
        trailPlacesOverlayState.claims,
        mapClaimCoordinator.activeClaim
    ) { claims, activeClaim ->
        activeClaim?.routeKey?.let { claims[it]?.overlay }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    val trailPhotosOverlay: StateFlow<MapTrailPhotosOverlay?> = combine(
        trailPhotosOverlayState.claims,
        mapClaimCoordinator.activeClaim
    ) { claims, activeClaim ->
        activeClaim?.routeKey?.let { claims[it]?.overlay }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    val showPhotoButtons: StateFlow<Boolean> = photoCaptureController.showFloatingButtons
    val isPhotoBusy: StateFlow<Boolean> = photoCaptureController.isBusy
    val pendingPhotoRequest: StateFlow<PhotoCaptureRequest?> = photoCaptureController.pendingRequest
    val photoAlert: StateFlow<PhotoCaptureAlert?> = photoCaptureController.alert
    val searchAreaPillState: StateFlow<MapSearchAreaPillState?> = combine(
        trailPlacesOverlayState.claims,
        mapClaimCoordinator.activeClaim
    ) { claims, activeClaim ->
        activeClaim?.routeKey?.let { claims[it]?.searchAreaPill }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    val activeRecordingHikeId: StateFlow<HikeId?> = recordingController.state
        .map { session -> session.sessionId?.takeIf { session.isActive }?.let(::HikeId) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = recordingController.state.value.sessionId
                ?.takeIf { recordingController.state.value.isActive }
                ?.let(::HikeId)
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
        mapCameraTracker.update(initialCameraState)
        viewModelScope.launch {
            hikeRepository.seedFixtureIfConfigured()
            hikeRepository.reclaimOrphanedPhotos()
            if (!launchConfigHolder.current.value.disableAutomaticRecovery) {
                recordingController.recoverOpenSession()
            }
            trailWalkController.restoreAtLaunch()
        }
        viewModelScope.launch {
            offlineCountryRepository.start()
        }
    }

    private data class ResolvedRouteLayers(
        val selectedRoutes: List<MapRouteOverlay>,
        val libraryLines: List<MapLibraryLineOverlay>
    )

    private val routeOverlaysFlow = combine(
        hikeRepository.mapRoutes,
        hikeRepository.libraryLines,
        mapClaimCoordinator.activeClaim
    ) { routes, libraryLines, activeClaim ->
        latestRouteMidpoints = routes.mapNotNull { mapRoute ->
            val mid = mapRoute.route.getOrNull(mapRoute.route.size / 2)?.coordinate
            mid?.let { mapRoute.hikeId to it }
        }.toMap()
        val isDrawingTrail = activeClaim?.routeKey == TrailMakerRoute
        val selectedHikeId = activeClaim?.selectedHikeId
        val hiddenHikeId = activeClaim?.hiddenRouteHikeId
        val selectedOverlays = if (isDrawingTrail || selectedHikeId == null || selectedHikeId == hiddenHikeId) {
            emptyList()
        } else {
            routes
                .filter { it.hikeId == selectedHikeId }
                .map { route -> route.toMapRouteOverlay(isSelected = true) }
        }
        val visibleLibraryLines = if (isDrawingTrail) {
            emptyList()
        } else {
            libraryLines.filterNot { it.hikeId == selectedHikeId || it.hikeId == hiddenHikeId }
        }
        latestVisibleLibraryLines = visibleLibraryLines
        latestSelectedRouteLines = selectedOverlays.map { overlay ->
            LibraryLine(
                hikeId = overlay.hikeId,
                tintHex = overlay.tintHex,
                runs = overlay.walkedSegments
            )
        }
        ResolvedRouteLayers(
            selectedRoutes = selectedOverlays,
            libraryLines = visibleLibraryLines.map { it.toMapLibraryLineOverlay() }
        )
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
    ) { settings, activeClaim, routeLayers, attributionDialogOpen ->
        val resolvedStyleId = MapStyleCatalog.renderable(settings.mapStyleId, mapProviderKeys).id
        val defaultOverlay = MapOverlayState(
            mapStyleId = resolvedStyleId,
            showsHikingRoutes = settings.showsHikingRoutes,
            attribution = MapAttribution.forStyle(resolvedStyleId, settings.showsHikingRoutes),
            routes = routeLayers.selectedRoutes,
            libraryLines = routeLayers.libraryLines,
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

    fun trailDraftHandler(): TrailDraftMapInteractionHandler? =
        if (mapClaimCoordinator.activeClaim.value?.routeKey == TrailMakerRoute) {
            trailDraftOverlayState.interactionHandler
        } else {
            null
        }

    fun trailPlacesHandler(): TrailPlacesMapInteractionHandler? =
        trailPlacesOverlayState.claimFor(mapClaimCoordinator.activeClaim.value?.routeKey)?.handler

    fun trailPhotosHandler(): TrailPhotosMapInteractionHandler? =
        trailPhotosOverlayState.claimFor(mapClaimCoordinator.activeClaim.value?.routeKey)?.handler

    fun defaultAddPlaceCoordinate(hikeId: HikeId): GeoCoordinate {
        val highlight = routeHighlight.value
        return when {
            hasCameraMoved -> cameraStateFlow.value.center
            highlight != null && highlight.hikeId == hikeId -> highlight.coordinate
            else -> latestRouteMidpoints[hikeId] ?: cameraStateFlow.value.center
        }
    }

    fun latestRecordingCoordinate(): GeoCoordinate? = recordingController.trace.value.points.lastOrNull()?.coordinate

    internal val mapOverlays: ShellMapOverlays by lazy {
        ShellMapOverlays(
            liveRecordingOverlayState = liveRecordingOverlay,
            walkCoverageOverlayState = walkCoverageOverlay,
            routeEditOverlayState = routeEditOverlay,
            routeEditHandlerProvider = ::routeEditHandler,
            trailDraftOverlayState = trailDraftOverlay,
            trailDraftHandlerProvider = ::trailDraftHandler,
            trailPlacesOverlayState = trailPlacesOverlay,
            trailPlacesHandlerProvider = ::trailPlacesHandler,
            trailPhotosOverlayState = trailPhotosOverlay,
            trailPhotosHandlerProvider = ::trailPhotosHandler
        )
    }

    internal fun createPlacesRuntime(
        searchAreaPill: MapSearchAreaPillState?,
        activeRecordingHikeId: HikeId?
    ): ShellPlacesRuntime = ShellPlacesRuntime(
        searchAreaPill = searchAreaPill,
        activeRecordingHikeId = activeRecordingHikeId,
        defaultAddPlaceCoordinateProvider = ::defaultAddPlaceCoordinate,
        latestRecordingCoordinateProvider = ::latestRecordingCoordinate
    )

    internal fun createWalkDialogs(showEndWalkForRecording: Boolean): ShellWalkDialogCallbacks =
        ShellWalkDialogCallbacks(
            showEndWalkForRecordingDialog = showEndWalkForRecording,
            onRequestEndWalkForRecording = ::onRequestEndWalkForRecording,
            onConfirmEndWalkForRecording = ::onConfirmEndWalkForRecording,
            onCancelEndWalkForRecording = ::onCancelEndWalkForRecording,
            onKeepFollowingOffTrail = ::onKeepFollowingOffTrail,
            onRecordInsteadFromOffTrail = ::onRecordInsteadFromOffTrail
        )

    fun syncBackStack(keysInOrder: List<Any>) {
        mapClaimCoordinator.syncBackStack(keysInOrder)
        photoCaptureController.syncBackStack(keysInOrder)
    }

    fun onRequestCameraPhoto() {
        photoCaptureController.requestCamera()
    }

    fun onRequestLibraryPhotos() {
        photoCaptureController.requestLibrary()
    }

    fun onConsumePhotoRequest(token: Long) {
        photoCaptureController.consumeRequest(token)
    }

    fun onDismissPhotoAlert() {
        photoCaptureController.dismissAlert()
    }

    fun onCameraCaptureFailed(alert: PhotoCaptureAlert) {
        photoCaptureController.showAlert(alert)
    }

    fun onCameraPhotoCaptured(hikeId: HikeId, placeId: String?, bytes: ByteArray, fallbackCapturedAtMillis: Long) {
        viewModelScope.launch {
            photoCaptureController.setBusy(true)
            try {
                val saveToLibrary = try {
                    userSettingsRepository.userSettings.first().savePhotosToLibrary
                } catch (_: IOException) {
                    false
                }
                val exifCapturedAt = withContext(defaultDispatcher) {
                    PhotoMetadataCodec.readMetadata(bytes).capturedAtEpochMillis
                }
                val resolvedShutterMillis = exifCapturedAt ?: fallbackCapturedAtMillis
                val coordinate = resolveCapturedPhotoCoordinate(hikeId, placeId, resolvedShutterMillis)
                val result = hikeRepository.addCameraPhoto(
                    hikeId = hikeId,
                    rawBytes = bytes,
                    capturedAtEpochMillis = resolvedShutterMillis,
                    coordinate = coordinate,
                    placeId = placeId,
                    saveToSystemLibrary = saveToLibrary
                )
                if (result.isFailure) {
                    photoCaptureController.showAlert(PhotoCaptureAlert.CAMERA_SAVE_FAILED)
                }
            } finally {
                photoCaptureController.setBusy(false)
            }
        }
    }

    fun onLibraryPhotosPicked(hikeId: HikeId, placeId: String?, uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        val startEpoch = photoCaptureController.importCancellationEpoch.value
        viewModelScope.launch {
            photoCaptureController.setBusy(true)
            var anyFailed = false
            try {
                for (uriString in uriStrings) {
                    if (photoCaptureController.importCancellationEpoch.value != startEpoch) break
                    val fallbackCoord = resolveCapturedPhotoCoordinate(hikeId, placeId, shutterEpochMillis = null)
                    val result = hikeRepository.addPickedPhotoUri(
                        hikeId = hikeId,
                        uriString = uriString,
                        fallbackCoordinate = fallbackCoord,
                        placeId = placeId
                    )
                    if (result.isFailure) anyFailed = true
                }
                if (anyFailed && photoCaptureController.importCancellationEpoch.value == startEpoch) {
                    photoCaptureController.showAlert(PhotoCaptureAlert.LIBRARY_IMPORT_FAILED)
                }
            } finally {
                photoCaptureController.setBusy(false)
            }
        }
    }

    private suspend fun resolveCapturedPhotoCoordinate(
        hikeId: HikeId,
        placeId: String?,
        shutterEpochMillis: Long?
    ): GeoCoordinate? {
        val registered = photoCaptureController.subjectFor(hikeId, placeId)
        if (registered != null) {
            return registered.anchorProvider(shutterEpochMillis)
        }
        val recording = recordingController.state.value
        return when {
            placeId != null -> hikeRepository.observeHike(hikeId).first()
                ?.places
                ?.firstOrNull { it.id == placeId }
                ?.coordinate
            recording.isActive && recording.sessionId == hikeId.value ->
                recordingController.photoCoordinateAt(shutterEpochMillis)
            else -> null
        }
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
        hasCameraMoved = true
        cameraStateFlow.value = cameraState
        mapCameraTracker.update(cameraState)
    }

    fun onMapTap(coordinate: GeoCoordinate) {
        mapClaimCoordinator.dispatchMapTap(coordinate)
        val hitHikeId = hitLibraryLineOnTap(coordinate) ?: return
        val nextCount = (openHikeFromMapRequestState.value?.first ?: 0) + 1
        openHikeFromMapRequestState.value = nextCount to hitHikeId
    }

    fun onConsumeOpenHikeFromMapRequest(requestToken: Int) {
        if (openHikeFromMapRequestState.value?.first == requestToken) {
            openHikeFromMapRequestState.value = null
        }
    }

    private fun hitLibraryLineOnTap(coordinate: GeoCoordinate): HikeId? {
        if (recordingController.state.value.isActive) return null
        val activeClaim = mapClaimCoordinator.activeClaim.value
        val hasOpenPhotoCallout = trailPhotosOverlay.value?.selectedPin != null
        val canTapLines = !hasOpenPhotoCallout &&
            activeClaim?.hiddenRouteHikeId == null &&
            isLibraryLineTapRoute(activeClaim?.routeKey)
        val zoom = cameraStateFlow.value.zoom
        val hitSelected = LibraryLineHitTest.nearestHikeId(coordinate, zoom, latestSelectedRouteLines) != null
        return if (canTapLines && !hitSelected) {
            LibraryLineHitTest.nearestHikeId(coordinate, zoom, latestVisibleLibraryLines)
        } else {
            null
        }
    }

    private fun isLibraryLineTapRoute(routeKey: Any?): Boolean = when (routeKey) {
        null, is HikeDetailRoute, MapModesRoute, LibraryTotalsRoute -> true
        else -> false
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

    fun createPhotosChrome(showPhotoButtons: Boolean, isBusy: Boolean): MapPhotosChromeState = MapPhotosChromeState(
        showPhotoButtons = showPhotoButtons,
        isBusy = isBusy,
        onTakePhoto = ::onRequestCameraPhoto,
        onAddFromLibrary = ::onRequestLibraryPhotos
    )

    internal fun createPhotoCaptureCallbacks(): PhotoCaptureHostCallbacks = PhotoCaptureHostCallbacks(
        onConsumeRequest = ::onConsumePhotoRequest,
        onCameraPhotoCaptured = ::onCameraPhotoCaptured,
        onCameraCaptureFailed = ::onCameraCaptureFailed,
        onLibraryPhotosPicked = ::onLibraryPhotosPicked,
        onDismissAlert = ::onDismissPhotoAlert
    )

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
