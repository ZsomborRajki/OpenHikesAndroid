package app.openhikes.feature.routeedit

import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCarriedEnds
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteShape
import app.openhikes.core.model.RouteStep
import app.openhikes.core.model.RouteSteps
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.RouteTrim
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.model.RouteTrimLine
import app.openhikes.core.model.RouteTurnJunctionDetector
import app.openhikes.core.model.toTrailRuns
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stateful in-memory session driving one Edit Route visit, isolating pure geometry/step logic
 * and map overlay slicing from Hilt/Compose ViewModel plumbing.
 */
@Suppress("TooManyFunctions")
internal class RouteEditSession(private val hikeId: HikeId) {
    data class StepGround(
        val revision: Int,
        val startMeters: Double,
        val endMeters: Double,
        val pausesTakenOff: RouteTrim.EndPauses,
        val isSelectingStretch: Boolean
    )

    data class PullState(
        val stretchStartMeters: Double,
        val stretchEndMeters: Double,
        val ground: StepGround,
        val coverCoordinates: List<GeoCoordinate>,
        val currentVia: GeoCoordinate,
        val coverSegments: List<List<GeoCoordinate>> =
            if (coverCoordinates.size >= 2) listOf(coverCoordinates) else emptyList()
    )

    var sources: RouteEditSources? = null
        private set
    var line: RouteTrimLine? = null
        private set
    var lineRevision: Int = 0
        private set
    var hikeDistanceMeters: Double = 0.0
        private set
    var tintHex: String = RouteTint.DEFAULT_HEX
        private set
    var borderHex: String = RouteBorder.NONE_HEX
        private set
    var routeWidthDp: Float = DEFAULT_ROUTE_WIDTH_DP
        private set
    var linePattern: RouteLinePattern = RouteLinePattern.Default
        private set
    var currentLengthMeters: Double = 0.0
        private set
    var currentRecordedSeconds: Double = 0.0
        private set

    var startMeters: Double = 0.0
        private set
    var endMeters: Double = 0.0
        private set
    var pausesTakenOff: RouteTrim.EndPauses = RouteTrim.EndPauses.NONE
        private set
    var steps: List<RouteStep> = emptyList()
        private set
    var isSelectingStretch: Boolean = false
        private set
    var trimStepIsOnlyForTheStretch: Boolean = false
        private set
    var cutWhenLoaded: Set<RouteTrimHandle> = emptySet()
        private set
    var junctionMeters: List<Double> = emptyList()
        private set
    var activePull: PullState? = null
        private set
    private var cachedBaseOverlay: MapRouteEditOverlay? = null

    var isRouting: Boolean = false
    var isMakingStep: Boolean = false
    var isSaving: Boolean = false
    var showRevertDialog: Boolean = false
    var refusal: RouteEditRefusal? = null

    val totalMeters: Double
        get() = line?.totalMeters ?: 0.0

    val canMakeSteps: Boolean
        get() = !isSaving && !isRouting && !isMakingStep

    val canUndo: Boolean
        get() = steps.isNotEmpty() && !isSelectingStretch && canMakeSteps

    val isTrimmed: Boolean
        get() = cuts(RouteTrimHandle.START) || cuts(RouteTrimHandle.END)

    val trimRange: ClosedFloatingPointRange<Double>?
        get() = if (isTrimmed) startMeters..max(startMeters, endMeters) else null

    val stepGround: StepGround
        get() = StepGround(
            revision = lineRevision,
            startMeters = startMeters,
            endMeters = endMeters,
            pausesTakenOff = pausesTakenOff,
            isSelectingStretch = isSelectingStretch
        )

    val hasChanges: Boolean
        get() {
            val loaded = sources ?: return false
            if (line == null || isSelectingStretch) return false
            val savedSteps = loaded.edit?.steps.orEmpty()
            val savedTrim = loaded.edit?.trimRange
            val savedPauses = loaded.edit?.endPausesTakenOff ?: RouteTrim.EndPauses.NONE
            return steps != savedSteps || !isSameTrim(trimRange, savedTrim) || pausesTakenOff != savedPauses
        }

