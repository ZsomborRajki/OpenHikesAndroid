package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.offline.OfflineStoreList
import app.openhikes.core.common.pmtiles.PMTilesWriter
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryExtra
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.PendingCountryDownload
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.UserSettings
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.OfflineFileTransferStatus
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineCountryMutationSafetyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cancelledTrimStillPublishesCommittedIndexAndUnlocksAddOffer() = runTest {
        val callerDispatcher = StandardTestDispatcher(testScheduler)
        val ioDispatcher = InterceptingDispatcher(StandardTestDispatcher(testScheduler))
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val elevation = sampleTileArchive(z = 12, payload = "si-elev")
        val slovenia = buildCountry(basemap = basemap, elevation = elevation)
        val payloads = mapOf(basemapEntry(slovenia, basemap), elevationEntry(slovenia, elevation))
        val harness = createHarness(slovenia, payloads, ioDispatcher)
        harness.installOnDisk(
            country = slovenia,
            hasDetail = false,
            hasElevation = true,
            basemap = basemap,
            elevation = elevation
        )

        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        repo.setIncludesElevation(false)
        assertThat(repo.state.value.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)

        var trimJob: kotlinx.coroutines.Job? = null
        ioDispatcher.onAfterNextDispatch = { trimJob?.cancel() }
        trimJob = launch(callerDispatcher) { repo.trimCountry("SI") }
        testScheduler.advanceUntilIdle()

        assertThat(repo.state.value.installed["SI"]?.hasElevation).isFalse()
        assertThat(repo.state.value.removal("SI")).isNull()
        val persisted = (harness.store.readIndex() as OfflineStoreList.Holding).items.single()
        assertThat(persisted.hasElevation).isFalse()
        val elevationFile = slovenia.files.first { it.role == OfflineFileRole.ELEVATION }
        assertThat(harness.store.fileFor(elevationFile).exists()).isFalse()

        repo.setIncludesElevation(true)
        assertThat(repo.state.value.addition("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        harness.archives.close()
    }

    @Test
    fun refusedSmallerRetryPreservesDroppedFileCleanupOnLaterRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val elevation = sampleTileArchive(z = 12, payload = "si-elev")
        val listed = buildCountry(basemap = basemap, detail = detail, elevation = elevation)
        val harness = createHarness(
            listed,
            mapOf(basemapEntry(listed, basemap), detailEntry(listed, detail), elevationEntry(listed, elevation)),
            dispatcher
        )
        harness.installOnDisk(listed, hasDetail = false, hasElevation = false, basemap = basemap)
        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        repo.extendCountry("SI")

        harness.server.failingFileNames.add("SI-elevation-20261117.pmtiles")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }
        assertThat(harness.store.stagedFileFor(detailFile).isFile).isTrue()

        // Turn Extra Detail off and retry while Battery Saver refuses preflight.
        repo.setIncludesDetail(false)
        harness.policy.setPowerSave(true)
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads["SI"]?.failure).isEqualTo(OfflineDownloadFailure.CONSERVING)
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.ELEVATION)
        assertThat(harness.store.stagedFileFor(detailFile).isFile).isTrue()

        // Once Battery Saver clears, the second retry shrinks pending.json and deletes the dropped Detail file.
        harness.server.failingFileNames.clear()
        harness.policy.setPowerSave(false)
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads["SI"]?.phase).isEqualTo(OfflineDownloadPhase.DOWNLOADING)
        assertThat(harness.store.stagedFileFor(detailFile).exists()).isFalse()
        val pendingOnDisk = (harness.store.readPending() as OfflineStoreList.Holding).items.single()
        assertThat(pendingOnDisk.roles).containsExactly(OfflineFileRole.ELEVATION)
        harness.archives.close()
    }

    @Test
    fun cancelAfterRefusedSmallerRetryDeletesDroppedStagedFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val elevation = sampleTileArchive(z = 12, payload = "si-elev")
        val listed = buildCountry(basemap = basemap, detail = detail, elevation = elevation)
        val harness = createHarness(
            listed,
            mapOf(basemapEntry(listed, basemap), detailEntry(listed, detail), elevationEntry(listed, elevation)),
            dispatcher
        )
        harness.installOnDisk(listed, hasDetail = false, hasElevation = false, basemap = basemap)
        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        repo.extendCountry("SI")

        harness.server.failingFileNames.add("SI-elevation-20261117.pmtiles")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }

        repo.setIncludesDetail(false)
        harness.policy.setPowerSave(true)
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.ELEVATION)

        repo.cancelDownload("SI")
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat(harness.store.stagedFileFor(detailFile).exists()).isFalse()
        assertThat((harness.store.readPending() as OfflineStoreList.Holding).items).isEmpty()
        harness.archives.close()
    }

    @Test
    fun staleRecoveryVerificationIgnoresReplacementAttemptWithSameBuildAndRoles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val listed = buildCountry(basemap = basemap, detail = detail)
        val harness = createHarness(
            listed,
            mapOf(basemapEntry(listed, basemap), detailEntry(listed, detail)),
            dispatcher
        )
        harness.installOnDisk(listed, hasDetail = false, hasElevation = false, basemap = basemap)
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }
        harness.store.stagedFileFor(detailFile).writeBytes(detail)
        harness.store.writePending(listOf(PendingCountryDownload(listed, listOf(OfflineFileRole.DETAIL))))

        val verified = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.executor.verifyFile = { file, staged ->
            val whole = OfflineCountryDigest.isWhole(file, staged)
            verified.complete(Unit)
            release.await()
            whole
        }

        val repo = harness.repository
        repo.refreshCatalog()
        val startJob = launch(dispatcher) { repo.start() }
        verified.await()

        // Cancel recovery and queue a brand-new addition for the exact same country, build, and roles.
        repo.cancelDownload("SI")
        assertThat(harness.store.stagedFileFor(detailFile).exists()).isFalse()
        repo.extendCountry("SI")
        val replacementAttemptId = requireNotNull(repo.state.value.downloads["SI"]).attemptId

        release.complete(Unit)
        startJob.join()

        val live = requireNotNull(repo.state.value.downloads["SI"])
        assertThat(live.attemptId).isEqualTo(replacementAttemptId)
        assertThat(live.phase).isEqualTo(OfflineDownloadPhase.DOWNLOADING)
        assertThat(live.failure).isNull()

        harness.executor.verifyFile = { file, staged -> OfflineCountryDigest.isWhole(file, staged) }
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(repo.state.value.installed["SI"]?.hasDetail).isTrue()
        harness.archives.close()
    }

    @Test
    fun staleFailedVerificationDoesNotDeleteOrDamageReplacementAttempt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val listed = buildCountry(basemap = basemap, detail = detail)
        val harness = createHarness(
            listed,
            mapOf(basemapEntry(listed, basemap), detailEntry(listed, detail)),
            dispatcher
        )
        harness.installOnDisk(listed, hasDetail = false, hasElevation = false, basemap = basemap)
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }
        harness.store.stagedFileFor(detailFile).writeBytes(detail)
        harness.store.writePending(listOf(PendingCountryDownload(listed, listOf(OfflineFileRole.DETAIL))))

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.executor.verifyFile = { _, _ ->
            entered.complete(Unit)
            release.await()
            false
        }

        val repo = harness.repository
        repo.refreshCatalog()
        val startJob = launch(dispatcher) { repo.start() }
        entered.await()

        repo.cancelDownload("SI")
        repo.extendCountry("SI")
        harness.store.stagedFileFor(detailFile).writeBytes(detail)

        release.complete(Unit)
        startJob.join()

        assertThat(repo.state.value.downloads["SI"]?.phase).isEqualTo(OfflineDownloadPhase.DOWNLOADING)
        assertThat(repo.state.value.downloads["SI"]?.failure).isNull()
        assertThat(harness.store.stagedFileFor(detailFile).isFile).isTrue()
        harness.archives.close()
    }

    private fun sampleTileArchive(z: Int, payload: String): ByteArray = PMTilesWriter.buildArchive(
        tiles = listOf(PMTilesWriter.TileSpec(z = z, x = 8, y = 5, payload = payload.encodeToByteArray()))
    )

    private fun basemapEntry(country: OfflineCountry, bytes: ByteArray): Pair<String, ByteArray> =
        country.files.first { it.role == OfflineFileRole.BASEMAP }.fileName to bytes

    private fun detailEntry(country: OfflineCountry, bytes: ByteArray): Pair<String, ByteArray> =
        country.files.first { it.role == OfflineFileRole.DETAIL }.fileName to bytes

    private fun elevationEntry(country: OfflineCountry, bytes: ByteArray): Pair<String, ByteArray> =
        country.files.first { it.role == OfflineFileRole.ELEVATION }.fileName to bytes

    private fun buildCountry(
        basemap: ByteArray,
        detail: ByteArray? = null,
        elevation: ByteArray? = null
    ): OfflineCountry {
        val files = buildList {
            add(countryFile(OfflineFileRole.BASEMAP, "countries/SI-20261117.pmtiles", basemap, 0, 14))
            if (detail != null) {
                add(countryFile(OfflineFileRole.DETAIL, "countries/SI-detail-20261117.pmtiles", detail, 15, 15))
            }
            if (elevation != null) {
                add(
                    countryFile(OfflineFileRole.ELEVATION, "countries/SI-elevation-20261117.pmtiles", elevation, 12, 12)
                )
            }
        }
        return OfflineCountry(
            id = "SI",
            build = "20261117",
            schema = 4,
            bounds = OfflineBounds(13.6, 45.45, 16.6, 46.85),
            files = files
        )
    }

    private fun countryFile(
        role: OfflineFileRole,
        path: String,
        bytes: ByteArray,
        minZoom: Int,
        maxZoom: Int
    ): OfflineCountryFile = OfflineCountryFile(
        role = role,
        path = path,
        byteCount = bytes.size.toLong(),
        sha256 = OfflineCountryDigest.sha256Hex(bytes),
        minimumZoom = minZoom,
        maximumZoom = maxZoom
    )

    private fun createHarness(
        country: OfflineCountry,
        payloads: Map<String, ByteArray>,
        dispatcher: CoroutineDispatcher
    ): Harness = Harness(
        store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries")),
        server = StubServer(OfflineCountryManifest(1, listOf(country)), payloads),
        scheduler = NoOpScheduler(),
        policy = MutablePolicy(),
        settingsRepo = InMemorySettingsRepo(),
        archives = OfflineCountryArchives(),
        clock = TestClock(100_000L),
        dispatcher = dispatcher
    )

    private class InterceptingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var onAfterNextDispatch: (() -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                block.run()
                val callback = onAfterNextDispatch
                if (callback != null) {
                    onAfterNextDispatch = null
                    callback()
                }
            }
        }
    }

    private class Harness(
        val store: OfflineCountryStore,
        val server: StubServer,
        val scheduler: NoOpScheduler,
        val policy: MutablePolicy,
        val settingsRepo: InMemorySettingsRepo,
        val archives: OfflineCountryArchives,
        val clock: TestClock,
        val dispatcher: CoroutineDispatcher
    ) {
        val executor = OfflineCountryDownloadExecutor(store, server, policy, clock, dispatcher)
        val repository = DefaultOfflineCountryRepository(
            store = store,
            server = server,
            scheduler = scheduler,
            tileNetworkPolicy = policy,
            userSettingsRepository = settingsRepo,
            executor = executor,
            reconciler = OfflineCountryReconciler(
                store = store,
                archives = archives,
                launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
                ioDispatcher = dispatcher
            ),
            ioDispatcher = dispatcher
        )

        fun installOnDisk(
            country: OfflineCountry,
            hasDetail: Boolean,
            hasElevation: Boolean,
            basemap: ByteArray,
            elevation: ByteArray? = null
        ) {
            store.prepare()
            store.fileFor(country.files.first { it.role == OfflineFileRole.BASEMAP }).writeBytes(basemap)
            if (elevation != null) {
                store.fileFor(country.files.first { it.role == OfflineFileRole.ELEVATION }).writeBytes(elevation)
            }
            store.writeInstalled(
                listOf(
                    InstalledCountryRecord(
                        country = country,
                        installedAtEpochMillis = 1_000L,
                        hasDetail = hasDetail,
                        hasElevation = hasElevation
                    )
                )
            )
        }
    }

    private class StubServer(
        private val manifest: OfflineCountryManifest,
        private val payloads: Map<String, ByteArray>
    ) : OfflineCountryServer {
        val failingFileNames = mutableSetOf<String>()

        override suspend fun fetchManifest(): OfflineCountryManifest = manifest

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
            if (file.fileName in failingFileNames) throw IOException("Simulated failure for ${file.fileName}")
            val bytes = payloads.getValue(file.fileName)
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(bytes)
            onProgress(bytes.size.toLong())
            return OfflineFileTransferStatus.COMPLETE
        }
    }

    private class NoOpScheduler : OfflineCountryDownloadScheduler {
        override fun enqueue(countryId: String) = Unit
        override fun enqueueContinuation(countryId: String) = Unit
        override fun cancel(countryId: String) = Unit
    }

    private class MutablePolicy : TileNetworkPolicy {
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
        fun setPowerSave(enabled: Boolean) {
            conditionsFlow.update { it.copy(isPowerSaveMode = enabled) }
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

        override suspend fun setOfflineCountriesElevationData(enabled: Boolean) =
            state.update { it.copy(offlineCountriesElevationData = enabled) }

        override suspend fun setJunctionWarningsEnabled(enabled: Boolean) =
            state.update { it.copy(junctionWarningsEnabled = enabled) }

        override suspend fun setShowsPlacePins(enabled: Boolean) = state.update { it.copy(showsPlacePins = enabled) }
        override suspend fun setPlaceSymbolVisible(symbol: TrailPlaceSymbol, visible: Boolean) = Unit
        override suspend fun setHikeListSort(sort: HikeListSort) = state.update { it.copy(hikeListSort = sort) }
        override suspend fun setRouteColoring(coloring: RouteColoring) =
            state.update { it.copy(routeColoring = coloring) }
    }
}
