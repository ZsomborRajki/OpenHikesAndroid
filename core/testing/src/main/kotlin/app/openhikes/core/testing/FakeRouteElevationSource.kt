package app.openhikes.core.testing

import app.openhikes.core.model.ElevationFailure
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteElevationSource
import kotlinx.coroutines.CompletableDeferred

/**
 * Pure-JVM fake [RouteElevationSource] for deterministic unit and ViewModel tests.
 */
class FakeRouteElevationSource(
    override var isAvailable: Boolean = true,
    var cannedHeights: List<Double>? = null,
    var failure: ElevationFailure? = null,
    var handler: (suspend (List<GeoCoordinate>) -> ElevationFetchResult)? = null
) : RouteElevationSource {
    private val lock = Any()
    private val recordedCounts = ArrayList<Int>()
    private val recordedRequests = ArrayList<List<GeoCoordinate>>()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    @Volatile
    var onRequestStarted: (() -> Unit)? = null

    val askedCounts: List<Int>
        get() = synchronized(lock) { recordedCounts.toList() }

    val askedRequests: List<List<GeoCoordinate>>
        get() = synchronized(lock) { recordedRequests.toList() }

    val requestCount: Int
        get() = synchronized(lock) { recordedCounts.size }

    override suspend fun heights(coordinates: List<GeoCoordinate>): ElevationFetchResult {
        synchronized(lock) {
            recordedCounts.add(coordinates.size)
            recordedRequests.add(coordinates)
        }
        onRequestStarted?.invoke()
        gate?.await()
        val customHandler = handler
        val activeFailure = failure
        val values = cannedHeights
        return when {
            customHandler != null -> customHandler(coordinates)
            activeFailure != null -> ElevationFetchResult.Failed(activeFailure)
            values == null -> ElevationFetchResult.Failed(ElevationFailure.UNCOVERED)
            values.isEmpty() -> ElevationFetchResult.Failed(ElevationFailure.UNREACHABLE)
            else -> ElevationFetchResult.Heights(
                List(coordinates.size) { idx -> values[idx % values.size] }
            )
        }
    }
}