    fun applyLoaded(hike: Hike, loadedSources: RouteEditSources, measured: RouteTrimLine, junctions: List<Double>) {
        sources = loadedSources
        line = measured
        hikeDistanceMeters = hike.distanceMeters
        tintHex = hike.tintHex
        borderHex = hike.routeBorderHex
        routeWidthDp = hike.routeWidthDp.toFloat()
        linePattern = hike.linePattern
        currentLengthMeters = RouteGeometry.routeLengthMeters(loadedSources.current)
        currentRecordedSeconds = EditedLine.recordedSeconds(
            whole = measured.route,
            cutTo = loadedSources.current,
            steps = EditedLine.Steps(recordedSeconds = measured.recordedSeconds)
        )
        steps = loadedSources.edit?.steps.orEmpty()
        isSelectingStretch = false
        trimStepIsOnlyForTheStretch = false
        activePull = null
        junctionMeters = junctions
        val storedTrim = loadedSources.edit?.trimRange
        startMeters = (storedTrim?.start ?: 0.0).coerceIn(0.0, measured.totalMeters)
        endMeters = (storedTrim?.endInclusive ?: measured.totalMeters).coerceIn(startMeters, measured.totalMeters)
        pausesTakenOff = loadedSources.edit?.endPausesTakenOff ?: RouteTrim.EndPauses.NONE
        cutWhenLoaded = buildSet {
            if (cuts(RouteTrimHandle.START)) add(RouteTrimHandle.START)
            if (cuts(RouteTrimHandle.END)) add(RouteTrimHandle.END)
            addAll(measured.endsMovedBySteps)
        }
        lineRevision += 1
        cachedBaseOverlay = null
    }

    fun applyRedrawn(
        nextSteps: List<RouteStep>,
        measured: RouteTrimLine,
        junctions: List<Double>,
        cuts: EditedLine.Cuts = EditedLine.Cuts(),
        selectingStretch: Boolean = false,
        onlyForStretch: Boolean = false
    ) {
        steps = nextSteps
        isSelectingStretch = selectingStretch
        trimStepIsOnlyForTheStretch = onlyForStretch
        activePull = null
        line = measured
        junctionMeters = junctions
        startMeters = (cuts.range?.start ?: 0.0).coerceIn(0.0, measured.totalMeters)
        endMeters = (cuts.range?.endInclusive ?: measured.totalMeters).coerceIn(startMeters, measured.totalMeters)
        pausesTakenOff = if (cuts.range == null) RouteTrim.EndPauses.NONE else cuts.takingOff
        if (selectingStretch && measured.totalMeters > 0.0) {
            startMeters = measured.totalMeters / THIRDS
            endMeters = measured.totalMeters * 2.0 / THIRDS
        }
        lineRevision += 1
        cachedBaseOverlay = null
    }

    fun cuts(handle: RouteTrimHandle): Boolean = when {
        isSelectingStretch -> false
        handle.endPause in pausesTakenOff -> true
        handle == RouteTrimHandle.START -> startMeters > 0.0
        else -> endMeters < totalMeters
    }

    fun positionOf(handle: RouteTrimHandle): Double = when (handle) {
        RouteTrimHandle.START -> startMeters
        RouteTrimHandle.END -> endMeters
    }

    fun moveHandle(handle: RouteTrimHandle, meters: Double): Boolean {
        val total = totalMeters
        if (total <= 0.0 || !meters.isFinite()) return false
        val gap = min(if (isSelectingStretch) MINIMUM_STRETCH_METERS else MINIMUM_KEPT_METERS, total)
        val before = positionOf(handle)
        when (handle) {
            RouteTrimHandle.START -> startMeters = meters.coerceIn(0.0, max(endMeters - gap, 0.0))
            RouteTrimHandle.END -> endMeters = meters.coerceIn(min(startMeters + gap, total), total)
        }
        val moved = positionOf(handle) != before
        if (moved) {
            if (handle.endPause in pausesTakenOff) {
                pausesTakenOff -= handle.endPause
            }
            cachedBaseOverlay = null
        }
        return moved
    }

    fun adjustHandle(handle: RouteTrimHandle, direction: Int): Boolean {
        val step = totalMeters * ACCESSIBILITY_STEP_FRACTION
        return moveHandle(handle, positionOf(handle) + direction * step)
    }

    fun moveHandleTowards(handle: RouteTrimHandle, coordinate: GeoCoordinate): Boolean {
        val meters = line?.distanceNearest(coordinate, aroundAnchorMeters = positionOf(handle)) ?: return false
        return moveHandle(handle, meters)
    }

    fun takeOffCarriedEnd(handle: RouteTrimHandle): Boolean {
        val ends = line?.carriedEnds
        val stretch = ends?.let { if (handle == RouteTrimHandle.START) it.leading else it.trailing }
            ?: return false
        val edge = innerEdge(stretch, handle)
        val moved = moveHandle(handle, edge)
        val onEnd = if (handle == RouteTrimHandle.START) edge <= 0.0 else edge >= totalMeters
        val addedPause = onEnd && handle.endPause !in pausesTakenOff
        if (addedPause) {
            pausesTakenOff += handle.endPause
            cachedBaseOverlay = null
        }
        return moved || addedPause
    }

