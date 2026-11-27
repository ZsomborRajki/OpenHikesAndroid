package app.openhikes.core.data

import android.content.Context
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.AssembledTrailGraph
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteStretchRouter
import app.openhikes.core.model.RoutedStretchWay
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphCorridor
import app.openhikes.core.model.TrailGraphIndex
import app.openhikes.core.model.TrailGraphLegRouter
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegAnswerCache
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegGap
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegRoute
import app.openhikes.core.model.TrailLegRouter
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.network.RoutingGraphSquareDataSource
import app.openhikes.core.network.RoutingSquareReadResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads bundled JSON [TrailGraph] fixtures from `assets/fixtures/` during debug UI tests.
 */
interface BundledTrailGraphFixtureSource {
    suspend fun loadFixture(fixtureName: String): TrailGraph?
}

@Singleton
internal class AssetBundledTrailGraphFixtureSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BundledTrailGraphFixtureSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadFixture(fixtureName: String): TrailGraph? = withContext(ioDispatcher) {
        val sanitized = fixtureName.trim()
        val hasUnsafePath = sanitized.contains("..") || sanitized.any { it == '/' || it == '\\' }
        if (sanitized.isEmpty() || hasUnsafePath) return@withContext null
        runCatching {
            val raw = context.assets.open("fixtures/$sanitized.json").use { it.readBytes().decodeToString() }
            json.decodeFromString(TrailGraph.serializer(), raw)
        }.getOrNull()
    }
}

/**
 * Assembles OSM trail graphs covering a corridor from either a bundled test fixture or
 * `tiles.openhikes.app` pre-built z12 routing squares.
 */
@Singleton
internal class ArchiveTrailGraphProvider @Inject constructor(
    private val routingSquareDataSource: RoutingGraphSquareDataSource,
    private val bundledFixtureSource: BundledTrailGraphFixtureSource,
    private val launchConfigHolder: LaunchConfigHolder
) : TrailGraphProvider {
    private val fixtureMutex = Mutex()
    private var cachedFixtureName: String? = null
    private var cachedFixtureGraph: TrailGraph? = null

    override val isAvailable: Boolean
        get() {
            val config = launchConfigHolder.current.value
            return !config.isUiTesting || config.trailGraphFixtureName != null
        }

    override suspend fun assembledGraph(covering: List<GeoCoordinate>): AssembledTrailGraph {
        val config = launchConfigHolder.current.value
        val fixtureName = config.trailGraphFixtureName
        return when {
            fixtureName != null -> AssembledTrailGraph(
                graph = loadFixture(fixtureName),
                outage = null,
                isComplete = true
            )
            config.isUiTesting -> AssembledTrailGraph(graph = null, outage = null, isComplete = false)
            else -> assembleFromNetworkSquares(covering)
        }
    }

    override suspend fun cachedGraphOnly(covering: List<GeoCoordinate>): TrailGraph? {
        val config = launchConfigHolder.current.value
        val fixtureName = config.trailGraphFixtureName
        return when {
            fixtureName != null -> loadFixture(fixtureName)
            config.isUiTesting -> null
            else -> assembleCachedOnly(covering)
        }
    }

    private suspend fun assembleFromNetworkSquares(covering: List<GeoCoordinate>): AssembledTrailGraph {
        val rawRegions = TrailGraphCorridor.regionsCovering(covering, maxRegions = MAX_CORRIDOR_REGIONS + 1)
        val exceededCap = rawRegions.size > MAX_CORRIDOR_REGIONS
        val regions = if (exceededCap) rawRegions.take(MAX_CORRIDOR_REGIONS) else rawRegions
        var combined = TrailGraph.Empty
        var firstOutage: TrailLegOutage? = null
        for (region in regions) {
            when (val answer = routingSquareDataSource.readRegion(region)) {
                is RoutingSquareReadResult.Graph -> combined = combined.merging(answer.graph)
                is RoutingSquareReadResult.Failed -> if (firstOutage == null) firstOutage = answer.outage
            }
        }
        return AssembledTrailGraph(
            graph = if (combined.isEmpty) null else combined,
            outage = firstOutage,
            isComplete = firstOutage == null && !exceededCap
        )
    }

    private suspend fun assembleCachedOnly(covering: List<GeoCoordinate>): TrailGraph? {
        val regions = TrailGraphCorridor.regionsCovering(covering, maxRegions = MAX_CORRIDOR_REGIONS)
        var combined = TrailGraph.Empty
        for (region in regions) {
            val cached = routingSquareDataSource.readCachedRegion(region)
            if (cached != null) combined = combined.merging(cached)
        }
        return if (combined.isEmpty) null else combined
    }

    private suspend fun loadFixture(name: String): TrailGraph? = fixtureMutex.withLock {
        if (cachedFixtureName != name) {
            val loaded = bundledFixtureSource.loadFixture(name)
            if (loaded != null) {
                cachedFixtureName = name
                cachedFixtureGraph = loaded
            }
            loaded
        } else {
            cachedFixtureGraph
        }
    }

    private companion object {
        const val MAX_CORRIDOR_REGIONS = 24
    }
}

