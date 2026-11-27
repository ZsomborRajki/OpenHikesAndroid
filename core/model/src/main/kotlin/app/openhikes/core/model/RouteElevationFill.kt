package app.openhikes.core.model

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure sampling rules selecting up to [MAXIMUM_SHAPE_POINTS] route coordinates for DEM elevation
 * lookup, pinning both ends of every pause boundary while they fit inside half the request budget.
 */
object RouteElevationRequest {
    const val MAXIMUM_SHAPE_POINTS = 200

    /**
     * Evenly spaced indices into a sequence of [count] items, always keeping first and last when
     * `count > maxPoints` and `maxPoints > 1`.
     */
    fun decimatedIndices(count: Int, maxPoints: Int = MAXIMUM_SHAPE_POINTS): List<Int> = when {
        count <= 0 || maxPoints <= 0 -> emptyList()
        count <= maxPoints || maxPoints == 1 -> (0 until count).toList()
        else -> {
            val lastIndex = count - 1
            val step = lastIndex.toDouble() / (maxPoints - 1).toDouble()
            List(maxPoints) { i -> min((i * step).roundToInt(), lastIndex) }
        }
    }

    /**
     * Evenly spaced indices up to [maxPoints] while preserving all valid indices in [keeping].
     */
    fun decimatedIndices(count: Int, maxPoints: Int, keeping: Set<Int>): List<Int> = when {
        count <= 0 || maxPoints <= 0 -> emptyList()
        count <= maxPoints || maxPoints == 1 -> (0 until count).toList()
        else -> decimatedKeeping(count = count, maxPoints = maxPoints, keeping = keeping)
    }

    private fun decimatedKeeping(count: Int, maxPoints: Int, keeping: Set<Int>): List<Int> {
        val required = keeping.filter { it in 0 until count }.toSet()
        val remainingBudget = maxPoints - required.size
        return if (remainingBudget <= 0) {
            required.sorted().take(maxPoints)
        } else {
            val others = (0 until count).filter { it !in required }
            val strided = when {
                others.isEmpty() -> emptyList()
                remainingBudget == 1 -> listOf(0)
                else -> decimatedIndices(count = others.size, maxPoints = remainingBudget)
            }
            val combined = HashSet<Int>(required.size + strided.size)
            combined.addAll(required)
            for (idx in strided) {
                combined.add(others[idx])
            }
            combined.sorted()
        }
    }

    fun sampleIndexes(count: Int, limit: Int = MAXIMUM_SHAPE_POINTS): List<Int> =
        decimatedIndices(count = count, maxPoints = limit)

    /**
     * Sample indices for [route], preserving both ends of every pause boundary when those ends
     * consume at most half of [limit]; otherwise falls back to an even spread across the whole line.
     */
    fun sampleIndexes(route: List<RouteCoordinate>, limit: Int = MAXIMUM_SHAPE_POINTS): List<Int> {
        val spread = sampleIndexes(count = route.size, limit = limit)
        val pausedLegs = if (spread.size < route.size) route.toTrailRuns().pausedLegs else emptyList()
        return if (pausedLegs.isEmpty()) {
            spread
        } else {
            val pauseEnds = HashSet<Int>(pausedLegs.size * 2)
            for (leg in pausedLegs) {
                pauseEnds.add(leg)
                pauseEnds.add(leg + 1)
            }
            if (pauseEnds.size <= limit / 2) {
                decimatedIndices(count = route.size, maxPoints = limit, keeping = pauseEnds)
            } else {
                spread
            }
        }
    }
}

/**
 * DEM heights sampled at specific [indexes] of a route of [routePointCount] points, carrying the
 * exact sampled [coordinates] so callers can verify the route has not moved before applying them.
 */