    fun pullBounds(aroundMeters: Double, reachMeters: Double): ClosedFloatingPointRange<Double>? {
        val margin = MINIMUM_STRETCH_METERS / 2.0
        val keptStart = startMeters
        val keptEnd = max(startMeters, endMeters)
        val canPull = line != null && !isSelectingStretch && canMakeSteps
        val insideKept = aroundMeters > keptStart + margin && aroundMeters < keptEnd - margin
        if (!canPull || !insideKept) return null
        val before = junctionMeters.lastOrNull {
            it <= aroundMeters - margin && aroundMeters - it <= MAXIMUM_PULL_METERS
        }
        val after = junctionMeters.firstOrNull {
            it >= aroundMeters + margin && it - aroundMeters <= MAXIMUM_PULL_METERS
        }
        val lower = max(before ?: (aroundMeters - max(reachMeters, margin)), keptStart)
        val upper = min(after ?: (aroundMeters + max(reachMeters, margin)), keptEnd)
        return if (upper > lower) lower..upper else null
    }

    fun beginPull(coordinate: GeoCoordinate, reachMeters: Double): Boolean {
        val currentLine = line
        val canStart = currentLine != null && !isSelectingStretch && canMakeSteps
        val tapToleranceMeters = max(reachMeters * TAP_TO_REACH_RATIO, MIN_PULL_TAP_METERS)
        val bounds = if (canStart) {
            currentLine.distanceAt(coordinate, withinReachMeters = tapToleranceMeters)
                ?.let { along -> pullBounds(aroundMeters = along, reachMeters = reachMeters) }
        } else {
            null
        }
        val cover = bounds?.let { currentLine?.coordinatesBetween(it.start, it.endInclusive) }
        val accepted = bounds != null && cover != null && cover.size >= 2
        if (accepted) {
            activePull = PullState(
                stretchStartMeters = bounds.start,
                stretchEndMeters = bounds.endInclusive,
                ground = stepGround,
                coverCoordinates = cover,
                currentVia = coordinate
            )
        }
        return accepted
    }

    fun movePull(coordinate: GeoCoordinate): Boolean {
        val current = activePull ?: return false
        activePull = current.copy(currentVia = coordinate)
        return true
    }

    fun cancelPull(): Boolean {
        if (activePull == null) return false
        activePull = null
        return true
    }

    fun buildStepListWithPendingTrim(step: RouteStep): List<RouteStep> {
        val next = ArrayList<RouteStep>(steps.size + 2)
        next.addAll(steps)
        trimRange?.let { range ->
            next.add(RouteStep.Trim(range.start, range.endInclusive, pausesTakenOff.rawValue))
        }
        next.add(step)
        return next
    }

    fun buildUndoTarget(): Pair<List<RouteStep>, EditedLine.Cuts> {
        val next = steps.toMutableList()
        var cuts = EditedLine.Cuts()
        val popped = next.removeAt(next.lastIndex)
        if (popped is RouteStep.Trim) {
            cuts = EditedLine.Cuts(
                range = popped.startMeters..popped.endMeters,
                takingOff = RouteTrim.EndPauses(popped.endPauses)
            )
        } else {
            val tail = next.lastOrNull()
            if (tail is RouteStep.Trim) {
                next.removeAt(next.lastIndex)
                cuts = EditedLine.Cuts(
                    range = tail.startMeters..tail.endMeters,
                    takingOff = RouteTrim.EndPauses(tail.endPauses)
                )
            }
        }
        return next to cuts
    }

    fun buildEditedLine(): EditedLine? {
        val currentLine = line
        val loaded = sources
        val isSynced = currentLine != null && loaded != null && currentLine.steps == steps
        val ready = isSynced && hasChanges && !isSelectingStretch
        return if (!ready) {
            null
        } else {
            EditedLine.build(
                whole = currentLine.route,
                distances = currentLine.distances,
                cuts = EditedLine.Cuts(range = trimRange, takingOff = pausesTakenOff),
                replacing = loaded.current,
                cutBefore = cutWhenLoaded,
                steps = EditedLine.Steps(
                    made = currentLine.steps,
                    moving = currentLine.endsMovedBySteps,
                    recordedSeconds = currentLine.recordedSeconds,
                    clockedRoute = currentLine.clockedRoute
                ),
                recordedBefore = currentRecordedSeconds
            )
        }
    }

