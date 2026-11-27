package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.pmtiles.PMTilesWriter
import app.openhikes.core.model.ElevationFailure
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.network.ElevationSquareDataSource
import app.openhikes.core.network.ElevationSquaresBatchAnswer
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElevationSquareLibraryTest {
    @Test
    fun readsDownloadedOfflineSquaresFirstWithoutTouchingRemoteAndFallsBackForMissingSquares() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val offlinePoint = GeoCoordinate(47.7185, 12.8317)
        val remotePoint = GeoCoordinate(47.2000, 11.2000)
        val seaPoint = GeoCoordinate(44.0000, 14.0000)

        val offlineSquares = squaresFor(offlinePoint)
        val remoteSquares = squaresFor(remotePoint)
        val seaSquares = squaresFor(seaPoint)

        val downloadedBytes = offlineSquares.associateWith { square -> squareFile(square, height = 610).encode() }
        val remoteAsked = mutableListOf<List<ElevationSquare>>()
        val remoteSource = object : ElevationSquareDataSource {
            override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer {
                remoteAsked.add(squares)
                val response = squares.associateWith { square ->
                    when (square) {
                        in remoteSquares -> squareFile(square, height = 920)
                        in seaSquares -> null
                        else -> null
                    }
                }
                return ElevationSquaresBatchAnswer.Loaded(response)
            }
        }
        val library = ElevationSquareLibrary(
            remoteDataSource = remoteSource,
            downloadedSquares = DownloadedElevationSquares { requested ->
                requested.mapNotNull { sq -> downloadedBytes[sq]?.let { sq to it } }.toMap()
            },
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )

        // Fully covered by offline archives: remote data source is never touched.
        val offlineOnly = library.heights(listOf(offlinePoint)) as ElevationFetchResult.Heights
        assertThat(offlineOnly.heights.single()).isWithin(1e-6).of(610.0)
        assertThat(remoteAsked).isEmpty()

        // Mixed offline + remote land + remote sea (null -> 0.0 m).
        val mixed = library.heights(listOf(offlinePoint, remotePoint, seaPoint)) as ElevationFetchResult.Heights
        assertThat(mixed.heights[0]).isWithin(1e-6).of(610.0)
        assertThat(mixed.heights[1]).isWithin(1e-6).of(920.0)
        assertThat(mixed.heights[2]).isWithin(1e-6).of(0.0)
        assertThat(remoteAsked.single()).doesNotContain(offlineSquares.first())
    }

    @Test
    fun enforcesSquareCapPropagatesFailuresAndMarksWithheldPostsAsNaN() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val point = GeoCoordinate(47.7185, 12.8317)
        val squares = squaresFor(point)
        val withheldLibrary = ElevationSquareLibrary(
            remoteDataSource = object : ElevationSquareDataSource {
                override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer =
                    ElevationSquaresBatchAnswer.Loaded(
                        squares.associateWith { square -> squareFile(square, height = ElevationSquareFile.UNKNOWN) }
                    )
            },
            downloadedSquares = DownloadedElevationSquares { emptyMap() },
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
        val withheld = withheldLibrary.heights(listOf(point)) as ElevationFetchResult.Heights
        assertThat(withheld.heights.single().isNaN()).isTrue()

        // More than 64 distinct eighth-degree squares returns TOO_LONG without touching data sources.
        val hugeRoute = List(70) { idx -> GeoCoordinate(47.0, 10.0 + idx * 0.25) }
        assertThat(withheldLibrary.heights(hugeRoute))
            .isEqualTo(ElevationFetchResult.Failed(ElevationFailure.TOO_LONG))

        val uncoveredLibrary = ElevationSquareLibrary(
            remoteDataSource = object : ElevationSquareDataSource {
                override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer =
                    ElevationSquaresBatchAnswer.Uncovered
            },
            downloadedSquares = DownloadedElevationSquares { emptyMap() },
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
        assertThat(uncoveredLibrary.heights(listOf(point)))
            .isEqualTo(ElevationFetchResult.Failed(ElevationFailure.UNCOVERED))
        assertThat(squares).isNotEmpty()
    }

    @Test
    fun launchAwareSourceHandlesUiTestDormancyScenariosAndProductionDelegation() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val point = GeoCoordinate(47.7185, 12.8317)
        val liveLibrary = ElevationSquareLibrary(
            remoteDataSource = object : ElevationSquareDataSource {
                override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer =
                    ElevationSquaresBatchAnswer.Loaded(
                        squares.associateWith { square -> squareFile(square, height = 740) }
                    )
            },
            downloadedSquares = DownloadedElevationSquares { emptyMap() },
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
        val holder = LaunchConfigHolder(LaunchConfig(isUiTesting = true, elevationScenarioName = null))
        val source = LaunchAwareRouteElevationSource(liveLibrary, holder)

        assertThat(source.isAvailable).isFalse()
        assertThat(source.heights(listOf(point)))
            .isEqualTo(ElevationFetchResult.Failed(ElevationFailure.UNCOVERED))

        holder.mergeWith { it.copy(elevationScenarioName = ElevationScenarios.SEEDED) }
        assertThat(source.isAvailable).isTrue()
        val seeded = source.heights(listOf(point)) as ElevationFetchResult.Heights
        assertThat(seeded.heights.single()).isGreaterThan(530.0)

        holder.mergeWith { it.copy(elevationScenarioName = ElevationScenarios.REFUSED) }
        assertThat(source.heights(listOf(point)))
            .isEqualTo(ElevationFetchResult.Failed(ElevationFailure.UNREACHABLE))

        holder.mergeWith { it.copy(elevationScenarioName = ElevationScenarios.WITHHELD) }
        val withheld = source.heights(listOf(point)) as ElevationFetchResult.Heights
        assertThat(withheld.heights.single().isNaN()).isTrue()

        holder.mergeWith { LaunchConfig.Production }
        assertThat(source.isAvailable).isTrue()
        val live = source.heights(listOf(point)) as ElevationFetchResult.Heights
        assertThat(live.heights.single()).isWithin(1e-6).of(740.0)
    }

    @Test
    fun offlineCountryElevationSquaresWaitsForArchivePreparationBeforeFirstLookup() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val tempDir = Files.createTempDirectory("cold-offline-elev").toFile()
        try {
            val point = GeoCoordinate(47.7185, 12.8317)
            val (store, installed) = createInstalledElevationCountry(tempDir, squaresFor(point), height = 815)
            val archives = OfflineCountryArchives()
            val prepareGate = CompletableDeferred<Unit>()
            val offlineRepo = GatedOfflineCountryRepository(prepareGate) { archives.reopen(listOf(installed), store) }
            var remoteCalls = 0
            val library = ElevationSquareLibrary(
                remoteDataSource = object : ElevationSquareDataSource {
                    override suspend fun readSquares(squares: List<ElevationSquare>): ElevationSquaresBatchAnswer {
                        remoteCalls += 1
                        return ElevationSquaresBatchAnswer.Unreachable
                    }
                },
                downloadedSquares = OfflineCountryElevationSquares(offlineRepo, archives),
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher
            )

            // Cold offline lookup suspends until archive preparation completes, then reads offline without remote.
            val deferred = async { library.heights(listOf(point)) }
            assertThat(deferred.isCompleted).isFalse()
            prepareGate.complete(Unit)

            val result = deferred.await() as ElevationFetchResult.Heights
            assertThat(result.heights.single()).isWithin(1e-6).of(815.0)
            assertThat(remoteCalls).isEqualTo(0)
            archives.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createInstalledElevationCountry(
        tempDir: File,
        squares: Set<ElevationSquare>,
        height: Short
    ): Pair<OfflineCountryStore, InstalledCountryRecord> {
        val store = OfflineCountryStore(File(tempDir, "OfflineCountries"))
        val basemapBytes = PMTilesWriter.buildArchive(
            listOf(PMTilesWriter.TileSpec(z = 4, x = 8, y = 5, payload = "base".encodeToByteArray()))
        )
        val elevationBytes = PMTilesWriter.buildArchive(
            squares.map { sq ->
                PMTilesWriter.TileSpec(
                    z = ElevationSquareGrid.ARCHIVE_ZOOM,
                    x = sq.column,
                    y = sq.row,
                    payload = squareFile(sq, height = height).encode()
                )
            }
        )
        val basemapFile = OfflineCountryFile(
            role = OfflineFileRole.BASEMAP,
            path = "countries/AT-20261126.pmtiles",
            byteCount = basemapBytes.size.toLong(),
            sha256 = OfflineCountryDigest.sha256Hex(basemapBytes),
            minimumZoom = 0,
            maximumZoom = 14
        )
        val elevationFile = OfflineCountryFile(
            role = OfflineFileRole.ELEVATION,
            path = "countries/AT-elevation-20261126.pmtiles",
            byteCount = elevationBytes.size.toLong(),
            sha256 = OfflineCountryDigest.sha256Hex(elevationBytes),
            minimumZoom = 12,
            maximumZoom = 12
        )
        store.fileFor(basemapFile).apply {
            parentFile?.mkdirs()
            writeBytes(basemapBytes)
        }
        store.fileFor(elevationFile).apply {
            parentFile?.mkdirs()
            writeBytes(elevationBytes)
        }
        val country = OfflineCountry(
            id = "AT",
            build = "20261126",
            schema = 4,
            bounds = OfflineBounds(9.0, 46.0, 17.0, 49.0),
            outline = emptyList(),
            files = listOf(basemapFile, elevationFile)
        )
        return store to InstalledCountryRecord(country = country, installedAtEpochMillis = 1L, hasElevation = true)
    }

    private class GatedOfflineCountryRepository(
        private val gate: CompletableDeferred<Unit>,
        private val onPrepared: () -> Unit
    ) : OfflineCountryRepository {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(
            app.openhikes.core.model.OfflineCountriesState(isPrepared = false)
        )

        override suspend fun start() = ensurePrepared()

        override suspend fun ensurePrepared() {
            gate.await()
            onPrepared()
        }

        override suspend fun refreshCatalog() = Unit
        override suspend fun downloadCountry(countryId: String) = Unit
        override suspend fun retryDownload(countryId: String) = Unit
        override suspend fun repairCountry(countryId: String) = Unit
        override suspend fun cancelDownload(countryId: String) = Unit
        override suspend fun deleteCountry(countryId: String) = Unit
        override suspend fun setIncludesDetail(enabled: Boolean) = Unit
        override suspend fun setIncludesElevation(enabled: Boolean) = Unit
        override suspend fun extendCountry(countryId: String) = Unit
        override suspend fun trimCountry(countryId: String) = Unit
        override suspend fun runScheduledDownload(countryId: String) = OfflineDownloadExecutionResult.COMPLETED
    }

    private fun squaresFor(coord: GeoCoordinate): Set<ElevationSquare> =
        requireNotNull(ElevationSquareGrid.postsAround(coord.latitude, coord.longitude))
            .posts
            .map { ElevationSquareGrid.placeOf(it).square }
            .toSet()

    private fun squareFile(square: ElevationSquare, height: Short): ElevationSquareFile {
        val rows = ElevationSquareGrid.ROWS_PER_SQUARE
        val cols = ElevationSquareGrid.columnsPerSquare(square.row)
        return ElevationSquareFile(rows = rows, columns = cols, heights = ShortArray(rows * cols) { height })
    }
}
