package app.openhikes.core.network

import app.openhikes.core.common.routing.TrailGraphSquareFile
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphRegion
import app.openhikes.core.model.TrailGraphSquareGrid
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpRoutingGraphSquareDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("routing-squares-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun fetchesDecodesCachesAndServesFromDiskWithoutSecondRequest() = runTest {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sampleSquareBytes())).build())

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = harness.dataSource.readRegion(region)
        val second = harness.dataSource.readRegion(region)

        assertThat(first).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
        val graph = (first as RoutingSquareReadResult.Graph).graph
        assertThat(graph.edges).hasSize(1)
        assertThat(graph.edges.first().name).isEqualTo("Ridge Path")
        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(1)
        harness.scope.cancel()
    }

    @Test
    fun readCachedRegionReturnsNullWhenUncachedAndGraphOnceCachedWithoutNetwork() = runTest {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        val uncachedRegion = TrailGraphRegion(zoom = 12, x = 200, y = 200)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sampleSquareBytes())).build())

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        assertThat(harness.dataSource.readCachedRegion(region)).isNull()
        assertThat(server.requestCount).isEqualTo(0)

        assertThat(harness.dataSource.readRegion(region)).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
        assertThat(server.requestCount).isEqualTo(1)

        val cached = harness.dataSource.readCachedRegion(region)
        assertThat(cached?.edges).hasSize(1)
        assertThat(harness.dataSource.readCachedRegion(uncachedRegion)).isNull()
        assertThat(server.requestCount).isEqualTo(1)
        harness.scope.cancel()
    }

    @Test
    fun returnsOfflineOutageWhenInteractiveNetworkBlockedAndUncached() = runTest {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        val harness = createDataSource(allowsNetwork = false, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = harness.dataSource.readRegion(region)

        assertThat(result).isEqualTo(RoutingSquareReadResult.Failed(TrailLegOutage.OFFLINE))
        assertThat(server.requestCount).isEqualTo(0)
        harness.scope.cancel()
    }

    @Test
    fun handlesHttp204EmptyAndHttp429RateLimitBackoffWhileServingCachedSquares() = runTest {
        val cachedRegion = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        val emptyRegion = TrailGraphRegion(zoom = 12, x = 100, y = 100)
        val limitedRegionA = TrailGraphRegion(zoom = 12, x = 101, y = 100)
        val limitedRegionB = TrailGraphRegion(zoom = 12, x = 102, y = 100)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sampleSquareBytes())).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
        server.enqueue(MockResponse.Builder().code(204).build())

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        assertThat(harness.dataSource.readRegion(cachedRegion)).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
        assertThat(harness.dataSource.readRegion(emptyRegion))
            .isEqualTo(RoutingSquareReadResult.Graph(TrailGraph.Empty))
        assertThat(harness.dataSource.readRegion(limitedRegionA))
            .isEqualTo(RoutingSquareReadResult.Failed(TrailLegOutage.RATE_LIMITED))
        assertThat(server.requestCount).isEqualTo(3)

        // Second uncached square during Retry-After cooldown returns RATE_LIMITED without hitting the network.
        assertThat(harness.dataSource.readRegion(limitedRegionB))
            .isEqualTo(RoutingSquareReadResult.Failed(TrailLegOutage.RATE_LIMITED))
        assertThat(server.requestCount).isEqualTo(3)

        // Cached square continues to be served from disk during the cooldown.
        assertThat(harness.dataSource.readRegion(cachedRegion)).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
        assertThat(server.requestCount).isEqualTo(3)

        // Once Retry-After elapses, uncached squares resume network fetches.
        harness.clock.advanceBy(Duration.ofSeconds(121L))
        assertThat(harness.dataSource.readRegion(limitedRegionB))
            .isEqualTo(RoutingSquareReadResult.Graph(TrailGraph.Empty))
        assertThat(server.requestCount).isEqualTo(4)
        harness.scope.cancel()
    }

    @Test
    fun cancellingFirstReaderKeepsSharedFetchAliveForSecondReader() = runBlocking {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val payload = sampleSquareBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).body(Buffer().write(payload)).build()
            }
        }

        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { pool ->
            val harness = createDataSource(allowsNetwork = true, ioDispatcher = pool)
            val firstReader = launch(pool) {
                harness.dataSource.readRegion(region)
            }
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue()

            val secondReader = async(pool) {
                harness.dataSource.readRegion(region)
            }
            firstReader.cancelAndJoin()
            allowResponse.countDown()

            val secondResult = secondReader.await()
            assertThat(secondResult).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
            assertThat((secondResult as RoutingSquareReadResult.Graph).graph.edges).hasSize(1)
            assertThat(server.requestCount).isEqualTo(1)
            harness.scope.cancel()
        }
    }

    private fun sampleSquareBytes(): ByteArray = TrailGraphSquareFile(
        ways = listOf(
            TrailGraphSquareFile.Way(
                id = 10L,
                highway = "path",
                tags = mapOf(TrailGraphSquareFile.Tag.NAME to "Ridge Path"),
                nodes = listOf(
                    TrailGraphSquareFile.Node(1L, 47_718_350, 12_831_700),
                    TrailGraphSquareFile.Node(2L, 47_718_900, 12_831_700)
                )
            )
        )
    ).encode()

    private data class Harness(
        val dataSource: HttpRoutingGraphSquareDataSource,
        val clock: TestClock,
        val scope: CoroutineScope
    )

    private fun createDataSource(allowsNetwork: Boolean, ioDispatcher: CoroutineDispatcher): Harness {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val conditions = TileNetworkConditions(isOnline = allowsNetwork)
        val policy = object : TileNetworkPolicy {
            override val conditions: StateFlow<TileNetworkConditions> = MutableStateFlow(conditions)
            override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(allowsNetwork)
            override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
                TileNetworkPolicyEvaluator.evaluate(purpose, conditions, simulatesOffline = !allowsNetwork)
        }
        val clock = TestClock(1_780_000_000_000L)
        val manifest = VectorTileArchiveManifest(
            version = 1,
            revision = 99,
            basemaps = listOf(
                VectorTileArchiveManifest.BasemapArchive("planet-20261118", schema = 4, maximumZoom = 15)
            ),
            routing = listOf(VectorTileArchiveManifest.SquareArchive("routing-20261122-00", generation = 1, zoom = 12))
        )
        val manager = VectorTileArchivesManager(
            manifestDataSource = object : VectorTileManifestDataSource {
                override suspend fun fetchManifest(): VectorTileArchiveManifest = manifest
            },
            tileNetworkPolicy = policy,
            clock = clock,
            serverBaseUrl = baseUrl,
            stateFile = File(tempDir, "archives.json"),
            ioDispatcher = ioDispatcher
        )
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        val dataSource = HttpRoutingGraphSquareDataSource(
            httpClient = OkHttpClient(),
            archivesManager = manager,
            tileNetworkPolicy = policy,
            clock = clock,
            cacheDir = File(tempDir, "squares"),
            ioDispatcher = ioDispatcher,
            applicationScope = scope
        )
        return Harness(dataSource = dataSource, clock = clock, scope = scope)
    }
}