    fun spotOf(handle: RouteTrimHandle): GeoCoordinate? {
        val currentLine = line ?: return null
        val takesOffPause = isSelectingStretch || handle.endPause in pausesTakenOff
        return when (handle) {
            RouteTrimHandle.START -> currentLine.startSpot(startMeters, takingOffPause = takesOffPause)
            RouteTrimHandle.END -> currentLine.endSpot(endMeters, takingOffPause = takesOffPause)
        }
    }

    fun keptSpotOf(handle: RouteTrimHandle): GeoCoordinate? {
        val currentLine = line
        return when {
            currentLine == null -> null
            !isSelectingStretch -> spotOf(handle)
            handle == RouteTrimHandle.START -> currentLine.startSpot(0.0)
            else -> currentLine.endSpot(currentLine.totalMeters)
        }
    }

    fun snapshotUiState(distanceUnit: DistanceUnit, canFollowTrails: Boolean = false): RouteEditUiState {
        val currentLine = line
        val startSpot = keptSpotOf(RouteTrimHandle.START)
        val endSpot = keptSpotOf(RouteTrimHandle.END)
        val endsMeet = startSpot != null && endSpot != null && RouteSteps.endsMeet(startSpot, endSpot)
        val picksWhole = isSelectingStretch &&
            startMeters <= END_TOLERANCE_METERS &&
            endMeters >= totalMeters - END_TOLERANCE_METERS
        val isLoop = currentLine?.shape == RouteShape.LOOP
        val canStartLoopAtCut = isLoop &&
            !isSelectingStretch &&
            cuts(RouteTrimHandle.START) &&
            !cuts(RouteTrimHandle.END) &&
            pausesTakenOff.isEmpty
        val ends = currentLine?.carriedEnds ?: RouteCarriedEnds()
        val offersActive = !isSelectingStretch
        val leadingOffer = ends.leading?.takeIf { offersActive && !hasTakenOff(it, RouteTrimHandle.START) }
        val trailingOffer = ends.trailing?.takeIf { offersActive && !hasTakenOff(it, RouteTrimHandle.END) }

        return RouteEditUiState(
            hikeId = hikeId,
            isLoading = currentLine == null && refusal == null,
            tintHex = tintHex,
            distanceUnit = distanceUnit,
            profile = currentLine?.profile,
            lineRevision = lineRevision,
            hasOriginal = sources?.edit?.original != null,
            isSelectingStretch = isSelectingStretch,
            hasSteps = steps.isNotEmpty(),
            canUndo = canUndo,
            canMakeSteps = canMakeSteps,
            canFollowTrails = canFollowTrails,
            isRouting = isRouting,
            isSaving = isSaving,
            isLoop = isLoop,
            canStartLoopAtCut = canStartLoopAtCut,
            keptEndsMeet = endsMeet,
            picksWholeLine = picksWhole,
            hasChanges = hasChanges,
            leadingCarriedOfferMeters = leadingOffer?.carriedMeters,
            trailingCarriedOfferMeters = trailingOffer?.carriedMeters,
            showRevertDialog = showRevertDialog,
            refusal = refusal
        )
    }

    fun snapshotRangeState(): RouteTrimRangeState {
        val total = totalMeters
        val kept = max(endMeters - startMeters, 0.0)
        val removed = max(total - kept, 0.0)
        val lost = currentLengthMeters - kept
        val displayedDistance = if (isSelectingStretch) {
            kept
        } else {
            max(0.0, min(kept, hikeDistanceMeters - lost))
        }
        val currentLine = line
        val began = currentLine?.timestamp(
            RouteTrimHandle.START,
            startMeters,
            takingOffPause = RouteTrim.EndPauses.START in pausesTakenOff
        )
        val ended = currentLine?.timestamp(
            RouteTrimHandle.END,
            endMeters,
            takingOffPause = RouteTrim.EndPauses.END in pausesTakenOff
        )
        val durationSeconds = if (began != null && ended != null && ended > began) {
            (ended - began) / MILLIS_PER_SECOND
        } else {
            null
        }
        return RouteTrimRangeState(
            startMeters = startMeters,
            endMeters = endMeters,
            totalMeters = total,
            displayedDistanceMeters = displayedDistance,
            removedMeters = removed,
            keptDurationSeconds = durationSeconds
        )
    }

    fun buildMapOverlay(): MapRouteEditOverlay? {
        val base = cachedBaseOverlay ?: computeBaseMapOverlay()?.also { cachedBaseOverlay = it } ?: return null
        val pull = activePull
        return if (pull != null && pull.coverCoordinates.size >= 2) {
            base.copy(
                pullCoverSegments = pull.coverSegments,
                pullBandCoordinates = listOf(
                    pull.coverCoordinates.first(),
                    pull.currentVia,
                    pull.coverCoordinates.last()
                )
            )
        } else {
            base
        }
    }

