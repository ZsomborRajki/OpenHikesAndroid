package app.openhikes.core.network

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.model.ElevationArchiveSource
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.RetryAfterParser
import app.openhikes.core.model.TileNetworkPurpose
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of fetching a batch of pre-built `OHEL` Copernicus DEM elevation squares.
 */
sealed interface ElevationSquaresBatchAnswer {
    /**
     * Every requested square resolved cleanly; a `null` value denotes sea level (`HTTP 204` or
     * empty body in a complete worldwide elevation archive).
     */
    data class Loaded(val squares: Map<ElevationSquare, ElevationSquareFile?>) : ElevationSquaresBatchAnswer

    data object Uncovered : ElevationSquaresBatchAnswer

    data object Unreachable : ElevationSquaresBatchAnswer
}

/**
 * Fetches and caches `OHEL` v1 Copernicus DEM elevation squares
 * (`elevation-<build>/12/<col>/<row>.bin`) from `tiles.openhikes.app`.
 */
interface ElevationSquareDataSource {
    suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer
}

@Singleton
@Suppress("LongParameterList")
internal class HttpElevationSquareDataSource @Inject constructor(
    @TileOkHttpClient private val httpClient: OkHttpClient,
    private val archivesManager: VectorTileArchivesManager,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @ElevationSquaresDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ElevationSquareDataSource {
    private val inFlight = ConcurrentHashMap<SquareFetchKey, Deferred<SquareHttpAnswer>>()
    private val rateLimitedUntilEpochMillis = AtomicLong(0L)
    private val requestGate = Semaphore(CONCURRENT_REQUESTS)

    override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer {
        val unique = squares.distinct()
        if (unique.isEmpty()) return ElevationSquaresBatchAnswer.Loaded(emptyMap())
        val cached = withContext(ioDispatcher) { readCachedSquares(unique) }
        val missing = unique.filter { it !in cached }
        val currentSource = archivesManager.activeSource.value
        val archive = currentSource?.elevation
        return when {
            missing.isEmpty() -> ElevationSquaresBatchAnswer.Loaded(cached)
            archive == null -> ElevationSquaresBatchAnswer.Uncovered
            !canIssueNetworkRequest() -> ElevationSquaresBatchAnswer.Unreachable
            else -> fetchBatchWithRetirementRetry(
                cached = cached,
                missing = missing,
                currentSource = currentSource,
                archive = archive
            )
        }
    }

    private suspend fun fetchBatchWithRetirementRetry(
        cached: Map<ElevationSquare, ElevationSquareFile?>,
        missing: List<ElevationSquare>,
        currentSource: VectorTileSource,
        archive: ElevationArchiveSource
    ): ElevationSquaresBatchAnswer {
        val firstRound = fetchMissingFromArchive(missing, archive)
        if (!firstRound.values.any { it is SquareHttpAnswer.Retired }) {
            return finalizeBatch(cached, missing, firstRound)
        }
        val moved = archivesManager.movedFrom(retiredSource = currentSource)
        val refreshed = if (moved) archivesManager.activeSource.value?.elevation else null
        return when {
            refreshed == null -> ElevationSquaresBatchAnswer.Uncovered
            !canIssueNetworkRequest() -> ElevationSquaresBatchAnswer.Unreachable
            else -> finalizeBatch(cached, missing, fetchMissingFromArchive(missing, refreshed))
        }
    }

    private suspend fun fetchMissingFromArchive(
        missing: List<ElevationSquare>,
        archive: ElevationArchiveSource
    ): Map<ElevationSquare, SquareHttpAnswer> = coroutineScope {
        missing.map { square ->
            async {
                square to fetchSquareCoalesced(square, archive)
            }
        }.awaitAll().toMap()
    }

    private suspend fun fetchSquareCoalesced(
        square: ElevationSquare,
        archive: ElevationArchiveSource
    ): SquareHttpAnswer {
        if (!canIssueNetworkRequest()) return SquareHttpAnswer.Error
        val key = SquareFetchKey(archiveName = archive.archiveName, square = square)
        val existing = inFlight[key]
        val active = if (existing != null) {
            existing
        } else {
            val created = applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                requestGate.withPermit {
                    if (!canIssueNetworkRequest()) {
                        SquareHttpAnswer.Error
                    } else {
                        fetchSquareHttp(archive.squareTileUrl(square))
                    }
                }
            }
            val winner = inFlight.putIfAbsent(key, created)
            if (winner != null) {
                created.cancel()
                winner
            } else {
                created.invokeOnCompletion { inFlight.remove(key, created) }
                created.start()
                created
            }
        }
        return active.await()
    }

    private suspend fun finalizeBatch(
        cached: Map<ElevationSquare, ElevationSquareFile?>,
        missing: List<ElevationSquare>,
        answers: Map<ElevationSquare, SquareHttpAnswer>
    ): ElevationSquaresBatchAnswer {
        val result = LinkedHashMap<ElevationSquare, ElevationSquareFile?>(cached.size + missing.size)
        result.putAll(cached)
        val toCache = ArrayList<Pair<String, ByteArray>>(missing.size)
        var failed = false
        for (square in missing) {
            if (failed) break
            val name = ElevationSquareGrid.recordName(square)
            when (val answer = answers[square]) {
                is SquareHttpAnswer.Bytes -> {
                    val decoded = decodeValidated(answer.payload, square)
                    if (decoded == null) {
                        failed = true
                    } else {
                        result[square] = decoded
                        toCache.add(name to answer.payload)
                    }
                }
                SquareHttpAnswer.Sea -> {
                    result[square] = null
                    toCache.add(name to ByteArray(0))
                }
                SquareHttpAnswer.Retired,
                SquareHttpAnswer.Error,
                null -> failed = true
            }
        }
        if (failed) return ElevationSquaresBatchAnswer.Unreachable
        if (toCache.isNotEmpty()) {
            withContext(ioDispatcher) {
                toCache.forEach { (name, bytes) -> writeCachedFile(name, bytes) }
                trimCache()
            }
        }
        return ElevationSquaresBatchAnswer.Loaded(result)
    }

    private fun fetchSquareHttp(url: String): SquareHttpAnswer = try {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_OK -> {
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) SquareHttpAnswer.Sea else SquareHttpAnswer.Bytes(bytes)
                }
                HTTP_NO_CONTENT -> SquareHttpAnswer.Sea
                HTTP_GONE -> SquareHttpAnswer.Retired
                RetryAfterParser.HTTP_TOO_MANY_REQUESTS -> {
                    applyRateLimitCooldown(response.header(RETRY_AFTER_HEADER))
                    SquareHttpAnswer.Error
                }
                else -> SquareHttpAnswer.Error
            }
        }
    } catch (_: IOException) {
        SquareHttpAnswer.Error
    }

    private fun readCachedSquares(squares: List<ElevationSquare>): Map<ElevationSquare, ElevationSquareFile?> {
        val found = LinkedHashMap<ElevationSquare, ElevationSquareFile?>()
        for (square in squares) {
            when (val entry = readCachedSquare(square)) {
                is CachedSquareEntry.Land -> found[square] = entry.file
                CachedSquareEntry.Sea -> found[square] = null
                CachedSquareEntry.Miss -> Unit
            }
        }
        return found
    }

    private fun readCachedSquare(square: ElevationSquare): CachedSquareEntry {
        val name = ElevationSquareGrid.recordName(square)
        val file = safeSquareFile(name)?.takeIf { it.isFile }
        val age = file?.let { clock.nowEpochMillis() - it.lastModified() }
        val bytes = if (file != null && age != null && age in 0L..CACHE_LIFETIME_MILLIS) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            null
        }
        return when {
            bytes == null -> CachedSquareEntry.Miss
            bytes.isEmpty() -> CachedSquareEntry.Sea
            else -> decodeValidated(bytes, square)?.let(CachedSquareEntry::Land) ?: CachedSquareEntry.Miss
        }
    }

    private sealed interface CachedSquareEntry {
        data class Land(val file: ElevationSquareFile) : CachedSquareEntry

        data object Sea : CachedSquareEntry

        data object Miss : CachedSquareEntry
    }

    private fun writeCachedFile(squareName: String, bytes: ByteArray) {
        val target = safeSquareFile(squareName) ?: return
        var tempFile: File? = null
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val uniqueTmp = Files.createTempFile(cacheDir.toPath(), "${target.name}.", ".tmp").toFile()
            tempFile = uniqueTmp
            FileOutputStream(uniqueTmp).use { out ->
                out.write(bytes)
                out.flush()
            }
            uniqueTmp.setLastModified(clock.nowEpochMillis())
            try {
                Files.move(
                    uniqueTmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(uniqueTmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            tempFile = null
        }.onFailure {
            tempFile?.delete()
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
        if (ElevationSquareGrid.squareNamed(squareName) == null) return null
        return File(cacheDir, "$squareName$FILE_EXTENSION")
    }

    private fun canIssueNetworkRequest(): Boolean =
        tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed && !isRateLimited()

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

    private data class SquareFetchKey(val archiveName: String, val square: ElevationSquare)

    private sealed interface SquareHttpAnswer {
        data class Bytes(val payload: ByteArray) : SquareHttpAnswer

        data object Sea : SquareHttpAnswer

        data object Retired : SquareHttpAnswer

        data object Error : SquareHttpAnswer
    }

    companion object {
        const val CACHE_DIR_NAME = "elevation_squares"
        const val MAX_CACHED_FILES = 256
        private const val CONCURRENT_REQUESTS = 8
        private const val FILE_EXTENSION = ".ohel"
        private const val CACHE_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60_000L
        private const val RETRY_AFTER_HEADER = "Retry-After"
        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_GONE = 410

        internal fun decodeValidated(bytes: ByteArray, square: ElevationSquare): ElevationSquareFile? {
            val file = runCatching { ElevationSquareFile.decode(bytes) }.getOrNull() ?: return null
            val expectedCols = ElevationSquareGrid.columnsPerSquare(square.row)
            return file.takeIf { it.rows == ElevationSquareGrid.ROWS_PER_SQUARE && it.columns == expectedCols }
        }
    }
}
