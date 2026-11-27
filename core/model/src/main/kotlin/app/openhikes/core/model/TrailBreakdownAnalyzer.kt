package app.openhikes.core.model

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only route attribution over a cached [TrailGraph], producing both category meter totals
 * ([TrailBreakdown]) and contiguous route-ordered geometry stretches ([TrailCategoryRun]) from one
 * shared sampling pass so map shading and breakdown bars never disagree.
 */
object TrailBreakdownAnalyzer {
    /**
     * Maximum distance from a sample to a mapped OSM way before the sample is reported as
     * unmapped instead of attributed.
     */
    const val DEFAULT_TOLERANCE_METERS: Double = 25.0

    /**
     * Target distance between consecutive samples along each walked route segment.
     */
    const val SAMPLING_STEP_METERS: Double = 20.0

    /**
     * Hysteresis bias favoring the OSM way matched by the previous sample so parallel paths do not
     * chatter back and forth fix by fix.
     */
    const val WAY_STICKINESS_METERS: Double = 5.0

    /**
     * Ceiling on samples contributed by a single segment so sparse or corrupt gaps stay bounded.
     */
    const val MAXIMUM_STEPS_PER_SEGMENT: Int = 512

    private const val CANCELLATION_CHECK_INTERVAL = 255
    private const val MIDPOINT_OFFSET = 0.5

    fun surfaceBreakdown(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        checkActive: () -> Unit = {}
    ): TrailSurfaceBreakdown = breakdown(
        route = route,
        graph = graph,
        toleranceMeters = toleranceMeters,
        classify = TrailSurface::fromEdge,
        unmapped = TrailSurface.UNMAPPED,
        checkActive = checkActive
    )

    fun difficultyBreakdown(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        checkActive: () -> Unit = {}
    ): TrailDifficultyBreakdown = breakdown(
        route = route,
        graph = graph,
        toleranceMeters = toleranceMeters,
        classify = TrailDifficulty::fromEdge,
        unmapped = TrailDifficulty.UNMAPPED,
        checkActive = checkActive
    )

    fun surfaceRuns(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        checkActive: () -> Unit = {}
    ): List<TrailCategoryRun<TrailSurface>> = runs(
        route = route,
        graph = graph,
        toleranceMeters = toleranceMeters,
        classify = TrailSurface::fromEdge,
        unmapped = TrailSurface.UNMAPPED,
        checkActive = checkActive
    )

    fun difficultyRuns(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        checkActive: () -> Unit = {}
    ): List<TrailCategoryRun<TrailDifficulty>> = runs(
        route = route,
        graph = graph,
        toleranceMeters = toleranceMeters,
        classify = TrailDifficulty::fromEdge,
        unmapped = TrailDifficulty.UNMAPPED,
        checkActive = checkActive
    )

    fun <C : TrailCategory> breakdown(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        classify: (TrailGraphEdge) -> C,
        unmapped: C,
        checkActive: () -> Unit = {}
    ): TrailBreakdown<C> {
        if (route.size <= 1 || graph.isEmpty) return TrailBreakdown.empty()
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        return breakdownWithIndex(route, index, toleranceMeters, classify, unmapped, checkActive)
    }

    internal fun <C : TrailCategory> breakdownWithIndex(
        route: List<RouteCoordinate>,
        index: TrailGraphIndex,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        classify: (TrailGraphEdge) -> C,
        unmapped: C,
        checkActive: () -> Unit = {}
    ): TrailBreakdown<C> {
        val totals = HashMap<C, Double>()
        walk(route, index, toleranceMeters, classify, unmapped, checkActive) { sample ->
            totals[sample.category] = (totals[sample.category] ?: 0.0) + sample.meters
        }
        return TrailBreakdown.fromMeters(totals)
    }

    fun <C : TrailCategory> runs(
        route: List<RouteCoordinate>,
        graph: TrailGraph,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        classify: (TrailGraphEdge) -> C,
        unmapped: C,
        checkActive: () -> Unit = {}
    ): List<TrailCategoryRun<C>> {
        if (route.size <= 1 || graph.isEmpty) return emptyList()
        val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
        val builder = TrailCategoryRunBuilder<C>()
        walk(route, index, toleranceMeters, classify, unmapped, checkActive) { sample ->
            builder.add(sample)
        }
        return builder.finish()
    }