/**
 * Production [TrailLegRouter] routing hiking legs over [TrailGraphProvider] with cached spatial index
 * and bounded oldest-first answer cache.
 */
@Singleton
internal class OsmTrailLegRouter @Inject constructor(
    private val graphProvider: TrailGraphProvider,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : TrailLegRouter {
    private val stateMutex = Mutex()
    private val cache = TrailLegAnswerCache()
    private var builtGraph: TrailGraph? = null
    private var builtIndex: TrailGraphIndex? = null

    override fun supportsMode(mode: TrailTravelMode): Boolean =
        mode == TrailTravelMode.HIKING && graphProvider.isAvailable

    override suspend fun route(ends: TrailLegEnds, mode: TrailTravelMode): TrailLegRoute? = when {
        mode != TrailTravelMode.HIKING -> TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.NO_DIRECTIONS))
        !graphProvider.isAvailable -> null
        else -> routeHikingLeg(ends)
    }

    private suspend fun routeHikingLeg(ends: TrailLegEnds): TrailLegRoute {
        val cached = stateMutex.withLock { cache[ends] }
        if (cached != null) return cached

        val finalRoute = if (ends.straightDistanceMeters > TrailGraphLegRouter.MAXIMUM_LEG_METERS) {
            TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.TOO_FAR_APART))
        } else {
            val assembled = graphProvider.assembledGraph(TrailGraphCorridor.corridorAlong(ends))
            val graph = assembled.graph
            val routed = if (graph != null && !graph.isEmpty) {
                resolveOverGraph(ends, graph)
            } else {
                TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))
            }
            val outage = assembled.outage
            if (routed.snap != TrailLegSnap.Snapped && outage != null) {
                TrailLegRoute.straight(ends, TrailLegSnap.Refused(outage))
            } else {
                routed
            }
        }

        if (!finalRoute.snap.isRetryable) {
            stateMutex.withLock { cache.store(ends, finalRoute) }
        }
        return finalRoute
    }

    private suspend fun resolveOverGraph(ends: TrailLegEnds, graph: TrailGraph): TrailLegRoute =
        withContext(defaultDispatcher) {
            stateMutex.withLock {
                val index = if (builtGraph == graph && builtIndex != null) {
                    requireNotNull(builtIndex)
                } else {
                    TrailGraphIndex(graph, TrailGraphIndex.Network.WALKING).also { fresh ->
                        builtGraph = graph
                        builtIndex = fresh
                    }
                }
                TrailGraphLegRouter.route(ends, index)
            }
        }
}

/**
 * Bridges [TrailLegRouter] into Edit Route's [RouteStretchRouter] so *Follow Trails*, *Close Loop*,
 * and map line pulls snap to real OSM paths whenever the routing graph is available.
 */
@Singleton
internal class TrailGraphRouteStretchRouter @Inject constructor(private val trailLegRouter: TrailLegRouter) :
    RouteStretchRouter {
    override val supportsMappedRouting: Boolean
        get() = trailLegRouter.supportsMode(TrailTravelMode.HIKING)

    override suspend fun routeWay(from: GeoCoordinate, to: GeoCoordinate, through: GeoCoordinate?): RoutedStretchWay? =
        when {
            !supportsMappedRouting -> RoutedStretchWay(
                coordinates = listOfNotNull(from, through, to),
                isMapped = false
            )
            through == null -> trailLegRouter.route(TrailLegEnds(from, to), TrailTravelMode.HIKING)?.let { routed ->
                RoutedStretchWay(coordinates = routed.coordinates, isMapped = routed.snap == TrailLegSnap.Snapped)
            }
            else -> routeThroughWaypoint(from, to, through)
        }

    private suspend fun routeThroughWaypoint(
        from: GeoCoordinate,
        to: GeoCoordinate,
        through: GeoCoordinate
    ): RoutedStretchWay? {
        val first = trailLegRouter.route(TrailLegEnds(from, through), TrailTravelMode.HIKING)
        val second = first?.let { trailLegRouter.route(TrailLegEnds(through, to), TrailTravelMode.HIKING) }
        return if (first != null && second != null) {
            RoutedStretchWay(
                coordinates = first.coordinates + second.coordinates.drop(1),
                isMapped = first.snap == TrailLegSnap.Snapped || second.snap == TrailLegSnap.Snapped
            )
        } else {
            null
        }
    }
}