data class RouteHeightSamples(
    val routePointCount: Int,
    val indexes: List<Int>,
    val coordinates: List<RouteCoordinate>,
    val heights: List<Double>
) {
    val summary: RouteElevationSummary
        get() {
            val accumulator = ElevationAccumulator()
            val count = min(coordinates.size, heights.size)
            for (i in 0 until count) {
                accumulator.record(coordinates[i].copy(elevationMeters = heights[i]))
            }
            return RouteElevationSummary(
                gainMeters = if (accumulator.hasChange) accumulator.gainMeters else null,
                lossMeters = if (accumulator.hasChange) accumulator.lossMeters else null,
                highMeters = accumulator.maximumMeters,
                lowMeters = accumulator.minimumMeters
            )
        }

    /**
     * Whether [route] still matches the exact point count and coordinates these heights were read for.
     */
    fun describes(route: List<RouteCoordinate>): Boolean = route.size == routePointCount &&
        indexes.size == coordinates.size &&
        indexes.size == heights.size &&
        indexes.indices.all { i ->
            val index = indexes[i]
            val point = route.getOrNull(index)
            val sampled = coordinates[i]
            point != null && point.latitude == sampled.latitude && point.longitude == sampled.longitude
        }

    /**
     * Returns [route] with finite readings placed at [indexes] and intervening points interpolated
     * linearly by cumulative ground distance, never interpolating across pause boundaries or
     * toward non-finite readings. Returns [route] untouched when [describes] is false.
     */
    fun interpolating(route: List<RouteCoordinate>): List<RouteCoordinate> {
        if (!describes(route)) return route
        val filled = route.toMutableList()
        for (i in indexes.indices) {
            val height = heights[i]
            if (height.isFinite()) {
                val idx = indexes[i]
                filled[idx] = filled[idx].copy(elevationMeters = height)
            }
        }
        val pausedLegs = route.toTrailRuns().pausedLegs
        for (pairIndex in 0 until indexes.lastIndex) {
            interpolateSpan(
                route = route,
                filled = filled,
                pausedLegs = pausedLegs,
                startIndex = indexes[pairIndex],
                startHeight = heights[pairIndex],
                endIndex = indexes[pairIndex + 1],
                endHeight = heights[pairIndex + 1]
            )
        }
        return filled
    }

    @Suppress("LongParameterList")
    private fun interpolateSpan(
        route: List<RouteCoordinate>,
        filled: MutableList<RouteCoordinate>,
        pausedLegs: List<Int>,
        startIndex: Int,
        startHeight: Double,
        endIndex: Int,
        endHeight: Double
    ) {
        if (endIndex - startIndex <= 1) return
        val firstPausedPos = lowerBound(pausedLegs, startIndex)
        val pastPausedPos = lowerBound(pausedLegs, endIndex)
        if (firstPausedPos < pastPausedPos) {
            fillPausedSpanEnds(
                filled = filled,
                startIndex = startIndex,
                startHeight = startHeight,
                firstPausedLeg = pausedLegs[firstPausedPos],
                lastPausedLeg = pausedLegs[pastPausedPos - 1],
                endIndex = endIndex,
                endHeight = endHeight
            )
        } else if (startHeight.isFinite() && endHeight.isFinite()) {
            interpolateContinuousSpan(route, filled, startIndex, startHeight, endIndex, endHeight)
        }
    }

    @Suppress("LongParameterList")
    private fun fillPausedSpanEnds(
        filled: MutableList<RouteCoordinate>,
        startIndex: Int,
        startHeight: Double,
        firstPausedLeg: Int,
        lastPausedLeg: Int,
        endIndex: Int,
        endHeight: Double
    ) {
        if (startHeight.isFinite()) {
            for (i in (startIndex + 1)..firstPausedLeg) {
                filled[i] = filled[i].copy(elevationMeters = startHeight)
            }
        }
        if (endHeight.isFinite()) {
            for (i in (lastPausedLeg + 1) until endIndex) {
                filled[i] = filled[i].copy(elevationMeters = endHeight)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun interpolateContinuousSpan(
        route: List<RouteCoordinate>,
        filled: MutableList<RouteCoordinate>,
        startIndex: Int,
        startHeight: Double,
        endIndex: Int,
        endHeight: Double
    ) {
        val spanCount = endIndex - startIndex
        val along = DoubleArray(spanCount + 1)
        for (offset in 1..spanCount) {
            val prev = route[startIndex + offset - 1]
            val curr = route[startIndex + offset]
            along[offset] = along[offset - 1] + RouteGeometry.distanceMeters(prev.coordinate, curr.coordinate)
        }
        val total = along[spanCount]
        val steps = spanCount.toDouble()
        for (offset in 1 until spanCount) {
            val fraction = if (total > 0.0) along[offset] / total else offset.toDouble() / steps
            val interpolated = startHeight + (endHeight - startHeight) * fraction
            val targetIndex = startIndex + offset
            filled[targetIndex] = filled[targetIndex].copy(elevationMeters = interpolated)
        }
    }

    private fun lowerBound(sorted: List<Int>, target: Int): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < target) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }
}

/**
 * Why a route elevation lookup returned no usable heights.
 */
enum class ElevationFailure {
    TOO_LONG,
    UNCOVERED,
    UNREACHABLE
}

/**
 * Result of reading Copernicus DEM heights for a list of coordinates.
 */
sealed interface ElevationFetchResult {
    data class Heights(val heights: List<Double>) : ElevationFetchResult

    data class Failed(val failure: ElevationFailure) : ElevationFetchResult
}

/**
 * Injectable pure-JVM seam providing Copernicus DEM ground elevations in meters.
 */
interface RouteElevationSource {
    /**
     * Whether this launch can query elevation squares at all. False in UI tests without an
     * explicit elevation scenario so no timers or background tasks are scheduled unnecessarily.
     */
    val isAvailable: Boolean
        get() = true

    /**
     * Returns heights in meters aligned 1:1 with [coordinates], with [Double.NaN] at any point
     * whose surrounding posts include withheld ground, or [ElevationFetchResult.Failed] if the
     * request could not be answered completely.
     */
    suspend fun heights(coordinates: List<GeoCoordinate>): ElevationFetchResult
}

/**
 * Samples [route] down to [RouteElevationRequest.MAXIMUM_SHAPE_POINTS] points, fetches heights from
 * this source, and returns [RouteHeightSamples] only when every sampled point has a finite height.
 */
suspend fun RouteElevationSource.samplesOf(route: List<RouteCoordinate>): RouteHeightSamples? {
    val indexes = if (isAvailable) RouteElevationRequest.sampleIndexes(route) else emptyList()
    if (indexes.size <= 1) return null
    val sampled = indexes.map { route[it] }
    val result = heights(sampled.map { it.coordinate })
    return when (result) {
        is ElevationFetchResult.Failed -> null
        is ElevationFetchResult.Heights -> {
            val valid = result.heights.size == sampled.size && result.heights.all { it.isFinite() }
            if (valid) {
                RouteHeightSamples(
                    routePointCount = route.size,
                    indexes = indexes,
                    coordinates = sampled,
                    heights = result.heights
                )
            } else {
                null
            }
        }
    }
}

/**
 * Returns [route] filled with interpolated DEM heights, or [route] unchanged when heights cannot
 * be read completely.
 */
suspend fun RouteElevationSource.fillRoute(route: List<RouteCoordinate>): List<RouteCoordinate> {
    val samples = samplesOf(route) ?: return route
    return samples.interpolating(route)
}

/**
 * Pure predicates deciding whether a library hike qualifies for one-time DEM elevation enrichment.
 *
 * A hike with even a single finite elevation reading is left untouched so measured GPS/barometer
 * tracks are never spliced with a second elevation reference.
 */
object HikeElevationFill {
    fun needsHeights(route: List<RouteCoordinate>): Boolean =
        route.size > 1 && route.none { it.elevationMeters?.isFinite() == true }

    fun canFill(hike: Hike): Boolean = !hike.isRecording && needsHeights(hike.route)
}
