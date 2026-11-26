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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
        val squareFile = TrailGraphSquareFile(
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
        )
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(squareFile.encode())).build())

        val dataSource = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = dataSource.readRegion(region)
        val second = dataSource.readRegion(region)

        assertThat(first).isInstanceOf(RoutingSquareReadResult.Graph::class.java)
        val graph = (first as RoutingSquareReadResult.Graph).graph
        assertThat(graph.edges).hasSize(1)
        assertThat(graph.edges.first().name).isEqualTo("Ridge Path")
        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun returnsOfflineOutageWhenInteractiveNetworkBlockedAndUncached() = runTest {
        val region = TrailGraphSquareGrid.regionContaining(GeoCoordinate(47.7185, 12.8317))
        val dataSource = createDataSource(allowsNetwork = false, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = dataSource.readRegion(region)

        assertThat(result).isEqualTo(RoutingSquareReadResult.Failed(TrailLegOutage.OFFLINE))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun handlesHttp204EmptyAndHttp429RateLimitCleanly() = runTest {
        val emptyRegion = TrailGraphRegion(zoom = 12, x = 100, y = 100)
        val limitedRegion = TrailGraphRegion(zoom = 12, x = 101, y = 100)
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(429).build())

        val dataSource = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        assertThat(dataSource.readRegion(emptyRegion)).isEqualTo(RoutingSquareReadResult.Graph(TrailGraph.Empty))
        assertThat(dataSource.readRegion(limitedRegion))
            .isEqualTo(RoutingSquareReadResult.Failed(TrailLegOutage.RATE_LIMITED))
    }

    private fun createDataSource(
        allowsNetwork: Boolean,
        ioDispatcher: CoroutineDispatcher
    ): HttpRoutingGraphSquareDataSource {
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
        return HttpRoutingGraphSquareDataSource(
            httpClient = OkHttpClient(),
            archivesManager = manager,
            tileNetworkPolicy = policy,
            clock = clock,
            cacheDir = File(tempDir, "squares"),
            ioDispatcher = ioDispatcher
        )
    }
}
