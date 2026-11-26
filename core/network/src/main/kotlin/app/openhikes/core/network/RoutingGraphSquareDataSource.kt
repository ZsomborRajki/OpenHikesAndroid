package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.routing.TrailGraphSquareFile
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphRegion
import app.openhikes.core.model.TrailGraphSquareGrid
import app.openhikes.core.model.TrailLegOutage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
 * from `tiles.openhikes.app`, handling HTTP `410` archive retirement and coalescing in-flight reads.
 */
interface RoutingGraphSquareDataSource {
    suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult
}

@Singleton
internal class HttpRoutingGraphSquareDataSource @Inject constructor(
    @TileOkHttpClient private val httpClient: OkHttpClient,
    private val archivesManager: VectorTileArchivesManager,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @RoutingSquaresDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RoutingGraphSquareDataSource {
    private val inFlight = ConcurrentHashMap<TrailGraphRegion, CompletableDeferred<RoutingSquareReadResult>>()

    override suspend fun readRegion(region: TrailGraphRegion): RoutingSquareReadResult {
        if (region.zoom != TrailGraphSquareGrid.REGION_ZOOM) {
            return RoutingSquareReadResult.Graph(TrailGraph.Empty)
        }
        val existing = inFlight[region]
        val result = if (existing != null) {
            existing.await()
        } else {
            val created = CompletableDeferred<RoutingSquareReadResult>()
            val winner = inFlight.putIfAbsent(region, created)
            if (winner != null) {
                winner.await()
            } else {
                try {
                    withContext(ioDispatcher) { loadOrFetch(region) }.also { created.complete(it) }
                } finally {
                    if (!created.isCompleted) created.cancel()
                    inFlight.remove(region, created)
                }
            }
        }
        return result
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
        retiredSource: app.openhikes.core.model.VectorTileSource,
        squareName: String,
        region: TrailGraphRegion
    ): RoutingSquareReadResult {
        val moved = archivesManager.movedFrom(retiredSource)
        val refreshedRouting = if (moved) archivesManager.activeSource.value?.routing else null
        if (refreshedRouting == null) return RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
        return when (val retry = fetchSquare(refreshedRouting.squareTileUrl(region))) {
            is SquareHttpAnswer.Bytes -> decodeAndCache(squareName, region, retry.payload)
            SquareHttpAnswer.Empty -> cacheEmptyAndReturn(squareName)
            SquareHttpAnswer.Retired -> RoutingSquareReadResult.Failed(TrailLegOutage.UNAVAILABLE)
            is SquareHttpAnswer.Error -> RoutingSquareReadResult.Failed(retry.outage)
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
                HTTP_TOO_MANY_REQUESTS -> SquareHttpAnswer.Error(TrailLegOutage.RATE_LIMITED)
                else -> SquareHttpAnswer.Error(TrailLegOutage.UNAVAILABLE)
            }
        }
    } catch (_: IOException) {
        SquareHttpAnswer.Error(TrailLegOutage.UNAVAILABLE)
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
        private const val FILE_EXTENSION = ".ohgs"
        private const val MAX_CACHED_FILES = 32
        private const val CACHE_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_GONE = 410
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
