package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.model.RetryAfterParser
import app.openhikes.core.model.TileAddress
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.VectorTileSource
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Outcome of fetching a single MVT vector tile from `tiles.openhikes.app`.
 */
sealed interface VectorTileFetchResult {
    data class Tile(val bytes: ByteArray) : VectorTileFetchResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Tile && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Missing : VectorTileFetchResult
    data object Unavailable : VectorTileFetchResult
    data object Offline : VectorTileFetchResult
    data object Refused : VectorTileFetchResult
}

/**
 * Fetches online OpenHikes Protomaps basemap and extras vector tiles with in-memory LRU caching,
 * concurrent in-flight request coalescing, HTTP `410 Gone` manifest refresh + retry, and
 * per-archive `Retry-After` failure pauses so extras outages never stall basemap rendering.
 */
@Singleton
class VectorTileNetworkClient @Inject constructor(
    @TileOkHttpClient private val okHttpClient: OkHttpClient,
    private val archivesManager: VectorTileArchivesManager,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val cacheLock = Any()
    private val tileCache = object : LinkedHashMap<String, CachedTileEntry>(CACHE_CAPACITY, CACHE_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTileEntry>?): Boolean =
            size > CACHE_CAPACITY
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<FetchOutcome>>()
    private val pausedUntilByArchive = ConcurrentHashMap<String, Long>()

    suspend fun fetchBasemapTile(address: TileAddress): VectorTileFetchResult =
        fetchTile(address = address, isExtras = false)

    suspend fun fetchExtrasTile(address: TileAddress): VectorTileFetchResult =
        fetchTile(address = address, isExtras = true)

    fun forgetTile(url: String) {
        synchronized(cacheLock) {
            tileCache.remove(url)
        }
    }

    fun isArchivePaused(archiveUrl: String): Boolean {
        val until = pausedUntilByArchive[archiveUrl] ?: return false
        return clock.nowEpochMillis() < until
    }

    private suspend fun fetchTile(address: TileAddress, isExtras: Boolean): VectorTileFetchResult {
        val source = archivesManager.activeSource.value
        return when {
            !address.isValid -> VectorTileFetchResult.Missing
            !tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed -> VectorTileFetchResult.Offline
            source == null -> VectorTileFetchResult.Unavailable
            else -> fetchOrRefreshOnRetired(source, address, isExtras)
        }
    }

    private suspend fun fetchOrRefreshOnRetired(
        source: VectorTileSource,
        address: TileAddress,
        isExtras: Boolean
    ): VectorTileFetchResult {
        val firstOutcome = fetchFromSource(source, address, isExtras)
        return when {
            firstOutcome != FetchOutcome.Retired -> firstOutcome.toResult()
            archivesManager.movedFrom(source) -> {
                val nextSource = archivesManager.activeSource.value
                nextSource?.let { fetchFromSource(it, address, isExtras).toResult() }
                    ?: VectorTileFetchResult.Unavailable
            }

            archivesManager.lastReadFailed -> VectorTileFetchResult.Unavailable
            else -> VectorTileFetchResult.Refused
        }
    }

    private suspend fun fetchFromSource(
        source: VectorTileSource,
        address: TileAddress,
        isExtras: Boolean
    ): FetchOutcome {
        val archiveUrl = if (isExtras) source.extras?.archiveUrl else source.basemapUrl
        val url = if (isExtras) source.extrasTileUrl(address) else source.basemapTileUrl(address)
        val cached = url?.let { synchronized(cacheLock) { tileCache[it] } }
        return when {
            archiveUrl == null || url == null -> FetchOutcome.Missing
            cached is CachedTileEntry.Data -> FetchOutcome.Tile(cached.bytes)
            cached is CachedTileEntry.Missing -> FetchOutcome.Missing
            isArchivePaused(archiveUrl) -> FetchOutcome.Unavailable
            else -> fetchCoalesced(url = url, archiveUrl = archiveUrl)
        }
    }

    private suspend fun fetchCoalesced(url: String, archiveUrl: String): FetchOutcome {
        val newDeferred = CompletableDeferred<FetchOutcome>()
        val existing = inFlight.putIfAbsent(url, newDeferred)
        if (existing != null) {
            return existing.await()
        }
        var outcome: FetchOutcome = FetchOutcome.Unavailable
        return try {
            outcome = executeWithSingleConnectionRetry(url = url, archiveUrl = archiveUrl)
            cacheIfApplicable(url, outcome)
            outcome
        } finally {
            newDeferred.complete(outcome)
            inFlight.remove(url, newDeferred)
        }
    }

    private suspend fun executeWithSingleConnectionRetry(url: String, archiveUrl: String): FetchOutcome =
        withContext(ioDispatcher) {
            var attempt = 0
            while (true) {
                try {
                    return@withContext executeOnce(url = url, archiveUrl = archiveUrl)
                } catch (_: IOException) {
                    if (!tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed) {
                        return@withContext FetchOutcome.Offline
                    }
                    if (attempt >= 1) {
                        pauseArchive(archiveUrl, DEFAULT_FAILURE_PAUSE_MILLIS)
                        return@withContext FetchOutcome.Unavailable
                    }
                    attempt++
                }
            }
            @Suppress("UNREACHABLE_CODE")
            FetchOutcome.Unavailable
        }

    private fun executeOnce(url: String, archiveUrl: String): FetchOutcome {
        val request = Request.Builder().url(url).get().build()
        return okHttpClient.newCall(request).execute().use { response ->
            val code = response.code
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) FetchOutcome.Missing else FetchOutcome.Tile(bytes)
                }

                code == HttpURLConnection.HTTP_NO_CONTENT || code == HttpURLConnection.HTTP_NOT_FOUND ->
                    FetchOutcome.Missing

                code == HttpURLConnection.HTTP_GONE -> FetchOutcome.Retired

                code == RetryAfterParser.HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR_RANGE -> {
                    val now = clock.nowEpochMillis()
                    val retryAfter = RetryAfterParser.parseDelayMillis(code, response.header("Retry-After"), now)
                    pauseArchive(archiveUrl, maxOf(DEFAULT_FAILURE_PAUSE_MILLIS, retryAfter ?: 0L))
                    FetchOutcome.Unavailable
                }

                else -> FetchOutcome.Refused
            }
        }
    }

    private fun pauseArchive(archiveUrl: String, delayMillis: Long) {
        val deadline = clock.nowEpochMillis() + delayMillis
        pausedUntilByArchive.compute(archiveUrl) { _, existing ->
            if (existing == null || deadline > existing) deadline else existing
        }
    }

    private fun cacheIfApplicable(url: String, outcome: FetchOutcome) {
        val entry = when (outcome) {
            is FetchOutcome.Tile -> CachedTileEntry.Data(outcome.bytes)
            FetchOutcome.Missing -> CachedTileEntry.Missing
            else -> return
        }
        synchronized(cacheLock) {
            tileCache[url] = entry
        }
    }

    private sealed interface CachedTileEntry {
        data class Data(val bytes: ByteArray) : CachedTileEntry
        data object Missing : CachedTileEntry
    }

    private sealed interface FetchOutcome {
        data class Tile(val bytes: ByteArray) : FetchOutcome
        data object Missing : FetchOutcome
        data object Retired : FetchOutcome
        data object Unavailable : FetchOutcome
        data object Offline : FetchOutcome
        data object Refused : FetchOutcome

        fun toResult(): VectorTileFetchResult = when (this) {
            is Tile -> VectorTileFetchResult.Tile(bytes)
            Missing -> VectorTileFetchResult.Missing
            Retired, Unavailable -> VectorTileFetchResult.Unavailable
            Offline -> VectorTileFetchResult.Offline
            Refused -> VectorTileFetchResult.Refused
        }
    }

    companion object {
        const val CACHE_CAPACITY = 128
        const val DEFAULT_FAILURE_PAUSE_MILLIS = 60_000L
        private const val CACHE_LOAD_FACTOR = 0.75f
        private val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
