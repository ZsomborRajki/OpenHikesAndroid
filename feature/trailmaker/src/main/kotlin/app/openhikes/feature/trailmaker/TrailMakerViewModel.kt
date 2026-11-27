package app.openhikes.feature.trailmaker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.StopPlaceSearchSource
import app.openhikes.core.data.TrailDraftRepository
import app.openhikes.core.data.TrailDraftWriteCoordinator
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.LocationFeed
import app.openhikes.core.location.LocationPermissionStatus
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailDraftMapInteractionHandler
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailDraftDroppedPin
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailDraftRefusal
import app.openhikes.core.model.TrailDraftSelection
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegRouter
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.ui.navigation.TrailMakerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Scoped ViewModel for [TrailMakerRoute] owning draft restoration & persistence, sequential leg
 * routing, reverse stop naming, stop search, place card state, map overlay publication, and save.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class TrailMakerViewModel @Inject constructor(
    private val trailDraftRepository: TrailDraftRepository,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val trailLegRouter: TrailLegRouter,
    trailGraphProvider: TrailGraphProvider,
    private val stopPlaceSearchSource: StopPlaceSearchSource,
    private val locationFeed: LocationFeed,
    private val clock: OpenHikesClock,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val trailDraftOverlayState: TrailDraftOverlayState,
    private val draftWriteCoordinator: TrailDraftWriteCoordinator,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    private val engine = TrailDraftEngine()
    private val uiStateFlow = MutableStateFlow(TrailMakerUiState(isRestoring = true))
    val uiState: StateFlow<TrailMakerUiState> = uiStateFlow.asStateFlow()

    private var distanceUnit: DistanceUnit = DistanceUnit.METRIC
    private var routeColoring: RouteColoring = RouteColoring.Default
    private var recentStops: List<TrailStopRecent> = emptyList()
    private var droppedPin: TrailDraftDroppedPin? = null
    private var selection: TrailDraftSelection? = null
    private var placeCardAddress: String? = null
    private var placeCardAddressJob: Job? = null

    private var showCloseDialog: Boolean = false
    private var showNameDialog: Boolean = false
    private var namingStartedAtEpochMillis: Long? = null
    private var nameDraft: String = ""
    private var isRestoring: Boolean = true
    private var isSaving: Boolean = false
    private var refusal: TrailDraftRefusal? = null
    private var stopSearchState: TrailStopSearchUiState? = null
    private var searchJob: Job? = null
    private var myLocationCoordinate: GeoCoordinate? = null
    private var myLocationJob: Job? = null
    private var hasFramedDrawing: Boolean = false
    private val backgroundQueues = TrailDraftBackgroundQueues(
        scope = viewModelScope,
        engine = engine,
        trailLegRouter = trailLegRouter,
        stopPlaceSearchSource = stopPlaceSearchSource,
        onRoutingUpdate = ::publishAll,
        onStopNamed = {
            if (canMutateDraft) {
                persistDraft()
                publishAll()
            }
        }
    )

    private val canMutateDraft: Boolean
        get() = !isRestoring && !isSaving

    private val shadingController = TrailDraftShadingController(
        scope = viewModelScope,
        engine = engine,
        trailGraphProvider = trailGraphProvider,
        defaultDispatcher = defaultDispatcher,
        canMutateDraft = { canMutateDraft },
        onStretchesUpdated = {
            publishOverlay()
            publishUiState()
        }
    )

    private val mapInteractionHandler = object : TrailDraftMapInteractionHandler {
        override fun onDropPin(coordinate: GeoCoordinate, legEnds: TrailLegEnds?) {
            if (!canMutateDraft) return
            droppedPin = TrailDraftDroppedPin(coordinate = coordinate, legEnds = legEnds)
            selectPlace(TrailDraftSelection.DroppedPin)
        }

        override fun onTapStopPin(waypointId: String) {
            if (!canMutateDraft) return
            if (engine.waypoints.any { it.id == waypointId }) {
                selectPlace(TrailDraftSelection.Stop(waypointId))
            }
        }

        override fun onTapDroppedPin() {
            if (!canMutateDraft) return
            if (droppedPin != null) {
                selectPlace(TrailDraftSelection.DroppedPin)
            }
        }

        override fun onChooseAlternative(legIndex: Int, alternativeIndex: Int) {
            if (!canMutateDraft) return
            if (engine.chooseAlternative(legIndex, alternativeIndex)) {
                persistDraft()
                publishAll()
            }
        }

        override fun onBeginPinDrag(index: Int): Boolean {
            if (!canMutateDraft) return false
            val started = engine.beginDrag(index)
            if (started) publishOverlay()
            return started
        }

        override fun onMovePinDrag(coordinate: GeoCoordinate) {
            if (!canMutateDraft) return
            if (engine.moveDrag(coordinate)) publishOverlay()
        }

        override fun onEndPinDrag() {
            if (!canMutateDraft) {
                if (engine.cancelDrag()) publishOverlay()
                return
            }
            val moved = engine.endDrag()
            if (moved) {
                commitLineAndRoute()
            } else {
                publishOverlay()
            }
        }

        override fun onCancelPinDrag() {
            if (engine.cancelDrag()) publishOverlay()
        }
    }

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = TrailMakerRoute,
                depth = INITIAL_CLAIM_DEPTH,
                selectedHikeId = null
            )
        )
        trailDraftOverlayState.bindHandler(mapInteractionHandler)
        publishUiState()
        observeSettingsAndRecents()
        restoreInitialDraft()
    }

    override fun onCleared() {
        cancelRouting()
        cancelNaming()
        cancelShading()
        cancelMyLocationLookup()
        searchJob?.cancel()
        placeCardAddressJob?.cancel()
        engine.cancelDrag()
        trailDraftOverlayState.clear()
        mapClaimCoordinator.releaseClaim(TrailMakerRoute)
    }

    fun onAction(action: TrailMakerAction) {
        when (action) {
            is TrailMakerAction.SelectTravelMode -> setTravelMode(action.mode)
            is TrailMakerAction.ToggleSnapToPaths -> setSnapsToPaths(action.enabled)
            is TrailMakerAction.SelectRouteColoring -> selectRouteColoring(action.coloring)
            TrailMakerAction.RetryRefusedLegs -> if (canMutateDraft) resolveLegs(retryingRefusals = true)
            is TrailMakerAction.OpenStopSearch -> openStopSearch(action.target)
            TrailMakerAction.CloseStopSearch -> closeStopSearch()
            is TrailMakerAction.UpdateSearchQuery -> updateSearchQuery(action.query)
            is TrailMakerAction.PickSearchResult -> applySearchPick(
                coordinate = action.result.coordinate,
                name = action.result.name,
                subtitle = action.result.subtitle
            )
            is TrailMakerAction.PickRecentStop -> applySearchPick(
                coordinate = action.recent.coordinate,
                name = action.recent.name,
                subtitle = action.recent.subtitle
            )
            TrailMakerAction.PickMyLocation -> pickMyLocation()
            is TrailMakerAction.RemoveRecentStop -> removeRecentStop(action.id)
            else -> onEditingOrDialogAction(action)
        }
    }

    private fun onEditingOrDialogAction(action: TrailMakerAction) {
        when (action) {
            is TrailMakerAction.DeleteStop -> removeStop(action.waypointId)
            is TrailMakerAction.MoveStop -> moveStop(action.waypointId, action.delta)
            TrailMakerAction.ClosePlaceCard -> selectPlace(null)
            TrailMakerAction.AddStopFromPlaceCard -> addStopFromPlaceCard()
            TrailMakerAction.RemoveDroppedPin -> removeDroppedPin()
            is TrailMakerAction.RemoveStopFromPlaceCard -> {
                if (canMutateDraft) {
                    selectPlace(null)
                    removeStop(action.waypointId)
                }
            }
            TrailMakerAction.DismissCloseDialog -> {
                showCloseDialog = false
                publishUiState()
            }
            TrailMakerAction.OpenSaveDialog -> openSaveDialog()
            is TrailMakerAction.UpdateNameDraft -> {
                if (canMutateDraft) {
                    nameDraft = action.value
                    publishUiState()
                }
            }
            TrailMakerAction.DismissNameDialog -> {
                showNameDialog = false
                publishUiState()
            }
            TrailMakerAction.DismissRefusal -> {
                refusal = null
                publishUiState()
            }
            else -> Unit
        }
    }

    fun onCloseRequested(onClose: () -> Unit) {
        if (!canMutateDraft) return
        if (engine.isEmpty) {
            discardAndClose(onClose)
        } else {
            showCloseDialog = true
            publishUiState()
        }
    }

    fun onDiscardTrail(onClose: () -> Unit) {
        if (!canMutateDraft) return
        showCloseDialog = false
        discardAndClose(onClose)
    }

    fun onKeepForLater(onClose: () -> Unit) {
        if (!canMutateDraft) return
        showCloseDialog = false
        publishUiState()
        viewModelScope.launch {
            draftWriteCoordinator.awaitPendingWrites()
            onClose()
        }
    }

    fun onConfirmSave(onSaved: (HikeId) -> Unit) {
        if (!canMutateDraft) return
        val madeOn = namingStartedAtEpochMillis ?: clock.nowEpochMillis()
        showNameDialog = false
        if (!engine.canBeSaved) {
            refusal = TrailDraftRefusal.TOO_SHORT
            publishUiState()
            return
        }
        val titleInput = nameDraft
        val distanceMeters = engine.distanceMeters
        val route = engine.routeCoordinates
        prepareForSave()
        viewModelScope.launch {
            val result = hikeRepository.saveDrawnTrail(
                title = titleInput,
                distanceMeters = distanceMeters,
                route = route,
                madeOnEpochMillis = madeOn
            )
            result.fold(
                onSuccess = { hike ->
                    nameDraft = ""
                    namingStartedAtEpochMillis = null
                    clearDraftStateAndAwait()
                    isSaving = false
                    publishUiState()
                    onSaved(hike.id)
                },
                onFailure = {
                    isSaving = false
                    refusal = TrailDraftRefusal.NOT_SAVED
                    publishAll()
                    resolveLegs(retryingRefusals = false)
                }
            )
        }
    }

    private fun prepareForSave() {
        isSaving = true
        cancelRouting()
        cancelNaming()
        cancelShading()
        cancelMyLocationLookup()
        searchJob?.cancel()
        searchJob = null
        placeCardAddressJob?.cancel()
        placeCardAddressJob = null
        engine.cancelDrag()
        stopSearchState = null
        selection = null
        showCloseDialog = false
        publishAll()
    }

    private fun observeSettingsAndRecents() {
        viewModelScope.launch {
            userSettingsRepository.userSettings
                .catch { error -> if (error !is IOException) throw error }
                .collect { settings ->
                    distanceUnit = settings.distanceUnit
                    val coloringChanged = routeColoring != settings.routeColoring
                    routeColoring = settings.routeColoring
                    if (coloringChanged) {
                        publishAll()
                    } else {
                        publishUiState()
                    }
                }
        }
        viewModelScope.launch {
            trailDraftRepository.recentStops.collect { list ->
                recentStops = list
                stopSearchState = stopSearchState?.copy(recents = list)
                publishUiState()
            }
        }
    }

    private fun restoreInitialDraft() {
        viewModelScope.launch {
            draftWriteCoordinator.awaitPendingWrites()
            val stored = trailDraftRepository.loadDraft()
            if (!stored.isEmpty) {
                engine.replace(stored)
                if (!hasFramedDrawing && engine.coordinates.isNotEmpty()) {
                    hasFramedDrawing = true
                    frameCoordinates(engine.coordinates)
                }
            }
            isRestoring = false
            publishAll()
            resolveLegs(retryingRefusals = false)
            enqueueUnnamedStops()
        }
    }

    private fun setTravelMode(mode: TrailTravelMode) {
        if (!canMutateDraft || !engine.setTravelMode(mode)) return
        cancelRouting()
        commitLineAndRoute()
    }

    private fun setSnapsToPaths(enabled: Boolean) {
        if (!canMutateDraft || !engine.setSnapsToPaths(enabled)) return
        cancelRouting()
        commitLineAndRoute()
    }

    private fun selectRouteColoring(coloring: RouteColoring) {
        routeColoring = coloring
        publishAll()
        viewModelScope.launch {
            try {
                userSettingsRepository.setRouteColoring(coloring)
            } catch (_: IOException) {
                // Keep UI responsive; settings store surfaces its own persistence state.
            }
        }
    }

    private fun openStopSearch(target: TrailStopSearchTarget) {
        if (!canMutateDraft) return
        selectPlace(null)
        cancelMyLocationLookup()
        locationFeed.refreshPermissionStatus()
        val prefill = when (target) {
            is TrailStopSearchTarget.Existing ->
                engine.waypoints
                    .firstOrNull { it.id == target.waypointId }
                    ?.name
                    .orEmpty()
            is TrailStopSearchTarget.Open, TrailStopSearchTarget.NewStop -> ""
        }
        val permission = locationFeed.permissionStatus.value
        val denied = permission == LocationPermissionStatus.DENIED
        val cached = if (denied) null else locationFeed.lastKnownCoordinate()
        myLocationCoordinate = cached
        stopSearchState = TrailStopSearchUiState(
            target = target,
            query = prefill,
            prefill = prefill,
            recents = recentStops,
            myLocationAvailable = cached != null,
            myLocationDenied = denied
        )
        publishUiState()
        if (!denied) {
            startMyLocationLookup(needsOneShot = cached == null)
        }
    }

    private fun startMyLocationLookup(needsOneShot: Boolean) {
        myLocationJob = viewModelScope.launch {
            launch {
                locationFeed.fixes.collect { fix ->
                    val coord = runCatching { GeoCoordinate(fix.latitude, fix.longitude) }.getOrNull() ?: return@collect
                    publishResolvedMyLocation(coord)
                }
            }
            if (needsOneShot) {
                val fresh = locationFeed.requestCurrentCoordinate()
                if (fresh != null) {
                    publishResolvedMyLocation(fresh)
                } else if (locationFeed.permissionStatus.value == LocationPermissionStatus.DENIED) {
                    val active = stopSearchState ?: return@launch
                    stopSearchState = active.copy(myLocationAvailable = false, myLocationDenied = true)
                    publishUiState()
                }
            }
        }
    }

    private fun publishResolvedMyLocation(coordinate: GeoCoordinate) {
        myLocationCoordinate = coordinate
        val active = stopSearchState ?: return
        if (!active.myLocationAvailable || active.myLocationDenied) {
            stopSearchState = active.copy(myLocationAvailable = true, myLocationDenied = false)
            publishUiState()
        }
    }

    private fun cancelMyLocationLookup() {
        myLocationJob?.cancel()
        myLocationJob = null
    }

    private fun closeStopSearch() {
        searchJob?.cancel()
        searchJob = null
        cancelMyLocationLookup()
        stopSearchState = null
        publishUiState()
    }

    private fun updateSearchQuery(query: String) {
        val current = stopSearchState.takeIf { canMutateDraft } ?: return
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty() || query == current.prefill) {
            stopSearchState = current.copy(query = query, results = emptyList(), isSearching = false, didFail = false)
            publishUiState()
            return
        }
        stopSearchState = current.copy(query = query, isSearching = true, didFail = false)
        publishUiState()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val answer = stopPlaceSearchSource.search(trimmed)
            val active = stopSearchState
            if (active != null && active.query == query) {
                stopSearchState = answer.fold(
                    onSuccess = { list -> active.copy(results = list, isSearching = false, didFail = list.isEmpty()) },
                    onFailure = { active.copy(results = emptyList(), isSearching = false, didFail = true) }
                )
                publishUiState()
            }
        }
    }

    private fun pickMyLocation() {
        if (!canMutateDraft) return
        val here = myLocationCoordinate ?: locationFeed.lastKnownCoordinate() ?: return
        applySearchPick(coordinate = here, name = "", subtitle = "", recordRecent = false)
    }

    private fun applySearchPick(
        coordinate: GeoCoordinate,
        name: String,
        subtitle: String,
        recordRecent: Boolean = true
    ) {
        if (!canMutateDraft) return
        val target = stopSearchState?.target ?: return
        val boundedName = HikeTitle.bounded(name).orEmpty()
        when (target) {
            is TrailStopSearchTarget.Open -> engine.fill(target.role, coordinate, boundedName)
            is TrailStopSearchTarget.Existing -> engine.place(target.waypointId, coordinate, boundedName)
            TrailStopSearchTarget.NewStop -> {
                if (!engine.fillOpenField(coordinate, boundedName)) {
                    engine.append(coordinate, boundedName)
                }
            }
        }
        if (recordRecent && boundedName.isNotEmpty()) {
            viewModelScope.launch {
                trailDraftRepository.recordRecentStop(
                    TrailStopRecent(
                        name = boundedName,
                        subtitle = subtitle,
                        latitude = coordinate.latitude,
                        longitude = coordinate.longitude
                    )
                )
            }
        }
        stopSearchState = null
        searchJob?.cancel()
        cancelMyLocationLookup()
        commitLineAndRoute()
        if (target.landsOnAnEnd && engine.coordinates.size > 1) {
            frameCoordinates(engine.coordinates)
        } else {
            mapClaimCoordinator.requestCameraCommand(
                routeKey = TrailMakerRoute,
                command = MapCameraCommand.MoveTo(
                    id = mapClaimCoordinator.nextCommandId(),
                    center = coordinate,
                    zoom = PICKED_STOP_ZOOM
                )
            )
        }
    }

    private fun removeRecentStop(id: String) {
        if (!canMutateDraft) return
        viewModelScope.launch {
            trailDraftRepository.removeRecentStop(id)
        }
    }

    private fun removeStop(waypointId: String) {
        if (!canMutateDraft || !engine.removeById(waypointId)) return
        if (selection == TrailDraftSelection.Stop(waypointId)) selectPlace(null)
        commitLineAndRoute()
    }

    private fun moveStop(waypointId: String, delta: Int) {
        val currentIndex = if (canMutateDraft) engine.waypoints.indexOfFirst { it.id == waypointId } else -1
        if (currentIndex < 0) return
        val targetIndex = (currentIndex + delta).coerceIn(0, engine.waypoints.lastIndex)
        if (engine.moveWaypoint(currentIndex, targetIndex)) {
            commitLineAndRoute()
        }
    }

    private fun selectPlace(next: TrailDraftSelection?) {
        selection = next
        placeCardAddress = null
        placeCardAddressJob?.cancel()
        val coordinate = when (next) {
            TrailDraftSelection.DroppedPin -> droppedPin?.coordinate
            is TrailDraftSelection.Stop -> engine.waypoints.firstOrNull { it.id == next.waypointId }?.coordinate
            null -> null
        }
        publishAll()
        if (coordinate != null) {
            placeCardAddressJob = viewModelScope.launch {
                val resolved = stopPlaceSearchSource.reverseAddress(coordinate)
                if (selection == next) {
                    placeCardAddress = resolved
                    publishUiState()
                }
            }
        }
    }

    private fun addStopFromPlaceCard() {
        if (!canMutateDraft) return
        val spot = droppedPin ?: return
        selection = null
        droppedPin = null
        engine.addStop(
            coordinate = spot.coordinate,
            name = HikeTitle.bounded(spot.name).orEmpty(),
            preferredLegEnds = spot.legEnds
        )
        commitLineAndRoute()
    }

    private fun removeDroppedPin() {
        if (!canMutateDraft) return
        if (selection == TrailDraftSelection.DroppedPin) selection = null
        droppedPin = null
        publishAll()
    }

    private fun openSaveDialog() {
        if (!canMutateDraft) return
        val now = clock.nowEpochMillis()
        namingStartedAtEpochMillis = now
        nameDraft = ""
        showNameDialog = true
        publishUiState()
    }

    private fun discardAndClose(onClose: () -> Unit) {
        viewModelScope.launch {
            clearDraftStateAndAwait()
            onClose()
        }
    }

    private suspend fun clearDraftStateAndAwait() {
        cancelRouting()
        cancelNaming()
        shadingController.reset()
        cancelMyLocationLookup()
        droppedPin = null
        selection = null
        placeCardAddress = null
        stopSearchState = null
        engine.clear()
        trailDraftOverlayState.clearOverlay()
        publishUiState()
        draftWriteCoordinator.writeAndAwait(trailDraftRepository, StoredTrailDraft.Empty)
    }

    private fun commitLineAndRoute() {
        persistDraft()
        resolveLegs(retryingRefusals = false)
        publishAll()
        enqueueUnnamedStops()
    }

    private fun persistDraft() {
        draftWriteCoordinator.enqueueWrite(trailDraftRepository, engine.toStoredDraft())
    }

    private fun resolveLegs(retryingRefusals: Boolean) {
        backgroundQueues.resolveLegs(retryingRefusals)
    }

    private fun cancelRouting() {
        backgroundQueues.cancelRouting()
    }

    private fun enqueueUnnamedStops() {
        backgroundQueues.enqueueUnnamedStops()
    }

    private fun cancelNaming() {
        backgroundQueues.cancelNaming()
    }

    private fun frameCoordinates(coordinates: List<GeoCoordinate>) {
        val bounds = GeoBoundingBox.from(coordinates) ?: return
        mapClaimCoordinator.requestCameraCommand(
            routeKey = TrailMakerRoute,
            command = MapCameraCommand.FitBounds(
                id = mapClaimCoordinator.nextCommandId(),
                bounds = bounds
            )
        )
    }

    private fun publishAll() {
        shadingController.refresh()
        publishOverlay()
        publishUiState()
    }

    private fun publishOverlay() {
        trailDraftOverlayState.show(
            TrailMakerOverlayBuilder.build(
                engine = engine,
                droppedPin = droppedPin,
                shadedStretches = shadingController.activeStretches(routeColoring)
            )
        )
    }

    private fun publishUiState() {
        uiStateFlow.value = TrailMakerUiStateMapper.map(
            TrailMakerUiProjectionInput(
                engine = engine,
                distanceUnit = distanceUnit,
                routeColoring = routeColoring,
                hasOfferedDifficultyShading = shadingController.hasOffered,
                canSnapToPaths = trailLegRouter.supportsMode(engine.travelMode),
                isRestoring = isRestoring,
                isSaving = isSaving,
                showCloseDialog = showCloseDialog,
                showNameDialog = showNameDialog,
                namingStartedAtEpochMillis = namingStartedAtEpochMillis,
                nameDraft = nameDraft,
                refusal = refusal,
                stopSearch = stopSearchState,
                droppedPin = droppedPin,
                selection = selection,
                placeCardAddress = placeCardAddress
            )
        )
    }

    private fun cancelShading() {
        shadingController.cancel()
    }

    private companion object {
        const val INITIAL_CLAIM_DEPTH = 1
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PICKED_STOP_ZOOM = 14.0
    }
}