    /**
     * Finds the nearest graph edge within [toleranceMeters], favoring [preferredWayId] when within
     * [WAY_STICKINESS_METERS] of the globally nearest candidate.
     */
    fun nearestEdgeIndex(
        coordinate: GeoCoordinate,
        index: TrailGraphIndex,
        toleranceMeters: Double,
        preferredWayId: Long?
    ): Int? {
        var bestEdge = -1
        var bestOffRoute = Double.POSITIVE_INFINITY
        var preferredEdge = -1
        var preferredOffRoute = Double.POSITIVE_INFINITY

        index.grid.forEachEdge(near = coordinate, radiusMeters = toleranceMeters) { edgeIndex ->
            val endpoints = index.edgeEndpoints[edgeIndex]
            val offRoute = RouteGeometry.project(coordinate, endpoints.start, endpoints.end).offRouteMeters
            if (offRoute <= toleranceMeters) {
                if (offRoute < bestOffRoute) {
                    bestOffRoute = offRoute
                    bestEdge = edgeIndex
                }
                if (preferredWayId != null &&
                    index.edges[edgeIndex].id.wayId == preferredWayId &&
                    offRoute < preferredOffRoute
                ) {
                    preferredOffRoute = offRoute
                    preferredEdge = edgeIndex
                }
            }
        }

        return when {
            bestEdge < 0 -> null
            preferredEdge >= 0 && preferredOffRoute <= bestOffRoute + WAY_STICKINESS_METERS -> preferredEdge
            else -> bestEdge
        }
    }

    @Suppress("LongParameterList")
    private fun <C : TrailCategory> walk(
        route: List<RouteCoordinate>,
        index: TrailGraphIndex,
        toleranceMeters: Double,
        classify: (TrailGraphEdge) -> C,
        unmapped: C,
        checkActive: () -> Unit,
        visit: (TrailCategorySample<C>) -> Unit
    ) {
        if (route.size <= 1 || index.edges.isEmpty()) return
        val runs = route.toTrailRuns()
        var previousWayId: Long? = null
        var samplesTaken = 0
        var resumesAfterPause = false

        for ((runIndex, range) in runs.walkedRuns.withIndex()) {
            if (runIndex > 0) resumesAfterPause = true
            for (leg in range.first until range.last) {
                val from = route[leg].coordinate
                val to = route[leg + 1].coordinate
                val segmentMeters = RouteGeometry.distanceMeters(from, to)
                if (!segmentMeters.isFinite() || segmentMeters <= 0.0) continue

                val steps = min(
                    max(ceil(segmentMeters / SAMPLING_STEP_METERS).toInt(), 1),
                    MAXIMUM_STEPS_PER_SEGMENT
                )
                val stepMeters = segmentMeters / steps.toDouble()
                for (step in 0 until steps) {
                    if (samplesTaken % CANCELLATION_CHECK_INTERVAL == 0) checkActive()
                    samplesTaken += 1
                    val midpoint = RouteGeometry.interpolate(
                        start = from,
                        end = to,
                        fraction = (step.toDouble() + MIDPOINT_OFFSET) / steps.toDouble()
                    )
                    val edgeIndex = nearestEdgeIndex(midpoint, index, toleranceMeters, previousWayId)
                    val category = if (edgeIndex != null) {
                        val edge = index.edges[edgeIndex]
                        previousWayId = edge.id.wayId
                        classify(edge)
                    } else {
                        previousWayId = null
                        unmapped
                    }
                    visit(
                        TrailCategorySample(
                            category = category,
                            from = from,
                            to = to,
                            step = step,
                            steps = steps,
                            meters = stepMeters,
                            resumesAfterPause = resumesAfterPause
                        )
                    )
                    resumesAfterPause = false
                }
            }
        }
    }
}

private data class TrailCategorySample<C : TrailCategory>(
    val category: C,
    val from: GeoCoordinate,
    val to: GeoCoordinate,
    val step: Int,
    val steps: Int,
    val meters: Double,
    val resumesAfterPause: Boolean
) {
    val endsSegment: Boolean
        get() = step == steps - 1

    val start: GeoCoordinate
        get() = if (step == 0) {
            from
        } else {
            RouteGeometry.interpolate(from, to, step.toDouble() / steps.toDouble())
        }

    val end: GeoCoordinate
        get() = if (endsSegment) {
            to
        } else {
            RouteGeometry.interpolate(from, to, (step + 1).toDouble() / steps.toDouble())
        }
}

