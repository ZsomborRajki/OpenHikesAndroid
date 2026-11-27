package app.openhikes.core.network

import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
class HttpElevationSquareDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("elevation-squares-test").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun fetchesDecodesCachesSeaAndLandSquaresAndServesFromDiskWithoutSecondRequest() = runTest {
        val landSquare = ElevationSquare(row = 338, column = 1_542)
        val seaSquare = ElevationSquare(row = 338, column = 1_543)
        val landBytes = buildValidSquareBytes(landSquare, constantHeight = 612)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/elevation-20261126-20/12/1542/338.bin" ->
                    MockResponse.Builder().code(200).body(Buffer().write(landBytes)).build()
                "/elevation-20261126-20/12/1543/338.bin" ->
                    MockResponse.Builder().code(204).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val first = harness.dataSource.readSquares(listOf(landSquare, seaSquare))
        val second = harness.dataSource.readSquares(listOf(landSquare, seaSquare))

        assertThat(first).isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)
        val loaded = (first as ElevationSquaresBatchAnswer.Loaded).squares
        assertThat(loaded[landSquare]?.heightAt(0)).isEqualTo(612.0)
        assertThat(loaded.containsKey(seaSquare)).isTrue()
        assertThat(loaded[seaSquare]).isNull()
        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(2)
        harness.scope.cancel()
    }

    @Test
    fun retiresArchiveOnHttp410RefreshesManifestOnceAndRetriesBatch() = runTest {
        val square = ElevationSquare(row = 338, column = 1_542)
        val updatedBytes = buildValidSquareBytes(square, constantHeight = 845)
        val manifestFetches = AtomicInteger(0)
        val v1Manifest = manifestWithElevation("elevation-20261120-00", revision = 20)
        val v2Manifest = manifestWithElevation("elevation-20261126-20", revision = 21)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/elevation-20261120-00/12/1542/338.bin" -> MockResponse.Builder().code(410).build()
                "/elevation-20261126-20/12/1542/338.bin" ->
                    MockResponse.Builder().code(200).body(Buffer().write(updatedBytes)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }

        val harness = createDataSource(
            allowsNetwork = true,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            manifestProvider = {
                if (manifestFetches.incrementAndGet() == 1) v1Manifest else v2Manifest
            }
        )
        val answer = harness.dataSource.readSquares(listOf(square))

        assertThat(answer).isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)
        assertThat((answer as ElevationSquaresBatchAnswer.Loaded).squares[square]?.heightAt(0)).isEqualTo(845.0)
        assertThat(manifestFetches.get()).isEqualTo(2)
        harness.scope.cancel()
    }

    @Test
    fun honorsHttp429CooldownOfflinePolicyAndRejectsWrongDimensions() = runTest {
        val cachedSquare = ElevationSquare(row = 338, column = 1_542)
        val limitedSquare = ElevationSquare(row = 338, column = 1_543)
        val thirdSquare = ElevationSquare(row = 338, column = 1_544)
        val wrongDimsSquare = ElevationSquare(row = 338, column = 1_545)
        val validBytes = buildValidSquareBytes(cachedSquare, constantHeight = 500)
        val badDimBytes = ElevationSquareFile(rows = 4, columns = 4, heights = ShortArray(16) { 100 }).encode()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/elevation-20261126-20/12/1542/338.bin" ->
                    MockResponse.Builder().code(200).body(Buffer().write(validBytes)).build()
                "/elevation-20261126-20/12/1543/338.bin" ->
                    MockResponse.Builder().code(429).addHeader("Retry-After", "90").build()
                "/elevation-20261126-20/12/1544/338.bin" ->
                    MockResponse.Builder().code(204).build()
                "/elevation-20261126-20/12/1545/338.bin" ->
                    MockResponse.Builder().code(200).body(Buffer().write(badDimBytes)).build()
                else -> MockResponse.Builder().code(500).build()
            }
        }

        val harness = createDataSource(allowsNetwork = true, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        assertThat(harness.dataSource.readSquares(listOf(cachedSquare)))
            .isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)
        assertThat(harness.dataSource.readSquares(listOf(wrongDimsSquare)))
            .isEqualTo(ElevationSquaresBatchAnswer.Unreachable)
        assertThat(harness.dataSource.readSquares(listOf(limitedSquare)))
            .isEqualTo(ElevationSquaresBatchAnswer.Unreachable)
        val countAfter429 = server.requestCount

        // During cooldown, uncached requests fail without hitting the network while cached squares still work.
        assertThat(harness.dataSource.readSquares(listOf(thirdSquare)))
            .isEqualTo(ElevationSquaresBatchAnswer.Unreachable)
        assertThat(server.requestCount).isEqualTo(countAfter429)
        assertThat(harness.dataSource.readSquares(listOf(cachedSquare)))
            .isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)

        harness.clock.advanceBy(Duration.ofSeconds(91L))
        assertThat(harness.dataSource.readSquares(listOf(thirdSquare)))
            .isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)
        assertThat(server.requestCount).isEqualTo(countAfter429 + 1)

        // Offline policy refuses uncached requests immediately without network access.
        val offlineHarness = createDataSource(
            allowsNetwork = false,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            cacheSubdir = "offline_squares"
        )
        assertThat(offlineHarness.dataSource.readSquares(listOf(thirdSquare)))
            .isEqualTo(ElevationSquaresBatchAnswer.Unreachable)
        assertThat(server.requestCount).isEqualTo(countAfter429 + 1)

        harness.scope.cancel()
        offlineHarness.scope.cancel()
    }

    @Test
    fun cancellingFirstReaderKeepsCoalescedFetchAliveForSecondReader() = runBlocking {
        val square = ElevationSquare(row = 338, column = 1_542)
        val payload = buildValidSquareBytes(square, constantHeight = 777)
        val requestStarted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).body(Buffer().write(payload)).build()
            }
        }

        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { pool ->
            val harness = createDataSource(allowsNetwork = true, ioDispatcher = pool)
            val firstReader = launch(pool) { harness.dataSource.readSquares(listOf(square)) }
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue()

            val secondReader = async(pool) { harness.dataSource.readSquares(listOf(square)) }
            firstReader.cancelAndJoin()
            allowResponse.countDown()

            val result = secondReader.await()
            assertThat(result).isInstanceOf(ElevationSquaresBatchAnswer.Loaded::class.java)
            assertThat((result as ElevationSquaresBatchAnswer.Loaded).squares[square]?.heightAt(0)).isEqualTo(777.0)
            assertThat(server.requestCount).isEqualTo(1)
            harness.scope.cancel()
        }
    }

    private fun buildValidSquareBytes(square: ElevationSquare, constantHeight: Short): ByteArray {
        val rows = ElevationSquareGrid.ROWS_PER_SQUARE
        val cols = ElevationSquareGrid.columnsPerSquare(square.row)
        return ElevationSquareFile(rows = rows, columns = cols, heights = ShortArray(rows * cols) { constantHeight })
            .encode()
    }

    private fun manifestWithElevation(name: String, revision: Int): VectorTileArchiveManifest =
        VectorTileArchiveManifest(
            version = 1,
            revision = revision,
            basemaps = listOf(
                VectorTileArchiveManifest.BasemapArchive("planet-20261118", schema = 4, maximumZoom = 15)
            ),
            elevation = listOf(
                VectorTileArchiveManifest.SquareArchive(name = name, generation = 1, zoom = 12)
            )
        )

    private data class Harness(
        val dataSource: HttpElevationSquareDataSource,
        val clock: TestClock,
        val scope: CoroutineScope
    )

    private fun createDataSource(
        allowsNetwork: Boolean,
        ioDispatcher: CoroutineDispatcher,
        cacheSubdir: String = "elevation_squares",
        manifestProvider: suspend () -> VectorTileArchiveManifest = {
            manifestWithElevation("elevation-20261126-20", revision = 20)
        }
    ): Harness {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val conditions = TileNetworkConditions(isOnline = allowsNetwork)
        val policy = object : TileNetworkPolicy {
            override val conditions: StateFlow<TileNetworkConditions> = MutableStateFlow(conditions)
            override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(allowsNetwork)
            override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
                TileNetworkPolicyEvaluator.evaluate(purpose, conditions, simulatesOffline = !allowsNetwork)
        }
        val clock = TestClock(1_780_000_000_000L)
        val manager = VectorTileArchivesManager(
            manifestDataSource = object : VectorTileManifestDataSource {
                override suspend fun fetchManifest(): VectorTileArchiveManifest = manifestProvider()
            },
            tileNetworkPolicy = policy,
            clock = clock,
            serverBaseUrl = baseUrl,
            stateFile = File(tempDir, "archives_$cacheSubdir.json"),
            ioDispatcher = ioDispatcher
        )
        if (allowsNetwork) {
            runBlocking { manager.refresh() }
            clock.advanceBy(Duration.ofSeconds(65L))
        }
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        val dataSource = HttpElevationSquareDataSource(
            httpClient = OkHttpClient(),
            archivesManager = manager,
            tileNetworkPolicy = policy,
            clock = clock,
            cacheDir = File(tempDir, cacheSubdir),
            ioDispatcher = ioDispatcher,
            applicationScope = scope
        )
        return Harness(dataSource = dataSource, clock = clock, scope = scope)
    }
}
