package app.openhikes.core.data

import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.pmtiles.PMTilesWriter
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineHoldingsState
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.model.TileAddress
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.UserSettings
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultOfflineCountryRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun downloadsVerifiesSha256InstallsServesOfflineTilesTogglesDetailAndDeletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemapTile = "austria-z14-mvt".encodeToByteArray()
        val detailTile = "austria-z15-mvt".encodeToByteArray()
        val basemapArchive = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 4, x = 8, y = 5, payload = basemapTile))
        )
        val detailArchive = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 15, x = 17_540, y = 11_420, payload = detailTile))
        )

        val austria = buildCountry(
            id = "AT",
            build = "20261117",
            basemapBytes = basemapArchive,
            detailBytes = detailArchive
        )
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = mapOf(
                "AT-20261117.pmtiles" to basemapArchive,
                "AT-detail-20261117.pmtiles" to detailArchive
            )
        )
        val harness = createHarness(server, dispatcher)
        val repo = harness.repository
        val store = harness.store
        val scheduler = harness.scheduler
        val archives = harness.archives

        repo.refreshCatalog()
        assertThat(repo.state.value.offered).containsExactly(austria)

        repo.downloadCountry("AT")
        assertThat(scheduler.enqueuedIds).containsExactly("AT")

        val execResult = repo.runScheduledDownload("AT")
        assertThat(execResult).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(repo.state.value.isDrawn("AT")).isTrue()
        assertThat(repo.state.value.installed["AT"]?.hasDetail).isTrue()
        assertThat(archives.readBasemapTile(TileAddress(4, 8, 5))).isEqualTo(basemapTile)
        assertThat(archives.readBasemapTile(TileAddress(15, 17_540, 11_420))).isEqualTo(detailTile)

        // Turning Extra Detail OFF commits index.json with hasDetail=false and deletes z15 file.
        repo.setIncludesDetail(false)
        assertThat(repo.state.value.installed["AT"]?.hasDetail).isFalse()
        assertThat(store.fileFor(austria.files.first { it.role == OfflineFileRole.DETAIL }).exists()).isFalse()
        assertThat(archives.readBasemapTile(TileAddress(4, 8, 5))).isEqualTo(basemapTile)

        // Turning Extra Detail back ON queues detail-only download for AT.
        repo.setIncludesDetail(true)
        assertThat(repo.state.value.downloads["AT"]?.isDetailOnly).isTrue()

        // Deleting AT removes record, cancels pending detail download, and deletes disk files.
        repo.deleteCountry("AT")
        assertThat(repo.state.value.installed).isEmpty()
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat(store.fileFor(austria.files.first { it.role == OfflineFileRole.BASEMAP }).exists()).isFalse()
        archives.close()
    }

    @Test
    fun rejectsCorruptSha256RefusesUnderBatterySaverAndPreservesUnreadableIndexWithoutSweeping() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val validArchive = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 4, x = 8, y = 5, payload = "ok".encodeToByteArray()))
        )
        val austria = buildCountry("AT", "20261117", validArchive, validArchive)
        val corruptedServer = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = mapOf(
                "AT-20261117.pmtiles" to "tampered-bytes".encodeToByteArray(),
                "AT-detail-20261117.pmtiles" to validArchive
            )
        )
        val harness = createHarness(corruptedServer, dispatcher)
        val repo = harness.repository
        val store = harness.store
        val networkPolicy = harness.networkPolicy
        val archives = harness.archives

        repo.refreshCatalog()

        // Battery Saver refuses immediately with CONSERVING.
        networkPolicy.update(TileNetworkConditions(isOnline = true, isPowerSaveMode = true))
        repo.downloadCountry("AT")
        assertThat(repo.state.value.downloads["AT"]?.failure).isEqualTo(OfflineDownloadFailure.CONSERVING)
        repo.cancelDownload("AT")

        // Cellular queues in WAITING_FOR_WIFI.
        networkPolicy.update(TileNetworkConditions(isOnline = true, isMetered = true))
        repo.downloadCountry("AT")
        assertThat(repo.state.value.downloads["AT"]?.phase).isEqualTo(OfflineDownloadPhase.WAITING_FOR_WIFI)

        // Wi-Fi download with tampered payload fails with DAMAGED and deletes staged file.
        networkPolicy.update(TileNetworkConditions(isOnline = true, isMetered = false))
        val exec = repo.runScheduledDownload("AT")
        assertThat(exec).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        assertThat(repo.state.value.downloads["AT"]?.failure).isEqualTo(OfflineDownloadFailure.DAMAGED)
        assertThat(store.stagedFileFor(austria.files.first()).exists()).isFalse()

        // Unreadable index.json sets holdings=UNREADABLE, isAvailable=false, and sweeps NOTHING.
        val preservedArchive = File(store.directory, "DE-20261117.pmtiles").apply { writeText("keep-me") }
        store.indexFile.writeText("{corrupt-json")
        val freshRepo = harness.newRepository()
        freshRepo.start()
        assertThat(freshRepo.state.value.holdings).isEqualTo(OfflineHoldingsState.UNREADABLE)
        assertThat(freshRepo.state.value.isAvailable).isFalse()
        assertThat(preservedArchive.isFile).isTrue()
        archives.close()
    }

    private class RepoHarness(
        val store: OfflineCountryStore,
        val server: OfflineCountryServer,
        val scheduler: RecordingScheduler,
        val networkPolicy: MutableTileNetworkPolicy,
        val settingsRepo: InMemorySettingsRepo,
        val archives: OfflineCountryArchives,
        val clock: TestClock,
        val dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) {
        val repository: DefaultOfflineCountryRepository = newRepository()

        fun newRepository(): DefaultOfflineCountryRepository {
            val executor = OfflineCountryDownloadExecutor(store, server, networkPolicy, archives, clock, dispatcher)
            val reconciler = OfflineCountryReconciler(store, archives, dispatcher)
            return DefaultOfflineCountryRepository(
                store = store,
                server = server,
                scheduler = scheduler,
                tileNetworkPolicy = networkPolicy,
                userSettingsRepository = settingsRepo,
                executor = executor,
                reconciler = reconciler,
                ioDispatcher = dispatcher
            )
        }
    }

    private fun createHarness(
        server: OfflineCountryServer,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): RepoHarness = RepoHarness(
        store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries")),
        server = server,
        scheduler = RecordingScheduler(),
        networkPolicy = MutableTileNetworkPolicy(),
        settingsRepo = InMemorySettingsRepo(),
        archives = OfflineCountryArchives(),
        clock = TestClock(initialEpochMillis = 50_000L),
        dispatcher = dispatcher
    )

    private fun buildCountry(
        id: String,
        build: String,
        basemapBytes: ByteArray,
        detailBytes: ByteArray
    ): OfflineCountry = OfflineCountry(
        id = id,
        build = build,
        schema = 4,
        bounds = OfflineBounds(west = 0.0, south = 0.0, east = 45.0, north = 60.0),
        outline = listOf(
            listOf(
                listOf(0.0, 0.0),
                listOf(45.0, 0.0),
                listOf(45.0, 60.0),
                listOf(0.0, 60.0),
                listOf(0.0, 0.0)
            )
        ),
        files = listOf(
            OfflineCountryFile(
                role = OfflineFileRole.BASEMAP,
                path = "countries/$id-$build.pmtiles",
                byteCount = basemapBytes.size.toLong(),
                sha256 = OfflineCountryDigest.sha256Hex(basemapBytes),
                minimumZoom = 0,
                maximumZoom = 14
            ),
            OfflineCountryFile(
                role = OfflineFileRole.DETAIL,
                path = "countries/$id-detail-$build.pmtiles",
                byteCount = detailBytes.size.toLong(),
                sha256 = OfflineCountryDigest.sha256Hex(detailBytes),
                minimumZoom = 15,
                maximumZoom = 15
            )
        )
    )

    private class InMemoryCountryServer(
        private val manifest: OfflineCountryManifest,
        private val payloads: Map<String, ByteArray>
    ) : OfflineCountryServer {
        override suspend fun fetchManifest(): OfflineCountryManifest = manifest
        override fun fileUrl(file: OfflineCountryFile): String =
            "https://tiles.openhikes.app/countries/${file.fileName}"
        override suspend fun downloadFile(
            file: OfflineCountryFile,
            partialFile: File,
            destinationFile: File,
            onProgress: (Long) -> Unit
        ) {
            val bytes = payloads.getValue(file.fileName)
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(bytes)
            onProgress(bytes.size.toLong())
        }
    }

    private class RecordingScheduler : OfflineCountryDownloadScheduler {
        val enqueuedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()
        override fun enqueue(countryId: String) {
            enqueuedIds.add(countryId)
        }

        override fun cancel(countryId: String) {
            cancelledIds.add(countryId)
        }
    }

    private class MutableTileNetworkPolicy : TileNetworkPolicy {
        private val conditionsFlow = MutableStateFlow(
            TileNetworkConditions(
                isOnline = true,
                isMetered = false,
                isDataSaverEnabled = false,
                isPowerSaveMode = false,
                thermalSeverity = ThermalSeverity.NOMINAL
            )
        )
        override val conditions: StateFlow<TileNetworkConditions> = conditionsFlow.asStateFlow()
        override val allowsInteractiveRequests: StateFlow<Boolean> = MutableStateFlow(true)
        fun update(next: TileNetworkConditions) {
            conditionsFlow.value = next
        }

        override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision =
            TileNetworkPolicyEvaluator.evaluate(purpose, conditionsFlow.value)
    }

    private class InMemorySettingsRepo : UserSettingsRepository {
        private val state = MutableStateFlow(UserSettings())
        override val userSettings: Flow<UserSettings> = state.asStateFlow()
        override suspend fun setDistanceUnit(unit: DistanceUnit) = state.update { it.copy(distanceUnit = unit) }

        override suspend fun setShowsHikingRoutes(enabled: Boolean) =
            state.update { it.copy(showsHikingRoutes = enabled) }

        override suspend fun setAutoPauseEnabled(enabled: Boolean) =
            state.update { it.copy(autoPauseEnabled = enabled) }

        override suspend fun setHikeRemindersEnabled(enabled: Boolean) =
            state.update { it.copy(hikeRemindersEnabled = enabled) }

        override suspend fun setLiveUpdatesEnabled(enabled: Boolean) =
            state.update { it.copy(liveUpdatesEnabled = enabled) }

        override suspend fun setKeepScreenAwake(enabled: Boolean) = state.update { it.copy(keepScreenAwake = enabled) }

        override suspend fun setAutoSaveTilesEnabled(enabled: Boolean) =
            state.update { it.copy(autoSaveTilesEnabled = enabled) }

        override suspend fun setSavePhotosToLibrary(enabled: Boolean) =
            state.update { it.copy(savePhotosToLibrary = enabled) }

        override suspend fun setMapStyleId(styleId: String) = state.update { it.copy(mapStyleId = styleId) }

        override suspend fun setOfflineCountriesExtraDetail(enabled: Boolean) =
            state.update { it.copy(offlineCountriesExtraDetail = enabled) }
    }
}
