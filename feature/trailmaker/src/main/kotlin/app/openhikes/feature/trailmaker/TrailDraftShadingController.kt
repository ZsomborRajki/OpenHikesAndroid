package app.openhikes.feature.trailmaker

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeTrailAnalysis
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteShadeStretch
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
 * Coordinates cache-only SAC difficulty shading for [TrailMakerViewModel] so drawn hiking routes
 * reflect cached OpenStreetMap grades without issuing network downloads or racing draft edits.
 */
internal class TrailDraftShadingController(
    private val scope: CoroutineScope,
    private val engine: TrailDraftEngine,
    private val trailGraphProvider: TrailGraphProvider,
    private val defaultDispatcher: CoroutineDispatcher,
    private val canMutateDraft: () -> Boolean,
    private val onStretchesUpdated: () -> Unit
) {
    private var difficultyStretches: List<RouteShadeStretch> = emptyList()
    private var shadingJob: Job? = null
    private var shadingGeneration: Long = 0L
    private var lastMeasuredLineSignature: List<List<GeoCoordinate>>? = null

    var hasOffered: Boolean = false
        private set

    fun activeStretches(routeColoring: RouteColoring): List<RouteShadeStretch> {
        val effective = routeColoring.lockingElevation(locked = true)
        val isReadyHiking = isReadyForHikingShading() && engine.drag == null
        return if (isReadyHiking && effective == RouteColoring.DIFFICULTY) {
            difficultyStretches
        } else {
            emptyList()
        }
    }

    fun refresh() {
        val signature = currentLineSignature()
        if (signature == null) {
            clearPending()
        } else if (signature != lastMeasuredLineSignature) {
            startMeasurement(signature, nonWarningDraftRoute(engine.legs))
        }
    }

    fun cancel() {
        shadingGeneration += 1L
        shadingJob?.cancel()
        shadingJob = null
    }

    fun reset() {
        cancel()
        difficultyStretches = emptyList()
        hasOffered = false
        lastMeasuredLineSignature = null
    }

    private fun clearPending() {
        cancel()
        difficultyStretches = emptyList()
        lastMeasuredLineSignature = null
    }

    private fun currentLineSignature(): List<List<GeoCoordinate>>? {
        if (!canMutateDraft() || !isReadyForHikingShading()) return null
        return engine.legs
            .filterNot { it.snap.isWarning }
            .map { it.coordinates }
            .filter { it.size > 1 }
            .takeIf { it.isNotEmpty() }
    }

    private fun startMeasurement(signature: List<List<GeoCoordinate>>, route: List<RouteCoordinate>) {
        lastMeasuredLineSignature = signature
        difficultyStretches = emptyList()
        val generation = ++shadingGeneration
        shadingJob?.cancel()
        shadingJob = scope.launch {
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
                    hasOffered = true
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
}
