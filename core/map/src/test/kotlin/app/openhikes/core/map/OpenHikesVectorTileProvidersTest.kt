package app.openhikes.core.map

import app.openhikes.core.data.OfflineTileArchiveSource
import app.openhikes.core.model.TileAddress
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.network.VectorTileArchivesManager
import app.openhikes.core.network.VectorTileManifestDataSource
import app.openhikes.core.network.VectorTileNetworkClient
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maplibre.compose.sources.TileCoordinate

class OpenHikesVectorTileProvidersTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun offlineCountryArchiveServesExactTileAndThrowsUnavailableForMissingOfflineTiles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val offlineBytes = "local-pmtiles-mvt".encodeToByteArray()
        val offlineSource = stubOfflineSource(TileAddress(5, 17, 11) to offlineBytes)
        val offlinePolicy = stubPolicy(isOnline = false)
        val provider = createBasemapProvider(offlineSource, offlinePolicy, "https://tiles.openhikes.app", dispatcher)

        assertThat(provider.loadTile(TileCoordinate(5, 17L, 11L))).isEqualTo(offlineBytes)
        val error = runCatching { provider.loadTile(TileCoordinate(5, 0L, 0L)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(TileUnavailableException::class.java)
    }

    @Test
    fun onlineConfirmedMissingReturnsEmptyTileWhileTransient503ThrowsUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server = MockWebServer().apply {
            this.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                    "/planet-20261118/5/1/1.mvt" -> MockResponse.Builder().code(204).build()
                    "/planet-20261118/5/2/2.mvt" -> MockResponse.Builder().code(503).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val provider = createBasemapProvider(
                offlineSource = stubOfflineSource(),
                policy = stubPolicy(isOnline = true),
                serverBaseUrl = baseUrl,
                dispatcher = dispatcher
            )

            assertThat(provider.loadTile(TileCoordinate(5, 1L, 1L))).isEmpty()
            val transientError = runCatching { provider.loadTile(TileCoordinate(5, 2L, 2L)) }.exceptionOrNull()
            assertThat(transientError).isInstanceOf(TileUnavailableException::class.java)
        } finally {
            server.close()
        }
    }

    @Test
    fun vectorZoomResolutionAndPoiKindsMatchOfflineExtraDetailContract() {
        assertThat(resolveOpenHikesVectorZooms(false, 15, offlineBasemapMaxZoom = 14, offlineExtrasMaxZoom = 14))
            .isEqualTo(14 to 14)
        assertThat(resolveOpenHikesVectorZooms(false, 15, offlineBasemapMaxZoom = 15, offlineExtrasMaxZoom = 15))
            .isEqualTo(15 to 15)
        assertThat(resolveOpenHikesVectorZooms(true, 15, offlineBasemapMaxZoom = 14, offlineExtrasMaxZoom = 14))
            .isEqualTo(15 to 15)

        assertThat(OpenHikesPoiStyle.PRIMARY_KINDS).containsAtLeast("drinking_water", "toilets", "alpine_hut")
        assertThat(OpenHikesPoiStyle.MICRO_KINDS).contains("bench")
    }

    private fun createBasemapProvider(
        offlineSource: OfflineTileArchiveSource,
        policy: TileNetworkPolicy,
        serverBaseUrl: String,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): OpenHikesBasemapTileProvider {
        val archivesManager = VectorTileArchivesManager(
            manifestDataSource = object : VectorTileManifestDataSource {
                override suspend fun fetchManifest(): VectorTileArchiveManifest = error("Unused in test")
            },
            tileNetworkPolicy = policy,
            clock = TestClock(),
            serverBaseUrl = serverBaseUrl,
            stateFile = File(tempFolder.root, "archives-${System.nanoTime()}.json"),
            ioDispatcher = dispatcher
        )
        val networkClient = VectorTileNetworkClient(
            okHttpClient = OkHttpClient(),
            archivesManager = archivesManager,
            tileNetworkPolicy = policy,
            clock = TestClock(),
            ioDispatcher = dispatcher
        )
        return OpenHikesBasemapTileProvider(offlineSource, networkClient, policy)
    }

    private fun stubOfflineSource(vararg tiles: Pair<TileAddress, ByteArray>): OfflineTileArchiveSource {
        val map = tiles.toMap()
        return object : OfflineTileArchiveSource {
            override val archiveRevision: StateFlow<Long> = MutableStateFlow(1L)
            override val maximumBasemapZoom: StateFlow<Int> = MutableStateFlow(14)
            override val maximumExtrasZoom: StateFlow<Int> = MutableStateFlow(14)
            override fun readBasemapTile(address: TileAddress): ByteArray? = map[address]
            override fun readExtrasTile(address: TileAddress): ByteArray? = null
            override fun containsTile(address: TileAddress): Boolean = address in map
        }
    }

    private fun stubPolicy(isOnline: Boolean): TileNetworkPolicy = object : TileNetworkPolicy {
        override val conditions: StateFlow<TileNetworkConditions> =
            MutableStateFlow(TileNetworkConditions(isOnline = isOnline))
        override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(isOnline)
        override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
            TileNetworkPolicyEvaluator.evaluate(purpose, conditions.value)
    }
}
