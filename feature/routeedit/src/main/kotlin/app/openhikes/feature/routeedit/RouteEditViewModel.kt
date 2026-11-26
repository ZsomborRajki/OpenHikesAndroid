package app.openhikes.feature.routeedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.TrailWalkController
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RoutePath
import app.openhikes.core.model.RouteStep
import app.openhikes.core.model.RouteSteps
import app.openhikes.core.model.RouteStretchRouter
import app.openhikes.core.model.RouteTrim
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.model.RouteTrimLine
import app.openhikes.core.ui.navigation.RouteEditRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scoped ViewModel for [RouteEditRoute] owning off-main route measurement, handle drags,
 * stretch and whole-route steps, undo, atomic save/revert, and live map overlay publication.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = RouteEditViewModel.Factory::class)
class RouteEditViewModel @AssistedInject constructor(
    @Assisted rawHikeId: String,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val trailWalkController: TrailWalkController,
    private val routeStretchRouter: RouteStretchRouter,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val routeEditOverlayState: RouteEditOverlayState,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawHikeId: String): RouteEditViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = RouteEditRoute(hikeId)
    private val session = RouteEditSession(hikeId)
    private var distanceUnit: DistanceUnit = DistanceUnit.METRIC
    private var hasAutoFitted: Boolean = false

    private val uiStateFlow = MutableStateFlow(RouteEditUiState(hikeId = hikeId))
    private val rangeStateFlow = MutableStateFlow(RouteTrimRangeState())

    val uiState: StateFlow<RouteEditUiState> = uiStateFlow.asStateFlow()
    val rangeState: StateFlow<RouteTrimRangeState> = rangeStateFlow.asStateFlow()

    private val mapInteractionHandler = object : RouteEditMapInteractionHandler {
        override fun onDragHandle(handle: RouteTrimHandle, towards: GeoCoordinate) {
            if (session.moveHandleTowards(handle, towards)) {
                publishRangeAndOverlay()
                publishCoarseState()
            }
        }

        override fun onAdjustHandle(handle: RouteTrimHandle, direction: Int) {
            if (session.adjustHandle(handle, direction)) {
                publishRangeAndOverlay()
                publishCoarseState()
            }
        }

        override fun onBeginPull(coordinate: GeoCoordinate, reachMeters: Double): Boolean {
            val started = session.beginPull(coordinate, reachMeters)
            if (started) publishOverlay()
            return started
        }

        override fun onMovePull(coordinate: GeoCoordinate) {
            if (session.movePull(coordinate)) publishOverlay()
        }

        override fun onEndPull(coordinate: GeoCoordinate, movedEnough: Boolean) {
            val pull = session.activePull
            if (pull == null || !movedEnough) {
                if (session.cancelPull()) publishOverlay()
                return
            }
            reroutePull(pull, coordinate)
        }

        override fun onCancelPull() {
            if (session.cancelPull()) publishOverlay()
        }
    }

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = routeKey,
                depth = INITIAL_CLAIM_DEPTH,
                selectedHikeId = hikeId,
                hiddenRouteHikeId = hikeId
            )
        )
        routeEditOverlayState.bindHandler(hikeId, mapInteractionHandler)
        viewModelScope.launch {
            userSettingsRepository.userSettings
                .catch { error -> if (error !is IOException) throw error }
                .collect { settings ->
                    distanceUnit = settings.distanceUnit
                    publishCoarseState()
                }
        }
        loadInitial()
    }

    override fun onCleared() {
        routeEditOverlayState.clearIfHike(hikeId)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onAction(action: RouteEditAction) {
        when (action) {
            is RouteEditAction.MoveHandle -> moveTrimHandle(action.isStart, action.meters)
            is RouteEditAction.AdjustHandle -> adjustTrimHandle(action.isStart, action.direction)
            RouteEditAction.RemoveCarriedStart -> applyCarriedOffer(RouteTrimHandle.START)
            RouteEditAction.RemoveCarriedEnd -> applyCarriedOffer(RouteTrimHandle.END)
            RouteEditAction.RequestRevert -> updateDialogState(showRevert = true)
            RouteEditAction.DismissRevert -> updateDialogState(showRevert = false)
            RouteEditAction.DismissRefusal -> updateDialogState(clearRefusal = true)
            else -> onStepAction(action)
        }
    }

    private fun onStepAction(action: RouteEditAction) {
        when (action) {
            RouteEditAction.BeginStretch -> beginStretch()
            RouteEditAction.EndStretch -> endStretch()
            RouteEditAction.FollowTrails -> followTrailsAcrossStretch()
            RouteEditAction.StraightenStretch -> straightenStretch()
            RouteEditAction.RemoveStretch -> takeOutStretch()
            RouteEditAction.ReverseRoute -> makeWholeStep(RouteStep.Reverse)
            RouteEditAction.OutAndBack -> if (!uiStateFlow.value.keptEndsMeet) makeWholeStep(RouteStep.OutAndBack)
            RouteEditAction.CloseLoop -> closeLoop()
            RouteEditAction.StartLoopHere -> startLoopAtCut()
            RouteEditAction.UndoStep -> undoLastStep()
            else -> Unit
        }
    }

    private fun moveTrimHandle(isStart: Boolean, meters: Double) {
        val handle = if (isStart) RouteTrimHandle.START else RouteTrimHandle.END
        if (session.moveHandle(handle, meters)) {
            publishRangeAndOverlay()
            publishCoarseState()
        }
    }

    private fun adjustTrimHandle(isStart: Boolean, direction: Int) {
        val handle = if (isStart) RouteTrimHandle.START else RouteTrimHandle.END
        if (session.adjustHandle(handle, direction)) {
            publishRangeAndOverlay()
            publishCoarseState()
        }
    }

    private fun updateDialogState(showRevert: Boolean? = null, clearRefusal: Boolean = false) {
        if (showRevert != null) session.showRevertDialog = showRevert
        if (clearRefusal) session.refusal = null
        publishCoarseState()
    }

    fun onSave(onDone: () -> Unit) {
        if (session.isSaving || !session.canMakeSteps) return
        viewModelScope.launch {
            session.isSaving = true
            publishCoarseState()
            try {
                val loaded = session.sources ?: return@launch
                val edited = withContext(defaultDispatcher) { session.buildEditedLine() } ?: return@launch
                val outcome = if (
                    edited.route == loaded.saved &&
                    edited.steps.isEmpty() &&
                    loaded.edit?.original != null
                ) {
                    hikeRepository.revertRouteEdit(hikeId, expectedCurrentRoute = loaded.current)
                } else {
                    hikeRepository.applyRouteEdit(hikeId, expectedCurrentRoute = loaded.current, edited = edited)
                }
                handleSaveOutcome(outcome, onDone)
            } finally {
                session.isSaving = false
                publishCoarseState()
            }
        }
    }

    fun onConfirmRevert(onDone: () -> Unit) {
        session.showRevertDialog = false
        if (session.isSaving) return
        val loaded = session.sources ?: return
        viewModelScope.launch {
            session.isSaving = true
            publishCoarseState()
            try {
                val outcome = hikeRepository.revertRouteEdit(hikeId, expectedCurrentRoute = loaded.current)
                handleSaveOutcome(outcome, onDone)
            } finally {
                session.isSaving = false
                publishCoarseState()
            }
        }
    }

    private fun handleSaveOutcome(outcome: RouteEditOutcome, onDone: () -> Unit) {
        when (outcome) {
            is RouteEditOutcome.Refused -> {
                session.refusal = outcome.refusal
                publishCoarseState()
            }
            is RouteEditOutcome.Saved -> {
                trailWalkController.onHikeRouteChanged(hikeId, outcome.route)
                routeEditOverlayState.clearIfHike(hikeId)
                onDone()
            }
        }
    }

    private fun loadInitial() {
        viewModelScope.launch {
            val hike = hikeRepository.observeHike(hikeId).first()
            val sources = hikeRepository.loadRouteEditSources(hikeId)
            if (hike == null || sources == null || sources.current.size <= 1) {
                session.refusal = RouteEditRefusal.LINE_CHANGED
                publishCoarseState()
                return@launch
            }
            if (sources.isMissingOriginal || sources.isUnreadable) {
                session.refusal = RouteEditRefusal.NOT_HERE_YET
                publishCoarseState()
                return@launch
            }
            val (measured, junctions) = withContext(defaultDispatcher) {
                val line = RouteTrimLine.measure(sources, steps = sources.edit?.steps.orEmpty())
                line to RouteEditSession.detectJunctionDistances(line)
            }
            session.applyLoaded(hike, sources, measured, junctions)
            publishAll()
            if (!hasAutoFitted) {
                measured.profile.boundingBox?.let { bounds ->
                    hasAutoFitted = true
                    mapClaimCoordinator.requestCameraCommand(
                        routeKey = routeKey,
                        command = MapCameraCommand.FitBounds(
                            id = mapClaimCoordinator.nextCommandId(),
                            bounds = bounds
                        )
                    )
                }
            }
        }
    }

    private fun applyCarriedOffer(handle: RouteTrimHandle) {
        if (session.takeOffCarriedEnd(handle)) {
            publishRangeAndOverlay()
            publishCoarseState()
        }
    }

    private fun beginStretch() {
        if (session.line == null || !session.canMakeSteps || session.isSelectingStretch) return
        holdingSteps {
            val cut = session.trimRange
            val nextSteps = if (cut != null) {
                session.steps + RouteStep.Trim(cut.start, cut.endInclusive, session.pausesTakenOff.rawValue)
            } else {
                session.steps
            }
            redrawWith(nextSteps, selectingStretch = true, onlyForStretch = cut != null)
        }
    }

    private fun endStretch() {
        if (!session.isSelectingStretch || session.isSaving) return
        holdingSteps {
            val last = session.steps.lastOrNull()
            if (session.trimStepIsOnlyForTheStretch && last is RouteStep.Trim) {
                val cuts = EditedLine.Cuts(
                    range = last.startMeters..last.endMeters,
                    takingOff = RouteTrim.EndPauses(last.endPauses)
                )
                redrawWith(session.steps.dropLast(1), cuts = cuts)
            } else {
                redrawWith(session.steps)
            }
        }
    }

    private fun straightenStretch() {
        if (!session.isSelectingStretch || !session.canMakeSteps) return
        holdingSteps {
            applyNewStep(RouteStep.Replace(session.startMeters, session.endMeters, RoutePath.STRAIGHT))
        }
    }

    private fun takeOutStretch() {
        if (!session.isSelectingStretch || !session.canMakeSteps || uiStateFlow.value.picksWholeLine) return
        holdingSteps {
            applyNewStep(RouteStep.Pause(session.startMeters, session.endMeters))
        }
    }

    private fun followTrailsAcrossStretch() {
        val from = session.spotOf(RouteTrimHandle.START)
        val to = session.spotOf(RouteTrimHandle.END)
        val canFollow = routeStretchRouter.supportsMappedRouting && session.isSelectingStretch && session.canMakeSteps
        if (!canFollow || from == null || to == null) return
        holdingSteps {
            val asked = session.stepGround
            session.isRouting = true
            publishCoarseState()
            val way = routeStretchRouter.routeWay(from, to)
            session.isRouting = false
            publishCoarseState()
            if (way == null || session.stepGround != asked) return@holdingSteps
            if (!way.isMapped) {
                session.refusal = RouteEditRefusal.NO_MAPPED_WAY
                publishCoarseState()
                return@holdingSteps
            }
            val path = RoutePath.fromCoordinates(way.coordinates)
            applyNewStep(RouteStep.Replace(asked.startMeters, asked.endMeters, path), expectedGround = asked)
        }
    }

    private fun makeWholeStep(step: RouteStep) {
        if (!session.canMakeSteps || session.isSelectingStretch) return
        holdingSteps { applyNewStep(step) }
    }

    private fun closeLoop() {
        val from = session.spotOf(RouteTrimHandle.END)
        val to = session.spotOf(RouteTrimHandle.START)
        val canClose = session.canMakeSteps && !session.isSelectingStretch && !uiStateFlow.value.keptEndsMeet
        if (!canClose || from == null || to == null) return
        holdingSteps {
            val asked = session.stepGround
            session.isRouting = true
            publishCoarseState()
            val way = routeStretchRouter.routeWay(from, to)
            session.isRouting = false
            publishCoarseState()
            val path = if (way != null && way.isMapped) {
                RoutePath.fromCoordinates(way.coordinates)
            } else {
                RoutePath.STRAIGHT
            }
            applyNewStep(RouteStep.CloseLoop(path), expectedGround = asked)
        }
    }

    private fun startLoopAtCut() {
        if (!uiStateFlow.value.canStartLoopAtCut || !session.canMakeSteps) return
        holdingSteps {
            redrawWith(session.steps + RouteStep.StartAt(session.startMeters), isNewStep = true)
        }
    }

    private fun undoLastStep() {
        if (!session.canUndo) return
        holdingSteps {
            val (nextSteps, cuts) = session.buildUndoTarget()
            redrawWith(nextSteps, cuts = cuts)
        }
    }

    private fun reroutePull(pull: RouteEditSession.PullState, via: GeoCoordinate) {
        val currentLine = session.line
        val canReroute = session.canMakeSteps && !session.isSelectingStretch && pull.ground == session.stepGround
        val isValidContext = currentLine != null && canReroute
        val from = if (isValidContext) currentLine.startSpot(pull.stretchStartMeters, takingOffPause = true) else null
        val to = if (isValidContext) currentLine.endSpot(pull.stretchEndMeters, takingOffPause = true) else null
        if (from == null || to == null) {
            if (session.cancelPull()) publishOverlay()
            return
        }
        val begins = if (session.cuts(RouteTrimHandle.START)) session.startMeters else 0.0
        val startOnKept = pull.stretchStartMeters - begins
        val endOnKept = pull.stretchEndMeters - begins
        holdingSteps {
            session.isRouting = true
            publishCoarseState()
            val way = routeStretchRouter.routeWay(from, to, through = via)
            session.isRouting = false
            publishCoarseState()
            val coords = if (way != null && way.coordinates.isNotEmpty()) way.coordinates else listOf(via)
            val landed = applyNewStep(
                RouteStep.Replace(startOnKept, endOnKept, RoutePath.fromCoordinates(coords)),
                expectedGround = pull.ground
            )
            if (!landed && session.cancelPull()) {
                publishOverlay()
            }
        }
    }

    private fun holdingSteps(block: suspend () -> Unit) {
        if (session.isMakingStep) return
        viewModelScope.launch {
            session.isMakingStep = true
            publishCoarseState()
            try {
                block()
            } finally {
                session.isMakingStep = false
                publishCoarseState()
            }
        }
    }

    private suspend fun applyNewStep(step: RouteStep, expectedGround: RouteEditSession.StepGround? = null): Boolean {
        val canApply = session.line != null &&
            !session.isSaving &&
            (expectedGround == null || expectedGround == session.stepGround)
        if (!canApply) return false
        val next = session.buildStepListWithPendingTrim(step)
        return if (RouteSteps.pathCoordinateCount(next) > RouteSteps.MAXIMUM_PATH_COORDINATES) {
            session.refusal = RouteEditRefusal.TOO_MUCH_TO_KEEP
            publishCoarseState()
            false
        } else {
            redrawWith(next, isNewStep = true)
        }
    }

    private suspend fun redrawWith(
        nextSteps: List<RouteStep>,
        cuts: EditedLine.Cuts = EditedLine.Cuts(),
        selectingStretch: Boolean = false,
        onlyForStretch: Boolean = false,
        isNewStep: Boolean = false
    ): Boolean {
        val loaded = session.sources
        val before = session.line
        if (loaded == null || before == null) return false
        val (after, junctions) = withContext(defaultDispatcher) {
            val measured = RouteTrimLine.measure(loaded, steps = nextSteps)
            measured to RouteEditSession.detectJunctionDistances(measured)
        }
        val noOpNewStep = isNewStep && !after.lastStepChangedLine
        val sameRoute = after.route == before.route &&
            nextSteps.size >= session.steps.size &&
            selectingStretch == session.isSelectingStretch
        val unchanged = noOpNewStep || sameRoute
        if (!unchanged) {
            session.applyRedrawn(
                nextSteps = nextSteps,
                measured = after,
                junctions = junctions,
                cuts = cuts,
                selectingStretch = selectingStretch,
                onlyForStretch = onlyForStretch
            )
            publishAll()
        }
        return !unchanged
    }

    private fun publishAll() {
        publishCoarseState()
        publishRangeAndOverlay()
    }

    private fun publishCoarseState() {
        uiStateFlow.value = session.snapshotUiState(
            distanceUnit = distanceUnit,
            canFollowTrails = routeStretchRouter.supportsMappedRouting
        )
    }

    private fun publishRangeAndOverlay() {
        rangeStateFlow.value = session.snapshotRangeState()
        publishOverlay()
    }

    private fun publishOverlay() {
        session.buildMapOverlay()?.let { routeEditOverlayState.show(it) }
    }

    private companion object {
        const val INITIAL_CLAIM_DEPTH = 2
    }
}
