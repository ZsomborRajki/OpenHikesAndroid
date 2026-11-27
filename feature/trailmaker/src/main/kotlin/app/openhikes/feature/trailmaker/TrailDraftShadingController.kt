package app.openhikes.feature.trailmaker

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeTrailAnalysis
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteHeightSamples
import app.openhikes.core.model.RouteShadeStretch
import app.openhikes.core.model.RouteSteepness
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLeg
import app.openhikes.core.model.TrailTravelMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates cache-only SAC difficulty shading and Copernicus DEM steepness shading for
 * [TrailMakerViewModel] so drawn hiking routes show the same map colors they will carry once saved.
 */
@Suppress("LongParameterList")
internal class TrailDraftShadingController(
    private val scope: CoroutineScope,
    private val engine: TrailDraftEngine,
    private val trailGraphProvider: TrailGraphProvider,
    private val defaultDispatcher: CoroutineDispatcher,
    private val canMutateDraft: () -> Boolean,
    private val onStretchesUpdated: () -> Unit
) {
    private var difficultyStretches: List<RouteShadeStretch> = emptyList()
    private var steepnessStretches: List<RouteShadeStretch> = emptyList()
    private var difficultyJob: Job? = null
    private var steepnessJob: Job? = null
    private var shadingGeneration: Long = 0L
    private var lastMeasuredLineSignature: List<Pair<List<GeoCoordinate>, Boolean>>? = null

    private val offeredSet = LinkedHashSet<RouteColoring>()

    val offeredColorings: Set<RouteColoring>
        get() = offeredSet.toSet()

    val hasOffered: Boolean
        get() = offeredSet.isNotEmpty()

    fun activeStretches(routeColoring: RouteColoring, elevationAvailable: Boolean = true): List<RouteShadeStretch> {
        val effective = routeColoring.lockingElevation(locked = !elevationAvailable)
        val isReadyHiking = isReadyForHikingShading() && engine.drag == null
        if (!isReadyHiking) return emptyList()
        return when (effective) {
            RouteColoring.DIFFICULTY -> difficultyStretches
            RouteColoring.ELEVATION -> steepnessStretches
            RouteColoring.OFF -> emptyList()
        }
    }

    fun refresh() {
        val signature = currentLineSignature()
        if (signature == null) {
            clearPending()
        } else if (signature != lastMeasuredLineSignature) {
            startDifficultyMeasurement(signature, nonWarningDraftRoute(engine.legs))
        }
    }

    /**
     * Shades the current hiking draft by steepness from newly landed [samples], masking out
     * degraded (dashed/unmapped/refused) legs so placeholder geometry is never colored over.
     */
    fun onHeightsLanded(samples: RouteHeightSamples) {
        if (!canMutateDraft() || !isReadyForHikingShading()) return
        val route = engine.routeCoordinates
        if (route.size <= 1 || !samples.describes(route)) return
        val measured = withoutDegradedLegs(samples.interpolating(route), engine.legs)
        val generation = shadingGeneration
        steepnessJob?.cancel()
        steepnessJob = scope.launch {
            val stretches = withContext(defaultDispatcher) {
                RouteSteepness.runs(measured)
            }
            if (isMeasurementStillValid(generation)) {
                steepnessStretches = stretches
                if (stretches.isNotEmpty()) {
                    offeredSet.add(RouteColoring.ELEVATION)
                }
                onStretchesUpdated()
            }
        }
    }

    fun cancel() {
        shadingGeneration += 1L
        difficultyJob?.cancel()
        difficultyJob = null
        steepnessJob?.cancel()
        steepnessJob = null
    }

    fun reset() {
        cancel()
        difficultyStretches = emptyList()
        steepnessStretches = emptyList()
        offeredSet.clear()
        lastMeasuredLineSignature = null
    }

    private fun clearPending() {
        cancel()
        difficultyStretches = emptyList()
        steepnessStretches = emptyList()
        lastMeasuredLineSignature = null
    }

    private fun currentLineSignature(): List<Pair<List<GeoCoordinate>, Boolean>>? {
        if (!canMutateDraft() || !isReadyForHikingShading()) return null
        return engine.legs
            .map { it.coordinates to it.snap.isDegraded }
            .filter { (coords, _) -> coords.size > 1 }
            .takeIf { it.isNotEmpty() }
    }

    private fun startDifficultyMeasurement(
        signature: List<Pair<List<GeoCoordinate>, Boolean>>,
        route: List<RouteCoordinate>
    ) {
        lastMeasuredLineSignature = signature
        difficultyStretches = emptyList()
        steepnessStretches = emptyList()
        val generation = ++shadingGeneration
        difficultyJob?.cancel()
        steepnessJob?.cancel()
        steepnessJob = null
        if (route.size <= 1) {
            difficultyJob = null
            return
        }
        difficultyJob = scope.launch {
            val stretches = withContext(defaultDispatcher) {
                val ctx = currentCoroutineContext()
                HikeTrailAnalysis.difficultyRuns(
                    route = route,
                    provider = trailGraphProvider,
                    allowNetwork = false,
                    checkActive = { ctx.ensureActive() }
                ).mapNotNull { run ->
                    run.category.shade?.let { shade ->
                        RouteShadeStretch(
                            shade = shade,
                            coordinates = run.coordinates,
                            joinsPrevious = run.joinsPrevious
                        )
                    }
                }
            }
            if (isMeasurementStillValid(generation)) {
                difficultyStretches = stretches
                if (stretches.isNotEmpty()) {
                    offeredSet.add(RouteColoring.DIFFICULTY)
                }
                onStretchesUpdated()
            }
        }
    }

    private fun isMeasurementStillValid(generation: Long): Boolean =
        generation == shadingGeneration && canMutateDraft() && isReadyForHikingShading()

    private fun isReadyForHikingShading(): Boolean = engine.travelMode == TrailTravelMode.HIKING && !engine.isRouting

    private fun nonWarningDraftRoute(legs: List<TrailLeg>): List<RouteCoordinate> {
        val result = ArrayList<RouteCoordinate>()
        var sawGap = false
        for (leg in legs) {
            if (leg.snap.isWarning || leg.coordinates.size < 2) {
                if (result.isNotEmpty()) sawGap = true
                continue
            }
            leg.coordinates.forEachIndexed { index, coord ->
                val isDuplicateJoin = index == 0 && !sawGap && result.lastOrNull()?.coordinate == coord
                if (!isDuplicateJoin) {
                    val boundary = if (index == 0 && sawGap) RouteBoundary.PAUSED else null
                    result.add(RouteCoordinate(coord.latitude, coord.longitude, boundary = boundary))
                    sawGap = false
                }
            }
        }
        return result
    }

    internal companion object {
        fun withoutDegradedLegs(route: List<RouteCoordinate>, legs: List<TrailLeg>): List<RouteCoordinate> {
            val cleared = route.toMutableList()
            var start = 0
            for (leg in legs) {
                val end = minOf(start + leg.coordinates.size - 1, cleared.lastIndex)
                if (start > end) break
                if (leg.snap.isDegraded) {
                    for (index in start..end) {
                        cleared[index] = cleared[index].copy(elevationMeters = null)
                    }
                }
                start = end
            }
            return cleared
        }
    }
}
