package app.openhikes.core.network

import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.TileAddress
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorTileNetworkClientTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun refreshesManifestOnHttp410AndRetriesAgainstNewArchive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestClock(initialEpochMillis = 1_000_000L)
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(OpenHikesUserAgent.HEADER_NAME, OpenHikesUserAgent.build(AppBuildInfo("1.0", 1)))
                        .build()
                )
            }
            .build()
        val seenUserAgents = mutableListOf<String?>()
        val expectedTileBytes = "new-archive-mvt-bytes".encodeToByteArray()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenUserAgents.add(request.headers[OpenHikesUserAgent.HEADER_NAME])
                return when (request.target) {
                    "/planet-20261118/4/8/5.mvt" -> MockResponse.Builder().code(410).build()
                    "/manifest.json" -> MockResponse.Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "version": 1,
                              "revision": 12,
                              "basemaps": [
                                { "name": "planet-20261125", "schema": 4, "maximumZoom": 15 }
                              ],
                              "extras": [
                                { "name": "extras-20261125-00", "generation": 2, "minimumZoom": 4 }
                              ]
                            }
                            """.trimIndent()
                        )
                        .build()

                    "/planet-20261125/4/8/5.mvt" -> MockResponse.Builder()
                        .code(200)
                        .body(okio.Buffer().write(expectedTileBytes))
                        .build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val policy = TestTileNetworkPolicy()
        val manifestSource = HttpVectorTileManifestDataSource(okHttp, baseUrl, dispatcher)
        val archivesManager = VectorTileArchivesManager(
            manifestDataSource = manifestSource,
            tileNetworkPolicy = policy,
            clock = clock,
            serverBaseUrl = baseUrl,
            stateFile = File(tempFolder.root, VectorTileArchivesManager.STATE_FILE_NAME),
            ioDispatcher = dispatcher
        )
        val client = VectorTileNetworkClient(
            okHttpClient = okHttp,
            archivesManager = archivesManager,
            tileNetworkPolicy = policy,
            clock = clock,
            ioDispatcher = dispatcher
        )

        val result = client.fetchBasemapTile(TileAddress(4, 8, 5))
        assertThat(result).isEqualTo(VectorTileFetchResult.Tile(expectedTileBytes))
        assertThat(archivesManager.activeSource.value?.basemapArchiveName).isEqualTo("planet-20261125")
        assertThat(seenUserAgents.filterNotNull().first()).contains("OpenHikes/1.0 (Android;")
    }

    @Test
    fun extrasHttp503PausesOnlyExtrasArchiveAndNotBasemap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestClock(initialEpochMillis = 2_000_000L)
        val okHttp = OkHttpClient()
        val basemapBytes = "basemap-tile".encodeToByteArray()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/extras-20261122-00/6/30/20.mvt" -> MockResponse.Builder()
                    .code(503)
                    .addHeader("Retry-After", "120")
                    .build()

                "/planet-20261118/6/30/20.mvt" -> MockResponse.Builder()
                    .code(200)
                    .body(okio.Buffer().write(basemapBytes))
                    .build()

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val policy = TestTileNetworkPolicy()
        val manifestSource = HttpVectorTileManifestDataSource(okHttp, baseUrl, dispatcher)
        val archivesManager = VectorTileArchivesManager(
            manifestDataSource = manifestSource,
            tileNetworkPolicy = policy,
            clock = clock,
            serverBaseUrl = baseUrl,
            stateFile = File(tempFolder.root, VectorTileArchivesManager.STATE_FILE_NAME),
            ioDispatcher = dispatcher
        )
        val client = VectorTileNetworkClient(
            okHttpClient = okHttp,
            archivesManager = archivesManager,
            tileNetworkPolicy = policy,
            clock = clock,
            ioDispatcher = dispatcher
        )

        val extrasResult = client.fetchExtrasTile(TileAddress(6, 30, 20))
        assertThat(extrasResult).isEqualTo(VectorTileFetchResult.Unavailable)

        val source = archivesManager.activeSource.value!!
        assertThat(client.isArchivePaused(source.extras!!.archiveUrl)).isTrue()
        assertThat(client.isArchivePaused(source.basemapUrl)).isFalse()

        val basemapResult = client.fetchBasemapTile(TileAddress(6, 30, 20))
        assertThat(basemapResult).isEqualTo(VectorTileFetchResult.Tile(basemapBytes))
    }

    private class TestTileNetworkPolicy : TileNetworkPolicy {
        private val conditionsFlow = MutableStateFlow(TileNetworkConditions())
        override val conditions: StateFlow<TileNetworkConditions> = conditionsFlow
        override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(true)
        override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
            TileNetworkPolicyEvaluator.evaluate(purpose, conditionsFlow.value)
    }
}
