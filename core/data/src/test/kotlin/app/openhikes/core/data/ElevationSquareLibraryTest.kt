package app.openhikes.core.data

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.model.ElevationFailure
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.network.ElevationSquareDataSource
import app.openhikes.core.network.ElevationSquaresBatchAnswer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
