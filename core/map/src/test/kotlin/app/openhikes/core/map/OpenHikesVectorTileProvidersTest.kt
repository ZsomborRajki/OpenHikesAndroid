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
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maplibre.compose.sources.TileCoordinate

class OpenHikesVectorTileProvidersTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun offlineCountryArchiveServesTilesEvenWhenNetworkIsOffline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val offlineBytes = "local-pmtiles-mvt".encodeToByteArray()
        val offlineSource = object : OfflineTileArchiveSource {
            override val archiveRevision: StateFlow<Long> = MutableStateFlow(1L)
            override fun readBasemapTile(address: TileAddress): ByteArray? =
                if (address == TileAddress(5, 17, 11)) offlineBytes else null

            override fun readExtrasTile(address: TileAddress): ByteArray? = null
            override fun containsTile(address: TileAddress): Boolean = address == TileAddress(5, 17, 11)
        }
        val offlinePolicy = object : TileNetworkPolicy {
            override val conditions: StateFlow<TileNetworkConditions> =
                MutableStateFlow(TileNetworkConditions(isOnline = false))
            override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(false)
            override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
                TileNetworkPolicyEvaluator.evaluate(purpose, conditions.value)
        }
        val archivesManager = VectorTileArchivesManager(
            manifestDataSource = object : VectorTileManifestDataSource {
                override suspend fun fetchManifest(): VectorTileArchiveManifest = error("Should not call network")
            },
            tileNetworkPolicy = offlinePolicy,
            clock = TestClock(),
            serverBaseUrl = "https://tiles.openhikes.app",
            stateFile = File(tempFolder.root, "archives.json"),
            ioDispatcher = dispatcher
        )
        val networkClient = VectorTileNetworkClient(
            okHttpClient = OkHttpClient(),
            archivesManager = archivesManager,
            tileNetworkPolicy = offlinePolicy,
            clock = TestClock(),
            ioDispatcher = dispatcher
        )
        val provider = OpenHikesBasemapTileProvider(offlineSource, networkClient, offlinePolicy)

        assertThat(provider.loadTile(TileCoordinate(5, 17L, 11L))).isEqualTo(offlineBytes)
        assertThat(provider.loadTile(TileCoordinate(5, 0L, 0L))).isEmpty()
    }
}
