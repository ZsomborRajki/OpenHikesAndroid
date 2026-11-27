package app.openhikes.feature.trailmaker

import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationSource
import app.openhikes.core.model.RouteElevationSummary
import app.openhikes.core.model.RouteHeightSamples
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.samplesOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debounced Copernicus DEM elevation controller for Trail Maker drafts.
 *
 * Waits [settleMillis] after the drawn geometry stops moving before requesting heights, clears any
 * stale figure immediately when the line changes, never requests while legs are still routing, and
 * skips requests entirely on launches where [RouteElevationSource.isAvailable] is false.
 */
@Suppress("LongParameterList")
internal class TrailDraftElevationController(
    private val scope: CoroutineScope,
    private val engine: TrailDraftEngine,
    private val elevationSource: RouteElevationSource,
    private val defaultDispatcher: CoroutineDispatcher,
    private val canMutateDraft: () -> Boolean,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val onStateChanged: () -> Unit,
    private val onHeightsLanded: (RouteHeightSamples) -> Unit
) {
    val isAvailable: Boolean
        get() = elevationSource.isAvailable

    var summary: RouteElevationSummary? = null
        private set

    var isMeasuring: Boolean = false
        private set

    var samples: RouteHeightSamples? = null
        private set

    private var measureJob: Job? = null
    private var generation: Long = 0L
    private var lastTrackedRoute: List<RouteCoordinate>? = null

    fun refresh() {
        val route = if (isAvailable && canMutateDraft() && !engine.isRouting) {
            engine.routeCoordinates
        } else {
            emptyList()
        }
        when {
            route.size <= 1 -> forgetIfNeeded()
            route != lastTrackedRoute -> scheduleMeasurement(route)
        }
    }

    fun cancel() {
        generation += 1L
        measureJob?.cancel()
        measureJob = null
        isMeasuring = false
    }

    fun reset() {
        generation += 1L
        measureJob?.cancel()
        measureJob = null
        lastTrackedRoute = null
        isMeasuring = false
        samples = null
        summary = null
    }

    private fun forgetIfNeeded() {
        val hadState = measureJob != null ||
            isMeasuring ||
            samples != null ||
            summary != null ||
            lastTrackedRoute != null
        if (!hadState) return
        reset()
    }

    private fun scheduleMeasurement(route: List<RouteCoordinate>) {
        generation += 1L
        val currentGeneration = generation
        measureJob?.cancel()
        lastTrackedRoute = route
        isMeasuring = false
        samples = null
        summary = null
        measureJob = scope.launch {
            if (settleMillis > 0L) {
                delay(settleMillis)
            }
            if (!isStillActive(currentGeneration)) return@launch
            val currentRoute = engine.routeCoordinates
            if (engine.isRouting || currentRoute.size <= 1) return@launch
            isMeasuring = true
            onStateChanged()
            val measured = withContext(defaultDispatcher) {
                elevationSource.samplesOf(currentRoute)
            }
            if (!isStillActive(currentGeneration)) return@launch
            isMeasuring = false
            if (measured != null && measured.describes(engine.routeCoordinates)) {
                samples = measured
                summary = measured.summary
                onHeightsLanded(measured)
            }
            onStateChanged()
        }
    }

    private fun isStillActive(expectedGeneration: Long): Boolean =
        expectedGeneration == generation && canMutateDraft() && isAvailable

    companion object {
        const val SETTLE_MILLIS = 2_000L
    }
}
