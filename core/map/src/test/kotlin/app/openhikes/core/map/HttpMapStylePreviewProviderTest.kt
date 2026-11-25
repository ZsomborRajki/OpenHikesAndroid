package app.openhikes.core.map

import android.graphics.Bitmap
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyle
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.MapStyleKind
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TilePreviewFrame
import app.openhikes.core.network.TileNetworkPolicy
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cache
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpMapStylePreviewProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun returnsNullWithoutNetworkForVectorStylesMissingKeyOrOfflinePolicy() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server = MockWebServer().apply { start() }
        try {
            val onlineProvider = HttpMapStylePreviewProvider(
                okHttpClient = OkHttpClient(),
                tileNetworkPolicy = stubPolicy(isOnline = true),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )
            val offlineProvider = HttpMapStylePreviewProvider(
                okHttpClient = OkHttpClient(),
                tileNetworkPolicy = stubPolicy(isOnline = false),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )
            val frame = TilePreviewFrame.around(null)

            assertThat(onlineProvider.loadPreview(MapStyleCatalog.openHikes, frame)).isNull()
            assertThat(onlineProvider.loadPreview(MapStyleCatalog.openFreeMap, frame)).isNull()
            assertThat(onlineProvider.loadPreview(MapStyleCatalog.stadiaOutdoors, frame)).isNull()

            val localRaster = testRasterStyle(tileTemplate(server))
            assertThat(offlineProvider.loadPreview(localRaster, frame)).isNull()
            assertThat(server.requestCount).isEqualTo(0)
        } finally {
            server.close()
        }
    }

    @Test
    fun fetchesAllFourTilesCompositesAndCachesResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pngBytes = encodeSolidTilePng()
        val requestCount = AtomicInteger(0)
        val server = MockWebServer().apply {
            this.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    return MockResponse.Builder()
                        .code(200)
                        .body(Buffer().write(pngBytes))
                        .build()
                }
            }
            start()
        }
        try {
            val provider = HttpMapStylePreviewProvider(
                okHttpClient = OkHttpClient(),
                tileNetworkPolicy = stubPolicy(isOnline = true),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )
            val style = testRasterStyle(tileTemplate(server))
            val frame = TilePreviewFrame.around(null)

            val first = provider.loadPreview(style, frame)
            assertThat(first).isNotNull()
            assertThat(first!!.width).isEqualTo(8)
            assertThat(first.height).isEqualTo(8)
            assertThat(requestCount.get()).isEqualTo(4)

            val cached = provider.loadPreview(style, frame)
            assertThat(cached).isSameInstanceAs(first)
            assertThat(requestCount.get()).isEqualTo(4)
        } finally {
            server.close()
        }
    }

    @Test
    fun partialTileFailureReturnsNullInsteadOfBrokenComposite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pngBytes = encodeSolidTilePng()
        val calls = AtomicInteger(0)
        val server = MockWebServer().apply {
            this.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (calls.incrementAndGet() == 4) {
                    MockResponse.Builder().code(503).build()
                } else {
                    MockResponse.Builder().code(200).body(Buffer().write(pngBytes)).build()
                }
            }
            start()
        }
        try {
            val provider = HttpMapStylePreviewProvider(
                okHttpClient = OkHttpClient(),
                tileNetworkPolicy = stubPolicy(isOnline = true),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )
            val style = testRasterStyle(tileTemplate(server))
            assertThat(provider.loadPreview(style, TilePreviewFrame.around(null))).isNull()
        } finally {
            server.close()
        }
    }

    @Test
    fun persistentHttpCacheReusesFreshTilesAcrossProviderInstancesAndOverlappingFrames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pngBytes = encodeSolidTilePng()
        val requestCount = AtomicInteger(0)
        val server = MockWebServer().apply {
            this.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Cache-Control", "public, max-age=604800")
                        .body(Buffer().write(pngBytes))
                        .build()
                }
            }
            start()
        }
        val cacheDir = tempFolder.newFolder("preview-http-cache")
        val cachedClient = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 4L * 1024L * 1024L))
            .build()
        try {
            val style = testRasterStyle(tileTemplate(server))
            val frame = TilePreviewFrame.around(null)

            val firstProvider = HttpMapStylePreviewProvider(
                okHttpClient = cachedClient,
                tileNetworkPolicy = stubPolicy(isOnline = true),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )
            val secondProvider = HttpMapStylePreviewProvider(
                okHttpClient = cachedClient,
                tileNetworkPolicy = stubPolicy(isOnline = true),
                mapProviderKeys = MapProviderKeys.Empty,
                ioDispatcher = dispatcher
            )

            assertThat(firstProvider.loadPreview(style, frame)).isNotNull()
            assertThat(requestCount.get()).isEqualTo(4)

            // A brand-new provider instance reuses the persisted HTTP cache instead of re-downloading.
            assertThat(secondProvider.loadPreview(style, frame)).isNotNull()
            assertThat(requestCount.get()).isEqualTo(4)

            // An overlapping adjacent 2x2 frame shares two tiles and fetches only the two new ones.
            val overlappingFrame = TilePreviewFrame(z = frame.z, x = frame.x + 1, y = frame.y)
            assertThat(secondProvider.loadPreview(style, overlappingFrame)).isNotNull()
            assertThat(requestCount.get()).isEqualTo(6)
        } finally {
            cachedClient.cache?.close()
            server.close()
        }
    }

    private fun tileTemplate(server: MockWebServer): String = "${server.url("/tiles/")}{z}/{x}/{y}.png"

    private fun testRasterStyle(urlTemplate: String): MapStyle = MapStyle(
        id = "test_raster",
        kind = MapStyleKind.RASTER_TILES,
        urlTemplate = urlTemplate,
        maximumZoom = 19,
        attribution = MapStyleCatalog.openStreetMapRaster.attribution,
        supportsOfflineCountries = false,
        supportsBulkDownload = false,
        apiKeySlot = null
    )

    private fun encodeSolidTilePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun stubPolicy(isOnline: Boolean): TileNetworkPolicy = object : TileNetworkPolicy {
        override val conditions: StateFlow<TileNetworkConditions> =
            MutableStateFlow(TileNetworkConditions(isOnline = isOnline))
        override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(isOnline)
        override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
            TileNetworkPolicyEvaluator.evaluate(purpose, conditions.value)
    }
}