    private fun computeBaseMapOverlay(): MapRouteEditOverlay? {
        val currentLine = line
        val startHandle = spotOf(RouteTrimHandle.START)
        val endHandle = spotOf(RouteTrimHandle.END)
        if (currentLine == null || startHandle == null || endHandle == null) return null
        val (walkedSegments, pausedSegments) = keptDisplaySegments(currentLine)
        val cutLeading = sliceRangeIf(!isSelectingStretch && startMeters > 0.0, currentLine, 0.0, startMeters)
        val cutTrailing = sliceRangeIf(
            !isSelectingStretch && endMeters < currentLine.totalMeters,
            currentLine,
            endMeters,
            currentLine.totalMeters
        )
        val pickedStretch =
            sliceRangeIf(isSelectingStretch && endMeters > startMeters, currentLine, startMeters, endMeters)
        return MapRouteEditOverlay(
            hikeId = hikeId,
            tintHex = tintHex,
            borderHex = borderHex,
            widthDp = routeWidthDp,
            linePattern = linePattern,
            keptWalkedSegments = walkedSegments,
            keptPausedSegments = pausedSegments,
            cutLeadingSegments = cutLeading,
            cutTrailingSegments = cutTrailing,
            pickedStretchSegments = pickedStretch,
            startHandle = startHandle,
            endHandle = endHandle,
            isSelectingStretch = isSelectingStretch
        )
    }

    private fun keptDisplaySegments(
        currentLine: RouteTrimLine
    ): Pair<List<List<GeoCoordinate>>, List<List<GeoCoordinate>>> {
        val keptRoute = if (isSelectingStretch || !isTrimmed) {
            currentLine.route
        } else {
            RouteTrim.trimmed(
                route = currentLine.route,
                startMeters = startMeters,
                endMeters = endMeters,
                distances = currentLine.distances,
                takingOff = pausesTakenOff
            )
        }
        val runs = keptRoute.toTrailRuns()
        val walked = runs.walkedRuns.map { range ->
            keptRoute.subList(range.first, range.last + 1).map { it.coordinate }
        }
        val paused = runs.pausedStretches.map { range ->
            keptRoute.subList(range.first, range.last + 1).map { it.coordinate }
        }
        return walked to paused
    }

    private fun sliceRangeIf(
        condition: Boolean,
        currentLine: RouteTrimLine,
        start: Double,
        end: Double
    ): List<List<GeoCoordinate>> = if (condition) {
        listOf(currentLine.coordinatesBetween(start, end)).filter { it.size >= 2 }
    } else {
        emptyList()
    }

    private fun hasTakenOff(stretch: RouteCarriedEnds.Stretch, handle: RouteTrimHandle): Boolean {
        val edge = innerEdge(stretch, handle)
        val isPast = if (handle == RouteTrimHandle.START) startMeters >= edge else endMeters <= edge
        return isPast && cuts(handle)
    }

    companion object {
        const val MINIMUM_KEPT_METERS = 50.0
        const val MINIMUM_STRETCH_METERS = 10.0
        const val END_TOLERANCE_METERS = 0.5
        const val MAXIMUM_PULL_METERS = 3_000.0
        const val ACCESSIBILITY_STEP_FRACTION = 0.02
        private const val THIRDS = 3.0
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val DEFAULT_ROUTE_WIDTH_DP = 3.0f
        private const val TAP_TO_REACH_RATIO = 0.45
        private const val MIN_PULL_TAP_METERS = 40.0

        fun detectJunctionDistances(line: RouteTrimLine): List<Double> =
            RouteTurnJunctionDetector.detect(line.profile).map { it.distanceMeters }

        fun isSameTrim(one: ClosedFloatingPointRange<Double>?, other: ClosedFloatingPointRange<Double>?): Boolean =
            when {
                one == null && other == null -> true
                one == null || other == null -> false
                else -> isSameEnd(one.start, other.start) && isSameEnd(one.endInclusive, other.endInclusive)
            }

        private fun isSameEnd(one: Double, other: Double): Boolean =
            one.isFinite() && other.isFinite() && abs(one - other) <= END_TOLERANCE_METERS

        private fun innerEdge(stretch: RouteCarriedEnds.Stretch, handle: RouteTrimHandle): Double = when (handle) {
            RouteTrimHandle.START -> stretch.endMeters
            RouteTrimHandle.END -> stretch.startMeters
        }
    }
}
