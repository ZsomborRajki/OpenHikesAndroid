package app.openhikes.core.network

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.routing.TrailGraphSquareFile
import app.openhikes.core.model.RetryAfterParser
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphRegion
import app.openhikes.core.model.TrailGraphSquareGrid
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.VectorTileSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of reading one z12 routing graph square from cache or `tiles.openhikes.app`.
 */
sealed interface RoutingSquareReadResult {
    data class Graph(val graph: TrailGraph) : RoutingSquareReadResult

    data class Failed(val outage: TrailLegOutage) : RoutingSquareReadResult
}

/**
 * Fetches and caches pre-built `OHGS` OSM routing squares (`routing-<build>/12/<x>/<y>.bin`)
 * from `tiles.openhikes.app`, handling HTTP `410` archive retirement, shared HTTP `429` backoff,
 * and caller-independent coalescing of in-flight reads.
 */
interface RoutingGraphSquareDataSource {
    suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult
}

@Singleton
@Suppress("LongParameterList")
internal class HttpRoutingGraphSquareDataSource @Inject constructor(
    @TileOkHttpClient private val httpClient: OkHttpClient,
    private val archivesManager: VectorTileArchivesManager,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @RoutingSquaresDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope
) : RoutingGraphSquareDataSource {
    private val inFlight = ConcurrentHashMap<TrailGraphRegion, Deferred<RoutingSquareReadResult>>()
    private val rateLimitedUntilEpochMillis = AtomicLong(0L)

    override suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult {
        if (region.zoom != TrailGraphSquareGrid.REGION_ZOOM) {
            return RoutingSquareReadResult.Graph(TrailGraph.Empty)
        }
        val existing = inFlight[region]
        val active = if (existing != null) {
            existing
        } else {
            val created = applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                loadOrFetch(region)
            }
            val winner = inFlight.putIfAbsent(region, created)
            if (winner != null) {
                created.cancel()
                winner
            } else {
                created.invokeOnCompletion { inFlight.remove(region, created) }
                created.start()
                created
            }
        }
        return active.await()
    }

    private suspend fun loadOrFetch(region: TrailGraphRegion): RoutingSquareReadResult {
        val squareName = TrailGraphSquareGrid.squareName(region)
        val cached = readCachedGraph(squareName, region)
        val currentSource = archivesManager.activeSource.value
        val routingArchive = currentSource?.routing
        return when {
            cached != null -> RoutingSquareReadResult.Graph(cached)
            !tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed ->
                RoutingSquareReadResult.Failed(TrailLegOutage.OFFLINE)
            isRateLimited() -> RoutingSquareReadResult.Failed(TrailLegOutage.RATE_LIMITED)
            routingArchive == null -> RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
            else -> when (val first = fetchSquare(routingArchive.squareTileUrl(region))) {
                is SquareHttpAnswer.Bytes -> decodeAndCache(squareName, region, first.payload)
                SquareHttpAnswer.Empty -> cacheEmptyAndReturn(squareName)
                SquareHttpAnswer.Retired -> retryAfterManifestRefresh(currentSource, squareName, region)
                is SquareHttpAnswer.Error -> RoutingSquareReadResult.Failed(first.outage)
            }
        }
    }

    private suspend fun retryAfterManifestRefresh(
        retiredSource: VectorTileSource,
        squareName: String,
        region: TrailGraphRegion
    ): RoutingSquareReadResult {
        val moved = archivesManager.movedFrom(retiredSource)
        val refreshedRouting = if (moved) archivesManager.activeSource.value?.routing else null
        return when {
            refreshedRouting == null -> RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
            isRateLimited() -> RoutingSquareReadResult.Failed(TrailLegOutage.RATE_LIMITED)
            else -> when (val retry = fetchSquare(refreshedRouting.squareTileUrl(region))) {
                is SquareHttpAnswer.Bytes -> decodeAndCache(squareName, region, retry.payload)
                SquareHttpAnswer.Empty -> cacheEmptyAndReturn(squareName)
                SquareHttpAnswer.Retired -> RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
                is SquareHttpAnswer.Error -> RoutingSquareReadResult.Failed(retry.outage)
            }
        }
    }

    private fun decodeAndCache(
        squareName: String,
        region: TrailGraphRegion,
        payload: ByteArray
    ): RoutingSquareReadResult {
        val square = runCatching { TrailGraphSquareFile.decode(payload) }.getOrNull()
            ?: return RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
        writeCachedFile(squareName, payload)
        return RoutingSquareReadResult.Graph(square.toTrailGraphCrossing(region.toGeoBoundingBox()))
    }

    private fun cacheEmptyAndReturn(squareName: String): RoutingSquareReadResult {
        writeCachedFile(squareName, TrailGraphSquareFile(emptyList()).encode())
        return RoutingSquareReadResult.Graph(TrailGraph.Empty)
    }

    private fun fetchSquare(url: String): SquareHttpAnswer = try {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_OK -> {
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) SquareHttpAnswer.Empty else SquareHttpAnswer.Bytes(bytes)
                }
                HTTP_NO_CONTENT -> SquareHttpAnswer.Empty
                HTTP_GONE -> SquareHttpAnswer.Retired
                RetryAfterParser.HTTP_TOO_MANY_REQUESTS -> {
                    applyRateLimitCooldown(response.header(RETRY_AFTER_HEADER))
                    SquareHttpAnswer.Error(TrailLegOutage.RATE_LIMITED)
                }
                else -> SquareHttpAnswer.Error(TrailLegOutage.UNAVAILABLE)
            }
        }
    } catch (_: IOException) {
        SquareHttpAnswer.Error(TrailLegOutage.UNAVAILABLE)
    }

    private fun isRateLimited(): Boolean = clock.nowEpochMillis() < rateLimitedUntilEpochMillis.get()

    private fun applyRateLimitCooldown(retryAfterHeader: String?) {
        val now = clock.nowEpochMillis()
        val parsed = RetryAfterParser.parseDelayMillis(
            statusCode = RetryAfterParser.HTTP_TOO_MANY_REQUESTS,
            headerValue = retryAfterHeader,
            nowEpochMillis = now
        )
        val deadline = now + (parsed ?: DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS)
        rateLimitedUntilEpochMillis.accumulateAndGet(deadline, ::maxOf)
    }

    private fun readCachedGraph(squareName: String, region: TrailGraphRegion): TrailGraph? {
        val file = safeSquareFile(squareName)?.takeIf { it.isFile }
        val ageMillis = file?.let { clock.nowEpochMillis() - it.lastModified() }
        val freshFile = if (file != null && ageMillis != null && ageMillis in 0L..CACHE_LIFETIME_MILLIS) {
            file
        } else {
            null
        }
        val square = freshFile?.let { f -> runCatching { TrailGraphSquareFile.decode(f.readBytes()) }.getOrNull() }
        return square?.toTrailGraphCrossing(region.toGeoBoundingBox())
    }

    private fun writeCachedFile(squareName: String, bytes: ByteArray) {
        val target = safeSquareFile(squareName) ?: return
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val tmp = File(cacheDir, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(clock.nowEpochMillis())
            trimCacheLocked()
        }
    }

    private fun trimCacheLocked() {
        val files = cacheDir.listFiles { file -> file.isFile && file.name.endsWith(FILE_EXTENSION) } ?: return
        if (files.size <= MAX_CACHED_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_FILES)
            .forEach { it.delete() }
    }

    private fun safeSquareFile(squareName: String): File? {
        if (TrailGraphSquareGrid.regionFromSquareName(squareName) == null) return null
        return File(cacheDir, "$squareName$FILE_EXTENSION")
    }

    private sealed interface SquareHttpAnswer {
        data class Bytes(val payload: ByteArray) : SquareHttpAnswer

        data object Empty : SquareHttpAnswer

        data object Retired : SquareHttpAnswer

        data class Error(val outage: TrailLegOutage) : SquareHttpAnswer
    }

    companion object {
        const val CACHE_DIR_NAME = "routing_graph_squares"
        const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60_000L
        private const val FILE_EXTENSION = ".ohgs"
        private const val MAX_CACHED_FILES = 32
        private const val CACHE_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val RETRY_AFTER_HEADER = "Retry-After"
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_GONE = 410
    }
}
