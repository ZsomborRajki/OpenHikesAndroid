package app.openhikes.core.network

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.places.PlaceSquareFile
import app.openhikes.core.model.PlaceSearchBox
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.PlaceSquare
import app.openhikes.core.model.PlaceSquareGrid
import app.openhikes.core.model.RetryAfterParser
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of reading pre-built `OHPS` v2 OpenStreetMap place squares for a search region.
 */
sealed interface PlaceSquaresAnswer {
    data class Places(val places: List<TrailPoint>) : PlaceSquaresAnswer

    data object Uncovered : PlaceSquaresAnswer

    data class Unreachable(val outage: PlaceSearchOutage) : PlaceSquaresAnswer
}

/**
 * Fetches and caches pre-built `OHPS` v2 places squares (`places-<build>/9/<col>/<row>.bin`)
 * from `tiles.openhikes.app`.
 */
interface PlaceSquareDataSource {
    suspend fun placesInBoxes(boxes: List<PlaceSearchBox>, symbols: Set<TrailPlaceSymbol>): PlaceSquaresAnswer

    suspend fun cachedPlacesInBoxes(boxes: List<PlaceSearchBox>, symbols: Set<TrailPlaceSymbol>): List<TrailPoint>
}

@Singleton
@Suppress("LongParameterList")
internal class HttpPlaceSquareDataSource @Inject constructor(
    @TileOkHttpClient private val httpClient: OkHttpClient,
    private val archivesManager: VectorTileArchivesManager,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @PlaceSquaresDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope
) : PlaceSquareDataSource {
    private val inFlight = ConcurrentHashMap<PlaceSquare, Deferred<SingleSquareResult>>()
    private val rateLimitedUntilEpochMillis = AtomicLong(0L)

    override suspend fun placesInBoxes(
        boxes: List<PlaceSearchBox>,
        symbols: Set<TrailPlaceSymbol>
    ): PlaceSquaresAnswer {
        val squares = boxes.flatMap(PlaceSquareGrid::squaresCovering).distinct()
        val decoded = ArrayList<PlaceSquareFile>(squares.size)
        var failure: PlaceSquaresAnswer? = if (
            squares.isEmpty() || squares.size > TrailPointQuery.MAXIMUM_SQUARES_PER_SEARCH
        ) {
            PlaceSquaresAnswer.Uncovered
        } else {
            null
        }
        var index = 0
        while (failure == null && index < squares.size) {
            when (val result = readSquare(squares[index])) {
                is SingleSquareResult.Loaded -> decoded.add(result.file)
                SingleSquareResult.Uncovered -> failure = PlaceSquaresAnswer.Uncovered
                is SingleSquareResult.Failed -> failure = PlaceSquaresAnswer.Unreachable(result.outage)
            }
            index += 1
        }
        return failure ?: PlaceSquaresAnswer.Places(
            PlaceSquareFile.extractPlaces(decoded, symbols) { _, ground ->
                boxes.any { box -> box.overlaps(ground) }
            }
        )
    }

    override suspend fun cachedPlacesInBoxes(
        boxes: List<PlaceSearchBox>,
        symbols: Set<TrailPlaceSymbol>
    ): List<TrailPoint> = withContext(ioDispatcher) {
        val squares = boxes.flatMap(PlaceSquareGrid::squaresCovering).distinct()
        if (squares.isEmpty() || squares.size > TrailPointQuery.MAXIMUM_SQUARES_PER_SEARCH) {
            return@withContext emptyList()
        }
        val files = squares.mapNotNull { square -> readCachedSquare(PlaceSquareGrid.recordName(square)) }
        PlaceSquareFile.extractPlaces(files, symbols) { _, ground ->
            boxes.any { box -> box.overlaps(ground) }
        }
    }

    private suspend fun readSquare(square: PlaceSquare): SingleSquareResult {
        val existing = inFlight[square]
        val active = if (existing != null) {
            existing
        } else {
            val created = applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                loadOrFetchSquare(square)
            }
            val winner = inFlight.putIfAbsent(square, created)
            if (winner != null) {
                created.cancel()
                winner
            } else {
                created.invokeOnCompletion { inFlight.remove(square, created) }
                created.start()
                created
            }
        }
        return active.await()
    }

    private suspend fun loadOrFetchSquare(square: PlaceSquare): SingleSquareResult {
        val name = PlaceSquareGrid.recordName(square)
        val cached = readCachedSquare(name)
        val currentSource = archivesManager.activeSource.value
        val placesArchive = currentSource?.places
        return when {
            cached != null -> SingleSquareResult.Loaded(cached)
            placesArchive == null -> SingleSquareResult.Uncovered
            !tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed ->
                SingleSquareResult.Failed(PlaceSearchOutage.OFFLINE)
            isRateLimited() -> SingleSquareResult.Failed(PlaceSearchOutage.RATE_LIMITED)
            else -> when (val first = fetchSquare(placesArchive.squareTileUrl(square))) {
                is SquareHttpAnswer.Bytes -> decodeAndCache(name, first.payload)
                SquareHttpAnswer.Empty -> cacheEmptyAndReturn(name)
                SquareHttpAnswer.Retired -> retryAfterManifestRefresh(currentSource, name, square)
                is SquareHttpAnswer.Error -> SingleSquareResult.Failed(first.outage)
            }
        }
    }

    private suspend fun retryAfterManifestRefresh(
        retiredSource: VectorTileSource,
        squareName: String,
        square: PlaceSquare
    ): SingleSquareResult {
        val moved = archivesManager.movedFrom(retiredSource)
        val refreshedPlaces = if (moved) archivesManager.activeSource.value?.places else null
        return when {
            refreshedPlaces == null -> SingleSquareResult.Uncovered
            isRateLimited() -> SingleSquareResult.Failed(PlaceSearchOutage.RATE_LIMITED)
            else -> when (val retry = fetchSquare(refreshedPlaces.squareTileUrl(square))) {
                is SquareHttpAnswer.Bytes -> decodeAndCache(squareName, retry.payload)
                SquareHttpAnswer.Empty -> cacheEmptyAndReturn(squareName)
                SquareHttpAnswer.Retired -> SingleSquareResult.Failed(PlaceSearchOutage.UNAVAILABLE)
                is SquareHttpAnswer.Error -> SingleSquareResult.Failed(retry.outage)
            }
        }
    }

    private fun decodeAndCache(squareName: String, payload: ByteArray): SingleSquareResult {
        val square = runCatching { PlaceSquareFile.decode(payload) }.getOrNull()
            ?: return SingleSquareResult.Failed(PlaceSearchOutage.UNAVAILABLE)
        writeCachedFile(squareName, payload)
        return SingleSquareResult.Loaded(square)
    }

    private fun cacheEmptyAndReturn(squareName: String): SingleSquareResult {
        val empty = PlaceSquareFile(emptyList())
        writeCachedFile(squareName, empty.encode())
        return SingleSquareResult.Loaded(empty)
    }

    private fun fetchSquare(url: String): SquareHttpAnswer = try {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_OK -> {
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) SquareHttpAnswer.Empty else SquareHttpAnswer.Bytes(bytes)
                }
                HTTP_NO_CONTENT, HTTP_NOT_FOUND -> SquareHttpAnswer.Empty
                HTTP_GONE -> SquareHttpAnswer.Retired
                RetryAfterParser.HTTP_TOO_MANY_REQUESTS -> {
                    applyRateLimitCooldown(response.header(RETRY_AFTER_HEADER))
                    SquareHttpAnswer.Error(PlaceSearchOutage.RATE_LIMITED)
                }
                else -> SquareHttpAnswer.Error(PlaceSearchOutage.UNAVAILABLE)
            }
        }
    } catch (_: IOException) {
        SquareHttpAnswer.Error(PlaceSearchOutage.UNAVAILABLE)
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

    private fun readCachedSquare(squareName: String): PlaceSquareFile? {
        val file = safeSquareFile(squareName)?.takeIf { it.isFile }
        val fresh = file?.takeIf { (clock.nowEpochMillis() - it.lastModified()) in 0L..CACHE_LIFETIME_MILLIS }
        return fresh?.let { runCatching { PlaceSquareFile.decode(it.readBytes()) }.getOrNull() }
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
            trimCache()
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXTENSION) } ?: return
        if (files.size <= MAX_CACHED_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_FILES)
            .forEach { it.delete() }
    }

    private fun safeSquareFile(squareName: String): File? {
        if (PlaceSquareGrid.squareNamed(squareName) == null) return null
        return File(cacheDir, "$squareName$FILE_EXTENSION")
    }

    private sealed interface SingleSquareResult {
        data class Loaded(val file: PlaceSquareFile) : SingleSquareResult

        data object Uncovered : SingleSquareResult

        data class Failed(val outage: PlaceSearchOutage) : SingleSquareResult
    }

    private sealed interface SquareHttpAnswer {
        data class Bytes(val payload: ByteArray) : SquareHttpAnswer

        data object Empty : SquareHttpAnswer

        data object Retired : SquareHttpAnswer

        data class Error(val outage: PlaceSearchOutage) : SquareHttpAnswer
    }

    companion object {
        const val CACHE_DIR_NAME = "place_squares"
        private const val FILE_EXTENSION = ".ohps"
        private const val MAX_CACHED_FILES = 100
        private const val CACHE_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60_000L
        private const val RETRY_AFTER_HEADER = "Retry-After"
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410
    }
}
