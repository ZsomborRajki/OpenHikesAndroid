package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.offline.OfflineStoreList
import app.openhikes.core.common.pmtiles.PMTilesWriter
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryExtra
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
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
import app.openhikes.core.network.ElevationSquareDataSource
import app.openhikes.core.network.ElevationSquaresBatchAnswer
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.OfflineFileTransferStatus
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
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

class OfflineCountryOffersRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val ljubljana = GeoCoordinate(latitude = 46.0569, longitude = 14.5058)
    private val ljubljanaSquares = requireNotNull(
        ElevationSquareGrid.postsAround(ljubljana.latitude, ljubljana.longitude)
    ).posts.map { ElevationSquareGrid.placeOf(it).square }.toSet()

    @Test
    fun elevationSwitchOffSkipsHeightsAndOffersExtendThenTrimAndServesOffline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val elevation = buildElevationArchive(ljubljanaSquares, height = 295)
        val slovenia = buildCountry(basemap = basemap, elevation = elevation)
        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(slovenia)),
            payloads = mapOf("SI-20261117.pmtiles" to basemap, "SI-elevation-20261117.pmtiles" to elevation),
            dispatcher = dispatcher
        )
        val repo = harness.repository
        repo.refreshCatalog()

        repo.setIncludesElevation(false)
        repo.downloadCountry("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.BASEMAP)
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isFalse()

        repo.setIncludesElevation(true)
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat(repo.state.value.addition("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        repo.extendCountry("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.ELEVATION)
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isTrue()
        assertThat(readOfflineHeight(repo, harness.archives, dispatcher)).isWithin(1e-6).of(295.0)

        repo.setIncludesElevation(false)
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isTrue()
        assertThat(repo.state.value.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        repo.trimCountry("SI")
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isFalse()
        assertThat(repo.state.value.isDrawn("SI")).isTrue()
        assertThat(harness.archives.elevationSquares(ljubljanaSquares.toList())).isEmpty()

        val restarted = harness.newRepository()
        restarted.start()
        assertThat(restarted.state.value.includesElevation).isFalse()
        assertThat(restarted.state.value.installed["SI"]?.hasElevation).isFalse()
        harness.archives.close()
    }

    @Test
    fun mandatoryGainedFilesAutoDownloadWhileOptionalExtrasWaitAndCarryWithOffer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val extras = sampleTileArchive(z = 14, payload = "si-extras")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val elevation = buildElevationArchive(ljubljanaSquares, height = 295)
        val gained = buildCountry(basemap = basemap, extras = extras, detail = detail, elevation = elevation)
        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(gained)),
            payloads = mapOf(
                "SI-20261117.pmtiles" to basemap,
                "SI-extras-20261117.pmtiles" to extras,
                "SI-detail-20261117.pmtiles" to detail,
                "SI-elevation-20261117.pmtiles" to elevation
            ),
            dispatcher = dispatcher
        )
        harness.installOnDisk(buildCountry(basemap = basemap), false, false, mapOf(OfflineFileRole.BASEMAP to basemap))

        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.EXTRAS)
        assertThat(repo.state.value.addition("SI")).isNull()

        repo.cancelDownload("SI")
        val offer = requireNotNull(repo.state.value.addition("SI"))
        assertThat(offer.extras).containsExactly(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION)
        assertThat(offer.files.map { it.role })
            .containsExactly(OfflineFileRole.EXTRAS, OfflineFileRole.DETAIL, OfflineFileRole.ELEVATION)
            .inOrder()

        repo.extendCountry("SI")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)
        assertThat(repo.state.value.installed["SI"]?.hasDetail).isTrue()
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isTrue()

        repo.setIncludesDetail(false)
        repo.setIncludesElevation(false)
        assertThat(repo.state.value.removal("SI")?.extras)
            .containsExactly(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION)
        repo.trimCountry("SI")
        assertThat(repo.state.value.installed["SI"]?.files?.map { it.role })
            .containsExactly(OfflineFileRole.BASEMAP, OfflineFileRole.EXTRAS)
        harness.archives.close()
    }

    @Test
    fun retryingForLessAbandonsDroppedStagedFilesAndCancelsWhenAllSwitchesOff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val elevation = buildElevationArchive(ljubljanaSquares, height = 295)
        val listed = buildCountry(basemap = basemap, detail = detail, elevation = elevation)
        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(listed)),
            payloads = mapOf(
                "SI-20261117.pmtiles" to basemap,
                "SI-detail-20261117.pmtiles" to detail,
                "SI-elevation-20261117.pmtiles" to elevation
            ),
            dispatcher = dispatcher
        )
        harness.installOnDisk(listed, false, false, mapOf(OfflineFileRole.BASEMAP to basemap))

        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        repo.extendCountry("SI")

        harness.server.failingFileNames.add("SI-elevation-20261117.pmtiles")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }
        assertThat(harness.store.stagedFileFor(detailFile).isFile).isTrue()

        harness.server.failingFileNames.clear()
        repo.setIncludesDetail(false)
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.ELEVATION)
        assertThat(harness.store.stagedFileFor(detailFile).exists()).isFalse()
        val pendingOnDisk = (harness.store.readPending() as OfflineStoreList.Holding).items
        assertThat(pendingOnDisk.single().roles).containsExactly(OfflineFileRole.ELEVATION)

        harness.server.failingFileNames.add("SI-elevation-20261117.pmtiles")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.FAILED)
        repo.setIncludesElevation(false)
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat((harness.store.readPending() as OfflineStoreList.Holding).items).isEmpty()
        harness.archives.close()
    }

    @Test
    fun retryingAdditionPastServerBuildEndsDownloadWithoutStartingUpdate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val listed = buildCountry(basemap = basemap, detail = detail)
        val newerBuild = buildCountry(build = "20261201", basemap = basemap, detail = detail)
        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(listed)),
            payloads = mapOf("SI-20261117.pmtiles" to basemap, "SI-detail-20261117.pmtiles" to detail),
            dispatcher = dispatcher
        )
        harness.installOnDisk(listed, false, false, mapOf(OfflineFileRole.BASEMAP to basemap))

        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        repo.extendCountry("SI")
        harness.server.failingFileNames.add("SI-detail-20261117.pmtiles")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.FAILED)

        harness.server.manifest = OfflineCountryManifest(1, listOf(newerBuild))
        repo.retryDownload("SI")
        assertThat(repo.state.value.downloads).isEmpty()
        assertThat(repo.state.value.hasUpdate(newerBuild)).isTrue()
        assertThat(repo.state.value.addition("SI")).isNull()
        harness.archives.close()
    }

    private suspend fun readOfflineHeight(
        repo: OfflineCountryRepository,
        archives: OfflineCountryArchives,
        dispatcher: CoroutineDispatcher
    ): Double {
        val library = ElevationSquareLibrary(
            remoteDataSource = object : ElevationSquareDataSource {
                override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer =
                    ElevationSquaresBatchAnswer.Unreachable
            },
            downloadedSquares = OfflineCountryElevationSquares(repo, archives),
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
        return (library.heights(listOf(ljubljana)) as ElevationFetchResult.Heights).heights.single()
    }

    @Test
    fun repairIgnoresSwitchesAndTrimmingBrokenOptionalFileClearsRepairAndUnlocksAdd() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val elevation = buildElevationArchive(ljubljanaSquares, height = 295)
        val listed = buildCountry(basemap = basemap, detail = detail, elevation = elevation)

        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(listed)),
            payloads = mapOf(
                "SI-20261117.pmtiles" to basemap,
                "SI-detail-20261117.pmtiles" to detail,
                "SI-elevation-20261117.pmtiles" to elevation
            ),
            dispatcher = dispatcher
        )
        harness.installOnDisk(
            listed,
            hasDetail = false,
            hasElevation = true,
            payloads = mapOf(
                OfflineFileRole.BASEMAP to basemap,
                OfflineFileRole.ELEVATION to "not-an-archive".encodeToByteArray()
            )
        )

        val repo = harness.repository
        repo.start()
        repo.refreshCatalog()
        assertThat(repo.state.value.needsRepair("SI")).isTrue()
        assertThat(repo.state.value.addition("SI")).isNull()

        // Turning Elevation Data OFF: repair still mends the broken held file!
        repo.setIncludesElevation(false)
        repo.repairCountry("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.ELEVATION)
        repo.cancelDownload("SI")

        // Trimming the broken elevation file clears needsRepair and unlocks the Add Extra Detail offer.
        assertThat(repo.state.value.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        repo.trimCountry("SI")
        assertThat(repo.state.value.needsRepair("SI")).isFalse()
        assertThat(repo.state.value.addition("SI")?.extras).containsExactly(OfflineCountryExtra.DETAIL)
        harness.archives.close()
    }

    @Test
    fun recoveringDownloadBlocksOffersUntilStartFinishesAndPrunesAlreadyInstalledPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val detail = sampleTileArchive(z = 15, payload = "si-z15")
        val listed = buildCountry(basemap = basemap, detail = detail)

        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(listed)),
            payloads = mapOf("SI-20261117.pmtiles" to basemap, "SI-detail-20261117.pmtiles" to detail),
            dispatcher = dispatcher
        )
        harness.installOnDisk(
            listed,
            hasDetail = false,
            hasElevation = false,
            payloads = mapOf(
                OfflineFileRole.BASEMAP to basemap
            )
        )
        val detailFile = listed.files.first { it.role == OfflineFileRole.DETAIL }
        harness.store.stagedFileFor(detailFile).writeBytes(detail)
        harness.store.writePending(listOf(PendingCountryDownload(listed, listOf(OfflineFileRole.DETAIL))))

        val repo = harness.repository
        // ensurePrepared() loads installed + marks staged ready-to-finish SI as recovering before start() verifies it.
        repo.ensurePrepared()
        assertThat(repo.state.value.recovering).contains("SI")
        assertThat(repo.state.value.addition("SI")).isNull()
        assertThat(repo.state.value.removal("SI")).isNull()
        repo.extendCountry("SI")
        assertThat(harness.scheduler.enqueuedIds).isEmpty()

        // start() verifies & installs the staged detail and clears recovering.
        repo.start()
        assertThat(repo.state.value.recovering).isEmpty()
        assertThat(repo.state.value.installed["SI"]?.hasDetail).isTrue()

        // A stale pending.json entry whose files are already installed cleanly is pruned on relaunch.
        harness.store.writePending(listOf(PendingCountryDownload(listed, listOf(OfflineFileRole.BASEMAP))))
        val relaunched = harness.newRepository()
        relaunched.start()
        assertThat(relaunched.state.value.downloads).isEmpty()
        assertThat((harness.store.readPending() as OfflineStoreList.Holding).items).isEmpty()
        harness.archives.close()
    }

    @Test
    fun turningSwitchOffMidwayLeavesDownloadRunningAndCancellingUpdateKeepsOldExtras() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val basemap = sampleTileArchive(z = 14, payload = "si-z14")
        val elevation = buildElevationArchive(ljubljanaSquares, height = 295)
        val older = buildCountry(build = "20261117", basemap = basemap, elevation = elevation)
        val newer = buildCountry(build = "20261201", basemap = basemap, elevation = elevation)

        val harness = createHarness(
            manifest = OfflineCountryManifest(1, listOf(older)),
            payloads = mapOf(
                "SI-20261117.pmtiles" to basemap,
                "SI-elevation-20261117.pmtiles" to elevation,
                "SI-20261201.pmtiles" to basemap,
                "SI-elevation-20261201.pmtiles" to elevation
            ),
            dispatcher = dispatcher
        )
        val repo = harness.repository
        repo.refreshCatalog()
        repo.downloadCountry("SI")
        assertThat(repo.runScheduledDownload("SI")).isEqualTo(OfflineDownloadExecutionResult.COMPLETED)

        harness.server.manifest = OfflineCountryManifest(1, listOf(newer))
        repo.refreshCatalog()
        repo.downloadCountry("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles)
            .containsExactly(OfflineFileRole.BASEMAP, OfflineFileRole.ELEVATION)

        // Flipping Elevation Data OFF while update runs leaves update running; cancel preserves old build's elevation.
        repo.setIncludesElevation(false)
        assertThat(repo.state.value.downloads["SI"]).isNotNull()
        repo.cancelDownload("SI")
        assertThat(repo.state.value.installed["SI"]?.hasElevation).isTrue()
        assertThat(repo.state.value.removal("SI")?.extras).containsExactly(OfflineCountryExtra.ELEVATION)
        assertThat(repo.state.value.addition("SI")).isNull()

        // Starting the update again now obeys Elevation Data OFF.
        repo.downloadCountry("SI")
        assertThat(repo.state.value.downloads["SI"]?.roles).containsExactly(OfflineFileRole.BASEMAP)
        harness.archives.close()
    }

    private fun sampleTileArchive(z: Int, payload: String): ByteArray = PMTilesWriter.buildArchive(
        tiles = listOf(PMTilesWriter.TileSpec(z = z, x = 8, y = 5, payload = payload.encodeToByteArray()))
    )

    private fun buildElevationArchive(squares: Set<ElevationSquare>, height: Short): ByteArray =
        PMTilesWriter.buildArchive(
            tiles = squares.map { sq ->
                val rows = ElevationSquareGrid.ROWS_PER_SQUARE
                val cols = ElevationSquareGrid.columnsPerSquare(sq.row)
                val file =
                    ElevationSquareFile(rows = rows, columns = cols, heights = ShortArray(rows * cols) { height })
                PMTilesWriter.TileSpec(
                    z = ElevationSquareGrid.ARCHIVE_ZOOM,
                    x = sq.column,
                    y = sq.row,
                    payload = file.encode()
                )
            }
        )

    private fun buildCountry(
        id: String = "SI",
        build: String = "20261117",
        basemap: ByteArray,
        extras: ByteArray? = null,
        detail: ByteArray? = null,
        elevation: ByteArray? = null
    ): OfflineCountry {
        val files = buildList {
            add(countryFile(OfflineFileRole.BASEMAP, "countries/$id-$build.pmtiles", basemap, 0, 14))
            if (extras != null) {
                add(countryFile(OfflineFileRole.EXTRAS, "countries/$id-extras-$build.pmtiles", extras, 0, 14))
            }
            if (detail != null) {
                add(countryFile(OfflineFileRole.DETAIL, "countries/$id-detail-$build.pmtiles", detail, 15, 15))
            }
            if (elevation != null) {
                add(countryFile(OfflineFileRole.ELEVATION, "countries/$id-elevation-$build.pmtiles", elevation, 12, 12))
            }
        }
        return OfflineCountry(
            id = id,
            build = build,
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
        manifest: OfflineCountryManifest,
        payloads: Map<String, ByteArray>,
        dispatcher: CoroutineDispatcher
    ): Harness = Harness(
        store = OfflineCountryStore(File(tempFolder.root, "OfflineCountries")),
        server = StubServer(manifest, payloads),
        scheduler = RecordingScheduler(),
        policy = StaticPolicy(),
        settingsRepo = InMemorySettingsRepo(),
        archives = OfflineCountryArchives(),
        clock = TestClock(100_000L),
        dispatcher = dispatcher
    )

    private class Harness(
        val store: OfflineCountryStore,
        val server: StubServer,
        val scheduler: RecordingScheduler,
        val policy: StaticPolicy,
        val settingsRepo: InMemorySettingsRepo,
        val archives: OfflineCountryArchives,
        val clock: TestClock,
        val dispatcher: CoroutineDispatcher
    ) {
        val repository: DefaultOfflineCountryRepository = newRepository()

        fun newRepository(): DefaultOfflineCountryRepository {
            val executor = OfflineCountryDownloadExecutor(store, server, policy, clock, dispatcher)
            val reconciler = OfflineCountryReconciler(
                store = store,
                archives = archives,
                launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
                ioDispatcher = dispatcher
            )
            return DefaultOfflineCountryRepository(
                store = store,
                server = server,
                scheduler = scheduler,
                tileNetworkPolicy = policy,
                userSettingsRepository = settingsRepo,
                executor = executor,
                reconciler = reconciler,
                ioDispatcher = dispatcher
            )
        }

        fun installOnDisk(
            country: OfflineCountry,
            hasDetail: Boolean,
            hasElevation: Boolean,
            payloads: Map<OfflineFileRole, ByteArray>
        ) {
            store.prepare()
            for (file in country.files) {
                val data = payloads[file.role] ?: continue
                store.fileFor(file).writeBytes(data)
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

    private class StubServer(var manifest: OfflineCountryManifest, private val payloads: Map<String, ByteArray>) :
        OfflineCountryServer {
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

    private class RecordingScheduler : OfflineCountryDownloadScheduler {
        val enqueuedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()
        override fun enqueue(countryId: String) {
            enqueuedIds.add(countryId)
        }

        override fun enqueueContinuation(countryId: String) = Unit

        override fun cancel(countryId: String) {
            cancelledIds.add(countryId)
        }
    }

    private class StaticPolicy : TileNetworkPolicy {
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
