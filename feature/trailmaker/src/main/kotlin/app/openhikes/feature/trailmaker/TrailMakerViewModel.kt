package app.openhikes.feature.trailmaker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.StopPlaceSearchSource
import app.openhikes.core.data.TrailDraftRepository
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
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailDraftDroppedPin
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailDraftRefusal
import app.openhikes.core.model.TrailDraftSelection
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegRouter
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.ui.navigation.TrailMakerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
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
    private val stopPlaceSearchSource: StopPlaceSearchSource,
    private val locationFeed: LocationFeed,
    private val clock: OpenHikesClock,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val trailDraftOverlayState: TrailDraftOverlayState,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {
    private val engine = TrailDraftEngine()
    private val uiStateFlow = MutableStateFlow(TrailMakerUiState())
    val uiState: StateFlow<TrailMakerUiState> = uiStateFlow.asStateFlow()

    private var distanceUnit: DistanceUnit = DistanceUnit.METRIC
    private var recentStops: List<TrailStopRecent> = emptyList()
    private var droppedPin: TrailDraftDroppedPin? = null
    private var selection: TrailDraftSelection? = null
    private var placeCardAddress: String? = null
    private var placeCardAddressJob: Job? = null

    private var showCloseDialog: Boolean = false
    private var showNameDialog: Boolean = false
    private var namingStartedAtEpochMillis: Long? = null
    private var nameDraft: String = ""
    private var isSaving: Boolean = false
    private var refusal: TrailDraftRefusal? = null
    private var stopSearchState: TrailStopSearchUiState? = null
    private var searchJob: Job? = null
    private var hasFramedDrawing: Boolean = false
    private var latestDraftWriteJob: Job? = null
    private val backgroundQueues = TrailDraftBackgroundQueues(
        scope = viewModelScope,
        engine = engine,
        trailLegRouter = trailLegRouter,
        stopPlaceSearchSource = stopPlaceSearchSource,
        onRoutingUpdate = ::publishAll,
        onStopNamed = {
            persistDraft()
            publishAll()
        }
    )

    private val mapInteractionHandler = object : TrailDraftMapInteractionHandler {
        override fun onDropPin(coordinate: GeoCoordinate, legEnds: TrailLegEnds?) {
            droppedPin = TrailDraftDroppedPin(coordinate = coordinate, legEnds = legEnds)
            selectPlace(TrailDraftSelection.DroppedPin)
        }

        override fun onTapStopPin(waypointId: String) {
            if (engine.waypoints.any { it.id == waypointId }) {
                selectPlace(TrailDraftSelection.Stop(waypointId))
            }
        }

        override fun onTapDroppedPin() {
            if (droppedPin != null) {
                selectPlace(TrailDraftSelection.DroppedPin)
            }
        }

        override fun onChooseAlternative(legIndex: Int, alternativeIndex: Int) {
            if (engine.chooseAlternative(legIndex, alternativeIndex)) {
                persistDraft()
                publishAll()
            }
        }

        override fun onBeginPinDrag(index: Int): Boolean {
            val started = engine.beginDrag(index)
            if (started) publishOverlay()
            return started
        }

        override fun onMovePinDrag(coordinate: GeoCoordinate) {
            if (engine.moveDrag(coordinate)) publishOverlay()
        }

        override fun onEndPinDrag() {
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
        observeSettingsAndRecents()
        restoreInitialDraft()
    }

    override fun onCleared() {
        cancelRouting()
        cancelNaming()
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
            TrailMakerAction.RetryRefusedLegs -> resolveLegs(retryingRefusals = true)
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
                selectPlace(null)
                removeStop(action.waypointId)
            }
            TrailMakerAction.DismissCloseDialog -> {
                showCloseDialog = false
                publishUiState()
            }
            TrailMakerAction.OpenSaveDialog -> openSaveDialog()
            is TrailMakerAction.UpdateNameDraft -> {
                nameDraft = action.value
                publishUiState()
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
        if (engine.isEmpty) {
            discardAndClose(onClose)
        } else {
            showCloseDialog = true
            publishUiState()
        }
    }

    fun onDiscardTrail(onClose: () -> Unit) {
        showCloseDialog = false
        discardAndClose(onClose)
    }

    fun onKeepForLater(onClose: () -> Unit) {
        showCloseDialog = false
        publishUiState()
        viewModelScope.launch {
            latestDraftWriteJob?.join()
            onClose()
        }
    }

    fun onConfirmSave(onSaved: (HikeId) -> Unit) {
        if (isSaving) return
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
        viewModelScope.launch {
            isSaving = true
            publishUiState()
            val result = hikeRepository.saveDrawnTrail(
                title = titleInput,
                distanceMeters = distanceMeters,
                route = route,
                madeOnEpochMillis = madeOn
            )
            isSaving = false
            result.fold(
                onSuccess = { hike ->
                    nameDraft = ""
                    namingStartedAtEpochMillis = null
                    clearDraftStateAndAwait()
                    onSaved(hike.id)
                },
                onFailure = {
                    refusal = TrailDraftRefusal.NOT_SAVED
                    publishUiState()
                }
            )
        }
    }

    private fun observeSettingsAndRecents() {
        viewModelScope.launch {
            userSettingsRepository.userSettings
                .catch { error -> if (error !is IOException) throw error }
                .collect { settings ->
                    distanceUnit = settings.distanceUnit
                    publishUiState()
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
            val stored = trailDraftRepository.loadDraft()
            if (!stored.isEmpty) {
                engine.replace(stored)
                if (!hasFramedDrawing && engine.coordinates.isNotEmpty()) {
                    hasFramedDrawing = true
                    frameCoordinates(engine.coordinates)
                }
            }
            publishAll()
            resolveLegs(retryingRefusals = false)
            enqueueUnnamedStops()
        }
    }

    private fun setTravelMode(mode: TrailTravelMode) {
        if (!engine.setTravelMode(mode)) return
        cancelRouting()
        commitLineAndRoute()
    }

    private fun setSnapsToPaths(enabled: Boolean) {
        if (!engine.setSnapsToPaths(enabled)) return
        cancelRouting()
        commitLineAndRoute()
    }

    private fun openStopSearch(target: TrailStopSearchTarget) {
        selectPlace(null)
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
        val hasFix = locationFeed.lastKnownCoordinate() != null
        stopSearchState = TrailStopSearchUiState(
            target = target,
            query = prefill,
            prefill = prefill,
            recents = recentStops,
            myLocationAvailable = hasFix,
            myLocationDenied = permission == LocationPermissionStatus.DENIED
        )
        publishUiState()
    }

    private fun closeStopSearch() {
        searchJob?.cancel()
        searchJob = null
        stopSearchState = null
        publishUiState()
    }

    private fun updateSearchQuery(query: String) {
        val current = stopSearchState ?: return
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
            val active = stopSearchState ?: return@launch
            if (active.query != query) return@launch
            stopSearchState = answer.fold(
                onSuccess = { list -> active.copy(results = list, isSearching = false, didFail = list.isEmpty()) },
                onFailure = { active.copy(results = emptyList(), isSearching = false, didFail = true) }
            )
            publishUiState()
        }
    }

    private fun pickMyLocation() {
        val here = locationFeed.lastKnownCoordinate() ?: return
        applySearchPick(coordinate = here, name = "", subtitle = "", recordRecent = false)
    }

    private fun applySearchPick(
        coordinate: GeoCoordinate,
        name: String,
        subtitle: String,
        recordRecent: Boolean = true
    ) {
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
        viewModelScope.launch {
            trailDraftRepository.removeRecentStop(id)
        }
    }

    private fun removeStop(waypointId: String) {
        if (!engine.removeById(waypointId)) return
        if (selection == TrailDraftSelection.Stop(waypointId)) selectPlace(null)
        commitLineAndRoute()
    }

    private fun moveStop(waypointId: String, delta: Int) {
        val currentIndex = engine.waypoints.indexOfFirst { it.id == waypointId }
        if (currentIndex < 0) return
        val targetIndex = (currentIndex + delta).coerceIn(0, engine.waypoints.lastIndex)
        if (!engine.moveWaypoint(currentIndex, targetIndex)) return
        commitLineAndRoute()
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
        if (selection == TrailDraftSelection.DroppedPin) selection = null
        droppedPin = null
        publishAll()
    }

    private fun openSaveDialog() {
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
        droppedPin = null
        selection = null
        placeCardAddress = null
        stopSearchState = null
        engine.clear()
        trailDraftOverlayState.clearOverlay()
        publishUiState()
        enqueueDraftWrite(StoredTrailDraft.Empty).join()
    }

    private fun commitLineAndRoute() {
        persistDraft()
        publishAll()
        resolveLegs(retryingRefusals = false)
        enqueueUnnamedStops()
    }

    private fun persistDraft() {
        enqueueDraftWrite(engine.toStoredDraft())
    }

    private fun enqueueDraftWrite(snapshot: StoredTrailDraft): Job {
        val previous = latestDraftWriteJob
        val job = applicationScope.launch {
            previous?.join()
            if (snapshot.isEmpty) {
                trailDraftRepository.clearDraft()
            } else {
                trailDraftRepository.saveDraft(snapshot)
            }
        }
        latestDraftWriteJob = job
        return job
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
        publishOverlay()
        publishUiState()
    }

    private fun publishOverlay() {
        trailDraftOverlayState.show(TrailMakerOverlayBuilder.build(engine, droppedPin))
    }

    private fun publishUiState() {
        uiStateFlow.value = TrailMakerUiStateMapper.map(
            TrailMakerUiProjectionInput(
                engine = engine,
                distanceUnit = distanceUnit,
                canSnapToPaths = trailLegRouter.supportsMode(engine.travelMode),
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

    private companion object {
        const val INITIAL_CLAIM_DEPTH = 1
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PICKED_STOP_ZOOM = 14.0
    }
}
