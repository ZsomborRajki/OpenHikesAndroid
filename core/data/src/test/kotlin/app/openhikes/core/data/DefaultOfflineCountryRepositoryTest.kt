package app.openhikes.core.data

import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.offline.OfflineStoreList
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
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.model.TileAddress
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.UserSettings
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.OfflineFileTransferStatus
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultOfflineCountryRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun downloadsVerifiesSha256InstallsServesExactZoomTilesTogglesDetailAndDeletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemapTile = "austria-z14-mvt".encodeToByteArray()
        val detailTile = "austria-z15-mvt".encodeToByteArray()
        val basemapArchive = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 14, x = 8_770, y = 5_710, payload = basemapTile))
        )
        val detailArchive = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 15, x = 17_540, y = 11_420, payload = detailTile))
        )
        val austria = buildCountry("AT", "20261117", basemapArchive, detailArchive)
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = mapOf("AT-20261117.pmtiles" to basemapArchive, "AT-detail-20261117.pmtiles" to detailArchive)
        )
        val harness = createHarness(server, dispatcher)
        val repo = harness.repository

        repo.refreshCatalog()
        repo.downloadCountry("AT")
        assertThat(harness.scheduler.enqueuedIds).containsExactly("AT")
        assertThat(repo.runScheduledDownload("AT")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(harness.archives.maximumBasemapZoom.value).isEqualTo(15)
        assertThat(harness.archives.readBasemapTile(TileAddress(14, 8_770, 5_710))).isEqualTo(basemapTile)
        assertThat(harness.archives.readBasemapTile(TileAddress(15, 17_540, 11_420))).isEqualTo(detailTile)

        // Turning Extra Detail OFF strips z15: exact z14 still serves, z15 children return null (no raw z14 shift).
        repo.setIncludesDetail(false)
        assertThat(repo.state.value.installed["AT"]?.hasDetail).isFalse()
        assertThat(harness.archives.maximumBasemapZoom.value).isEqualTo(14)
        assertThat(harness.archives.readBasemapTile(TileAddress(14, 8_770, 5_710))).isEqualTo(basemapTile)
        assertThat(harness.archives.readBasemapTile(TileAddress(15, 17_540, 11_420))).isNull()
        assertThat(harness.archives.readBasemapTile(TileAddress(15, 17_541, 11_420))).isNull()

        // Turning Extra Detail back ON queues detail-only download; deleting removes all state and disk files.
        repo.setIncludesDetail(true)
        assertThat(repo.state.value.downloads["AT"]?.isDetailOnly).isTrue()
        repo.deleteCountry("AT")
        assertThat(repo.state.value.installed).isEmpty()
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat(harness.store.fileFor(austria.files.first { it.role == OfflineFileRole.BASEMAP }).exists()).isFalse()
        harness.archives.close()
    }

    @Test
    fun rejectsCorruptSha256RefusesUnderBatterySaverAndPreservesUnreadableIndexWithoutSweeping() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val validArchive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "ok")
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
        repo.refreshCatalog()

        harness.networkPolicy.update(TileNetworkConditions(isOnline = true, isPowerSaveMode = true))
        repo.downloadCountry("AT")
        assertThat(repo.state.value.downloads["AT"]?.failure).isEqualTo(OfflineDownloadFailure.CONSERVING)
        repo.cancelDownload("AT")

        harness.networkPolicy.update(TileNetworkConditions(isOnline = true, isMetered = true))
        repo.downloadCountry("AT")
        assertThat(repo.state.value.downloads["AT"]?.phase).isEqualTo(OfflineDownloadPhase.WAITING_FOR_WIFI)

        harness.networkPolicy.update(TileNetworkConditions(isOnline = true, isMetered = false))
        assertThat(repo.runScheduledDownload("AT")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        assertThat(repo.state.value.downloads["AT"]?.failure).isEqualTo(OfflineDownloadFailure.DAMAGED)
        assertThat(harness.store.stagedFileFor(austria.files.first()).exists()).isFalse()

        val preservedArchive = File(harness.store.directory, "DE-20261117.pmtiles").apply { writeText("keep-me") }
        harness.store.indexFile.writeText("{corrupt-json")
        val freshRepo = harness.newRepository()
        freshRepo.start()
        assertThat(freshRepo.state.value.holdings).isEqualTo(OfflineHoldingsState.UNREADABLE)
        assertThat(freshRepo.state.value.isAvailable).isFalse()
        assertThat(preservedArchive.isFile).isTrue()
        harness.archives.close()
    }

    @Test
    fun concurrentInstallsPersistBothCountriesAtomicallyAndSurviveRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val atArchive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "at")
        val chArchive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "ch")
        val austria = buildCountry("AT", "20261117", atArchive, atArchive)
        val switzerland = buildCountry("CH", "20261117", chArchive, chArchive)
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria, switzerland)),
            payloads = mapOf(
                "AT-20261117.pmtiles" to atArchive,
                "AT-detail-20261117.pmtiles" to atArchive,
                "CH-20261117.pmtiles" to chArchive,
                "CH-detail-20261117.pmtiles" to chArchive
            )
        )
        val harness = createHarness(server, dispatcher)
        val repo = harness.repository
        repo.refreshCatalog()
        repo.downloadCountry("AT")
        repo.downloadCountry("CH")

        val results = listOf(
            async { repo.runScheduledDownload("AT") },
            async { repo.runScheduledDownload("CH") }
        ).awaitAll()
        assertThat(results).containsExactly(
            OfflineDownloadExecutionResult.COMPLETED,
            OfflineDownloadExecutionResult.COMPLETED
        )

        val onDisk = harness.store.readIndex() as OfflineStoreList.Holding
        assertThat(onDisk.items.map { it.country.id }).containsExactly("AT", "CH")

        val restarted = harness.newRepository()
        restarted.start()
        assertThat(restarted.state.value.installed.keys).containsExactly("AT", "CH")
        assertThat(harness.store.fileFor(austria.files.first()).isFile).isTrue()
        assertThat(harness.store.fileFor(switzerland.files.first()).isFile).isTrue()
        harness.archives.close()
    }

    @Test
    fun coldStartedWorkerRecoversPendingJsonBeforeExecutingDownload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val archive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "cold-start")
        val austria = buildCountry("AT", "20261117", archive, archive)
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = mapOf("AT-20261117.pmtiles" to archive, "AT-detail-20261117.pmtiles" to archive)
        )
        val harness = createHarness(server, dispatcher)
        harness.repository.refreshCatalog()
        harness.repository.downloadCountry("AT")

        // Brand-new repository without calling start() mimics WorkManager starting a cold process.
        val coldRepo = harness.newRepository()
        val result = coldRepo.runScheduledDownload("AT")
        assertThat(result).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(coldRepo.state.value.installed.keys).containsExactly("AT")
        harness.archives.close()
    }

    @Test
    fun malformedCatalogErrorSetsFailedAndKeepsCachedCatalog() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val archive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "cached")
        val austria = buildCountry("AT", "20261117", archive, archive)
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = emptyMap()
        )
        val harness = createHarness(server, dispatcher)
        harness.repository.refreshCatalog()
        assertThat(harness.repository.state.value.offered).containsExactly(austria)

        server.nextManifestError = SerializationException("Truncated JSON")
        harness.repository.refreshCatalog()
        assertThat(harness.repository.state.value.listPhase).isEqualTo(OfflineListPhase.FAILED)
        assertThat(harness.repository.state.value.offered).containsExactly(austria)
        harness.archives.close()
    }

    @Test
    fun boundedSlicesReturnContinueSliceAndPowerPolicyChangeInterruptsMidFilePreservingPartial() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val largeArchive = buildSampleArchive(z = 4, x = 8, y = 5, payload = "a".repeat(2_048))
        val austria = buildCountry("AT", "20261117", largeArchive, largeArchive)
        val server = InMemoryCountryServer(
            manifest = OfflineCountryManifest(version = 1, countries = listOf(austria)),
            payloads = mapOf("AT-20261117.pmtiles" to largeArchive, "AT-detail-20261117.pmtiles" to largeArchive)
        )
        val harness = createHarness(server, dispatcher)
        harness.executor.maxAttemptBytes = 64L
        harness.repository.refreshCatalog()
        harness.repository.downloadCountry("AT")

        // First slice stops cleanly at 64 bytes and asks worker to continue without backoff.
        assertThat(
            harness.repository.runScheduledDownload("AT")
        ).isEqualTo(OfflineDownloadExecutionResult.CONTINUE_SLICE)
        val partialFile = harness.store.partialFileFor(austria.files.first())
        assertThat(partialFile.length()).isEqualTo(64L)
        assertThat(harness.repository.state.value.downloads["AT"]?.phase).isEqualTo(OfflineDownloadPhase.DOWNLOADING)

        // Flipping Battery Saver mid-stream interrupts immediately and preserves partial progress.
        harness.executor.maxAttemptBytes = OfflineCountryDownloadExecutor.DEFAULT_MAX_ATTEMPT_BYTES
        server.onMidChunk = {
            harness.networkPolicy.update(TileNetworkConditions(isOnline = true, isPowerSaveMode = true))
        }
        assertThat(harness.repository.runScheduledDownload("AT")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        assertThat(harness.repository.state.value.downloads["AT"]?.failure).isEqualTo(OfflineDownloadFailure.CONSERVING)
        assertThat(partialFile.isFile).isTrue()
        assertThat(partialFile.length()).isAtLeast(64L)

        // Turning Battery Saver back off and retrying resumes from partial and installs cleanly.
        server.onMidChunk = null
        harness.networkPolicy.update(TileNetworkConditions(isOnline = true, isPowerSaveMode = false))
        harness.repository.retryDownload("AT")
        assertThat(harness.repository.runScheduledDownload("AT")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(harness.repository.state.value.installed.keys).containsExactly("AT")
        harness.archives.close()
    }

    private fun buildSampleArchive(z: Int, x: Int, y: Int, payload: String): ByteArray = PMTilesWriter.buildArchive(
        tiles = listOf(PMTilesWriter.TileSpec(z = z, x = x, y = y, payload = payload.encodeToByteArray()))
    )

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
        lateinit var executor: OfflineCountryDownloadExecutor
        val repository: DefaultOfflineCountryRepository = newRepository()

        fun newRepository(): DefaultOfflineCountryRepository {
            val exec = OfflineCountryDownloadExecutor(store, server, networkPolicy, clock, dispatcher)
            executor = exec
            val reconciler = OfflineCountryReconciler(store, archives, dispatcher)
            return DefaultOfflineCountryRepository(
                store = store,
                server = server,
                scheduler = scheduler,
                tileNetworkPolicy = networkPolicy,
                userSettingsRepository = settingsRepo,
                executor = exec,
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
        var nextManifestError: Throwable? = null
        var onMidChunk: (() -> Unit)? = null
        var transferGate: CompletableDeferred<Unit>? = null

        override suspend fun fetchManifest(): OfflineCountryManifest {
            nextManifestError?.let { error ->
                nextManifestError = null
                throw error
            }
            return manifest
        }

        override fun fileUrl(file: OfflineCountryFile): String =
            "https://tiles.openhikes.app/countries/${file.fileName}"

        override suspend fun downloadFile(
            file: OfflineCountryFile,
            partialFile: File,
            destinationFile: File,
            maxBytesToTransfer: Long?,
            shouldContinue: () -> Boolean,
            onProgress: (Long) -> Unit
        ): OfflineFileTransferStatus {
            transferGate?.await()
            val bytes = payloads.getValue(file.fileName)
            partialFile.parentFile?.mkdirs()
            val existing = if (partialFile.isFile) partialFile.length().toInt().coerceAtMost(bytes.size) else 0
            val budget = maxBytesToTransfer?.toInt()?.coerceAtLeast(1) ?: (bytes.size - existing)
            val targetEnd = (existing + budget).coerceAtMost(bytes.size)
            var cursor = existing
            while (cursor < targetEnd) {
                if (!shouldContinue()) return OfflineFileTransferStatus.INTERRUPTED
                val nextCursor = (cursor + 32).coerceAtMost(targetEnd)
                partialFile.appendBytes(bytes.copyOfRange(cursor, nextCursor))
                cursor = nextCursor
                onProgress(cursor.toLong())
                onMidChunk?.invoke()
            }
            return if (cursor < bytes.size) {
                OfflineFileTransferStatus.PARTIAL_SLICE
            } else {
                destinationFile.parentFile?.mkdirs()
                partialFile.renameTo(destinationFile)
                OfflineFileTransferStatus.COMPLETE
            }
        }
    }

    private class RecordingScheduler : OfflineCountryDownloadScheduler {
        val enqueuedIds = mutableListOf<String>()
        val continuedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()
        override fun enqueue(countryId: String) {
            enqueuedIds.add(countryId)
        }

        override fun enqueueContinuation(countryId: String) {
            continuedIds.add(countryId)
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

        override suspend fun setJunctionWarningsEnabled(enabled: Boolean) =
            state.update { it.copy(junctionWarningsEnabled = enabled) }

        override suspend fun setShowsPlacePins(enabled: Boolean) = state.update { it.copy(showsPlacePins = enabled) }

        override suspend fun setPlaceSymbolVisible(
            symbol: app.openhikes.core.model.TrailPlaceSymbol,
            visible: Boolean
        ) = state.update { current ->
            val next = if (visible) current.hiddenPlaceSymbols - symbol else current.hiddenPlaceSymbols + symbol
            current.copy(hiddenPlaceSymbols = next)
        }

        override suspend fun setHikeListSort(sort: app.openhikes.core.model.HikeListSort) =
            state.update { it.copy(hikeListSort = sort) }
    }
}