/**
 * Folds consecutive samples of one category into polylines without duplicating collinear mid-segment
 * samples, closing runs on category changes and pause boundaries alike.
 */
private class TrailCategoryRunBuilder<C : TrailCategory> {
    private val runs = ArrayList<TrailCategoryRun<C>>()
    private var currentCategory: C? = null
    private val coordinates = ArrayList<GeoCoordinate>()
    private var pendingEnd: GeoCoordinate? = null

    fun add(sample: TrailCategorySample<C>) {
        if (sample.category != currentCategory || sample.resumesAfterPause) {
            close()
            currentCategory = sample.category
            coordinates.clear()
            coordinates.add(sample.start)
        }
        if (sample.endsSegment) {
            coordinates.add(sample.to)
            pendingEnd = null
        } else {
            pendingEnd = sample.end
        }
    }

    fun finish(): List<TrailCategoryRun<C>> {
        close()
        return runs
    }

    private fun close() {
        val category = currentCategory ?: return
        pendingEnd?.let(coordinates::add)
        if (coordinates.size > 1) {
            runs.add(TrailCategoryRun(category = category, coordinates = coordinates.toList()))
        }
        currentCategory = null
        coordinates.clear()
        pendingEnd = null
    }
}

/**
 * Coordinates one-pass OSM surface & SAC difficulty breakdowns and surveyed map-shading runs
 * against a [TrailGraphProvider].
 */
object HikeTrailAnalysis {
    /**
     * Measures both surface and SAC difficulty breakdowns from a single complete graph covering
     * [route]. Quietly returns [HikeTrailBreakdowns.Empty] when the graph is unavailable, partial,
     * or empty so missing network regions are never misreported as unmapped ground.
     */
    suspend fun breakdowns(
        route: List<RouteCoordinate>,
        provider: TrailGraphProvider,
        checkActive: () -> Unit = {}
    ): HikeTrailBreakdowns {
        val graph = if (route.size > 1 && provider.isAvailable) {
            completeNonEmptyGraph(provider.assembledGraph(route.map { it.coordinate }))
        } else {
            null
        }
        return if (graph == null) {
            HikeTrailBreakdowns.Empty
        } else {
            val index = TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING)
            val surface = TrailBreakdownAnalyzer.breakdownWithIndex(
                route = route,
                index = index,
                classify = TrailSurface::fromEdge,
                unmapped = TrailSurface.UNMAPPED,
                checkActive = checkActive
            )
            val difficulty = TrailBreakdownAnalyzer.breakdownWithIndex(
                route = route,
                index = index,
                classify = TrailDifficulty::fromEdge,
                unmapped = TrailDifficulty.UNMAPPED,
                checkActive = checkActive
            )
            HikeTrailBreakdowns(
                surface = surface.takeUnless { it.isEmpty },
                difficulty = difficulty.takeUnless { it.isEmpty }
            )
        }
    }

    /**
     * Returns every surveyed SAC difficulty stretch along [route] for map shading.
     *
     * When [allowNetwork] is `false` (used by Trail Maker while drawing), reads only already-cached
     * graph regions without issuing network requests.
     */
    suspend fun difficultyRuns(
        route: List<RouteCoordinate>,
        provider: TrailGraphProvider,
        allowNetwork: Boolean = true,
        checkActive: () -> Unit = {}
    ): List<TrailCategoryRun<TrailDifficulty>> {
        val graph = if (route.size > 1 && provider.isAvailable) {
            val coordinates = route.map { it.coordinate }
            if (allowNetwork) {
                provider.assembledGraph(coordinates).graph
            } else {
                provider.cachedGraphOnly(coordinates)
            }
        } else {
            null
        }
        return if (graph == null || graph.isEmpty) {
            emptyList()
        } else {
            TrailBreakdownAnalyzer.difficultyRuns(
                route = route,
                graph = graph,
                checkActive = checkActive
            ).filter { it.category.isSurveyed }
        }
    }

    private fun completeNonEmptyGraph(assembled: AssembledTrailGraph): TrailGraph? =
        assembled.graph?.takeIf { assembled.isComplete && assembled.outage == null && !it.isEmpty }
}
