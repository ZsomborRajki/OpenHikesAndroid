package app.openhikes.core.testing

import app.openhikes.core.model.AssembledTrailGraph
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegOutage
import kotlinx.serialization.json.Json

/**
 * Pure-JVM deterministic [TrailGraphProvider] fake for trail analysis, route shading, and Trail
 * Maker tests without touching disk or network.
 */
class FakeTrailGraphProvider(
    private val graph: TrailGraph? = null,
    private val cachedGraph: TrailGraph? = graph,
    private val outage: TrailLegOutage? = null,
    private val isComplete: Boolean = (outage == null),
    override val isAvailable: Boolean = true,
    private val assembledOverride: (suspend (List<GeoCoordinate>) -> AssembledTrailGraph)? = null
) : TrailGraphProvider {
    var assembledGraphCalls: Int = 0
        private set

    var cachedGraphOnlyCalls: Int = 0
        private set

    override suspend fun assembledGraph(covering: List<GeoCoordinate>): AssembledTrailGraph {
        assembledGraphCalls += 1
        return assembledOverride?.invoke(covering) ?: AssembledTrailGraph(
            graph = graph,
            outage = outage,
            isComplete = isComplete
        )
    }

    override suspend fun cachedGraphOnly(covering: List<GeoCoordinate>): TrailGraph? {
        cachedGraphOnlyCalls += 1
        return cachedGraph
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromThumseeLoopFixture(): FakeTrailGraphProvider {
            val decoded = json.decodeFromString(TrailGraph.serializer(), TestFixtures.thumseeLoopTrailsGraphJson())
            return FakeTrailGraphProvider(graph = decoded, cachedGraph = decoded)
        }
    }
}
