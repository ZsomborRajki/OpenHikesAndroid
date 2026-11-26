package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.places.OverpassRemarkException
import app.openhikes.core.common.places.TrailPointDecoding
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PlaceSearchArea
import app.openhikes.core.model.PlaceSearchBox
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.RetryAfterParser
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.model.TrailPointSearchResult
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback OpenStreetMap Overpass API place search used only when pre-built `OHPS` squares do not
 * cover the search area or could not be reached.
 */
interface OverpassPlaceDataSource {
    suspend fun queryPlaces(boxes: List<PlaceSearchBox>, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult

    suspend fun readCachedPlaces(area: PlaceSearchArea, limit: Int): List<TrailPoint>
}

@Singleton
@Suppress("LongParameterList")
internal class HttpOverpassPlaceDataSource @Inject constructor(
    @ManifestOkHttpClient private val httpClient: OkHttpClient,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @OverpassEndpointUrl private val endpointUrl: String,
    @OverpassPlacesDir private val cacheDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : OverpassPlaceDataSource {
    private val rateLimitedUntilEpochMillis = AtomicLong(0L)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun queryPlaces(
        boxes: List<PlaceSearchBox>,
        symbols: Set<TrailPlaceSymbol>
    ): TrailPointSearchResult = withContext(ioDispatcher) {
        val query = TrailPointQuery.overpassQuery(boxes, symbols)
            ?: return@withContext TrailPointSearchResult.Places(emptyList())
        when {
            !tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed ->
                TrailPointSearchResult.Failed(PlaceSearchOutage.OFFLINE)
            clock.nowEpochMillis() < rateLimitedUntilEpochMillis.get() ->
                TrailPointSearchResult.Failed(PlaceSearchOutage.RATE_LIMITED)
            else -> executeQuery(query, symbols)
        }
    }

    override suspend fun readCachedPlaces(area: PlaceSearchArea, limit: Int): List<TrailPoint> =
        withContext(ioDispatcher) {
            if (limit <= 0 || !cacheDir.isDirectory) return@withContext emptyList()
            val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(CACHE_EXT) }
                ?: return@withContext emptyList()
            val now = clock.nowEpochMillis()
            val matched = ArrayList<Pair<TrailPoint, Double>>()
            for (file in files) {
                val age = now - file.lastModified()
                val cached = if (age in 0L..CACHE_LIFETIME_MILLIS) {
                    runCatching { json.decodeFromString<CachedPlaceRecord>(file.readText()) }.getOrNull()
                } else {
                    null
                }
                val place = cached?.toTrailPoint()
                if (place != null && area.contains(place.coordinate)) {
                    matched.add(place to RouteGeometry.distanceMeters(area.center, place.coordinate))
                }
            }
            matched.sortedBy { it.second }.take(limit).map { it.first }
        }

    private fun executeQuery(query: String, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult = try {
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder().url(endpointUrl).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_OK -> decodeResponse(response.body.bytes(), symbols)
                RetryAfterParser.HTTP_TOO_MANY_REQUESTS, HTTP_GATEWAY_TIMEOUT -> {
                    applyRateLimitCooldown(response.header(RETRY_AFTER_HEADER))
                    TrailPointSearchResult.Failed(PlaceSearchOutage.RATE_LIMITED)
                }
                else -> TrailPointSearchResult.Failed(PlaceSearchOutage.UNAVAILABLE)
            }
        }
    } catch (_: IOException) {
        TrailPointSearchResult.Failed(PlaceSearchOutage.UNAVAILABLE)
    }

    private fun decodeResponse(bytes: ByteArray, symbols: Set<TrailPlaceSymbol>): TrailPointSearchResult = try {
        val decoded = TrailPointDecoding.decodeOverpassResponse(bytes, symbols)
        saveToCache(decoded)
        TrailPointSearchResult.Places(decoded)
    } catch (_: OverpassRemarkException) {
        TrailPointSearchResult.Failed(PlaceSearchOutage.RATE_LIMITED)
    } catch (_: IllegalArgumentException) {
        TrailPointSearchResult.Failed(PlaceSearchOutage.UNAVAILABLE)
    }

    private fun applyRateLimitCooldown(retryAfterHeader: String?) {
        val now = clock.nowEpochMillis()
        val parsed = RetryAfterParser.parseDelayMillis(
            statusCode = RetryAfterParser.HTTP_TOO_MANY_REQUESTS,
            headerValue = retryAfterHeader,
            nowEpochMillis = now
        )
        val deadline = now + (parsed ?: DEFAULT_COOLDOWN_MILLIS)
        rateLimitedUntilEpochMillis.accumulateAndGet(deadline, ::maxOf)
    }

    private fun saveToCache(places: List<TrailPoint>) {
        if (places.isEmpty()) return
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val now = clock.nowEpochMillis()
            for (place in places) {
                val osm = place.osm
                if (osm != null && osm.elementType in TrailPlaceOsm.ELEMENT_TYPES && osm.elementId > 0L) {
                    val file = File(cacheDir, "${osm.elementType}_${osm.elementId}$CACHE_EXT")
                    file.writeText(json.encodeToString(CachedPlaceRecord.from(place)))
                    file.setLastModified(now)
                }
            }
            trimCache()
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(CACHE_EXT) } ?: return
        if (files.size <= MAX_CACHED_PLACES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_PLACES)
            .forEach { it.delete() }
    }

    @Serializable
    private data class CachedPlaceRecord(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val name: String,
        val symbol: String?,
        val osm: TrailPlaceOsm?
    ) {
        fun toTrailPoint(): TrailPoint = TrailPoint(
            id = id,
            hikeId = HikeId("unassigned"),
            coordinate = GeoCoordinate(latitude = latitude, longitude = longitude),
            name = name,
            symbol = symbol,
            osm = osm
        )

        companion object {
            fun from(place: TrailPoint): CachedPlaceRecord = CachedPlaceRecord(
                id = place.id,
                latitude = place.coordinate.latitude,
                longitude = place.coordinate.longitude,
                name = place.name,
                symbol = place.symbol,
                osm = place.osm
            )
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val CACHE_DIR_NAME = "overpass_places_cache"
        private const val CACHE_EXT = ".json"
        private const val MAX_CACHED_PLACES = 500
        private const val CACHE_LIFETIME_MILLIS = 30L * 24L * 60L * 60L * 1000L
        private const val DEFAULT_COOLDOWN_MILLIS = 60_000L
        private const val RETRY_AFTER_HEADER = "Retry-After"
        private const val HTTP_OK = 200
        private const val HTTP_GATEWAY_TIMEOUT = 504
    }
}
