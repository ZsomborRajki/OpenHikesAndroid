package app.openhikes.core.network

import app.openhikes.core.common.places.PlaceSquareFile
import app.openhikes.core.model.PlaceSearchBox
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class HttpPlaceSquareDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("place-squares-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun fetchesDecodesCachesAndServesFromDiskWithoutSecondRequest() = runTest {
        val box = PlaceSearchBox(south = 47.70, west = 12.80, north = 47.75, east = 12.90)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sampleSquareBytes())).build())

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = harness.dataSource.placesInBoxes(listOf(box), TrailPlaceSymbol.entries.toSet())
        val second = harness.dataSource.placesInBoxes(listOf(box), TrailPlaceSymbol.entries.toSet())
        val cached = harness.dataSource.cachedPlacesInBoxes(listOf(box), TrailPlaceSymbol.entries.toSet())

        assertThat(first).isInstanceOf(PlaceSquaresAnswer.Places::class.java)
        val places = (first as PlaceSquaresAnswer.Places).places
        assertThat(places.map { it.name }).containsExactly("Thumsee Kopf")
        assertThat(second).isEqualTo(first)
        assertThat(cached.map { it.name }).containsExactly("Thumsee Kopf")
        assertThat(server.requestCount).isEqualTo(1)
        harness.scope.cancel()
    }

    @Test
    fun returnsOfflineOutageWhenUncachedAndOffline() = runTest {
        val box = PlaceSearchBox(south = 47.70, west = 12.80, north = 47.75, east = 12.90)
        val harness = createDataSource(allowsNetwork = false, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val result = harness.dataSource.placesInBoxes(listOf(box), TrailPlaceSymbol.entries.toSet())

        assertThat(result).isEqualTo(PlaceSquaresAnswer.Unreachable(PlaceSearchOutage.OFFLINE))
        assertThat(server.requestCount).isEqualTo(0)
        harness.scope.cancel()
    }

    @Test
    fun handlesHttp204EmptyAndHttp429RateLimitCooldown() = runTest {
        val cachedBox = PlaceSearchBox(south = 47.70, west = 12.80, north = 47.75, east = 12.90)
        val emptyBox = PlaceSearchBox(south = 46.10, west = 10.10, north = 46.20, east = 10.20)
        val limitedBoxA = PlaceSearchBox(south = 45.10, west = 9.10, north = 45.20, east = 9.20)
        val limitedBoxB = PlaceSearchBox(south = 44.10, west = 8.10, north = 44.20, east = 8.20)
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(sampleSquareBytes())).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").build())
        server.enqueue(MockResponse.Builder().code(204).build())

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        assertThat(harness.dataSource.placesInBoxes(listOf(cachedBox), TrailPlaceSymbol.entries.toSet()))
            .isInstanceOf(PlaceSquaresAnswer.Places::class.java)
        assertThat(harness.dataSource.placesInBoxes(listOf(emptyBox), TrailPlaceSymbol.entries.toSet()))
            .isEqualTo(PlaceSquaresAnswer.Places(emptyList()))
        assertThat(harness.dataSource.placesInBoxes(listOf(limitedBoxA), TrailPlaceSymbol.entries.toSet()))
            .isEqualTo(PlaceSquaresAnswer.Unreachable(PlaceSearchOutage.RATE_LIMITED))
        assertThat(server.requestCount).isEqualTo(3)

        // During cooldown, uncached boxes fail immediately while cached squares remain available.
        assertThat(harness.dataSource.placesInBoxes(listOf(limitedBoxB), TrailPlaceSymbol.entries.toSet()))
            .isEqualTo(PlaceSquaresAnswer.Unreachable(PlaceSearchOutage.RATE_LIMITED))
        assertThat(harness.dataSource.placesInBoxes(listOf(cachedBox), TrailPlaceSymbol.entries.toSet()))
            .isInstanceOf(PlaceSquaresAnswer.Places::class.java)
        assertThat(server.requestCount).isEqualTo(3)

        harness.clock.advanceBy(Duration.ofSeconds(61L))
        assertThat(harness.dataSource.placesInBoxes(listOf(limitedBoxB), TrailPlaceSymbol.entries.toSet()))
            .isEqualTo(PlaceSquaresAnswer.Places(emptyList()))
        assertThat(server.requestCount).isEqualTo(4)
        harness.scope.cancel()
    }

    private fun sampleSquareBytes(): ByteArray = PlaceSquareFile(
        places = listOf(
            PlaceSquareFile.Place(
                element = PlaceSquareFile.Element.NODE,
                id = 9_100_004L,
                point = PlaceSquareFile.Point(latitude = 47_721_500, longitude = 12_841_363),
                tags = mapOf(
                    "natural" to "peak",
                    "name" to "Thumsee Kopf",
                    "ele" to "712",
                    "wikidata" to "Q4115189"
                )
            )
        )
    ).encode()

    private data class Harness(
        val dataSource: HttpPlaceSquareDataSource,
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
            places = listOf(VectorTileArchiveManifest.SquareArchive("places-20261122-00", generation = 1, zoom = 9))
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
        val dataSource = HttpPlaceSquareDataSource(
            httpClient = OkHttpClient(),
            archivesManager = manager,
            tileNetworkPolicy = policy,
            clock = clock,
            cacheDir = File(tempDir, "place_squares"),
            ioDispatcher = ioDispatcher,
            applicationScope = scope
        )
        return Harness(dataSource = dataSource, clock = clock, scope = scope)
    }
}
