package app.openhikes.core.data

import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.elevation.ElevationSquareFile
import app.openhikes.core.model.ElevationFailure
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.ElevationSquare
import app.openhikes.core.model.ElevationSquareGrid
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteElevationSource
import app.openhikes.core.network.ElevationSquareDataSource
import app.openhikes.core.network.ElevationSquaresBatchAnswer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Deterministic UI-test scenario names parsed from `ui_test_elevation` launch extras.
 */
object ElevationScenarios {
    const val SEEDED = "seeded"
    const val REFUSED = "refused"
    const val WITHHELD = "withheld"
}

/**
 * Resolves Copernicus DEM elevations from downloaded offline country archives first, then disk
 * cache / `tiles.openhikes.app`, interpolating bilinearly across the four surrounding posts.
 */
@Singleton
class ElevationSquareLibrary @Inject constructor(
    private val remoteDataSource: ElevationSquareDataSource,
    private val downloadedSquares: DownloadedElevationSquares,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : RouteElevationSource {
    override suspend fun heights(coordinates: List<GeoCoordinate>): ElevationFetchResult {
        if (coordinates.isEmpty()) return ElevationFetchResult.Heights(emptyList())
        val neighborhoods = withContext(defaultDispatcher) {
            coordinates.map { coord ->
                ElevationSquareGrid.postsAround(latitude = coord.latitude, longitude = coord.longitude)
            }
        }
        val squares = neighborhoods
            .asSequence()
            .filterNotNull()
            .flatMap { it.posts.asSequence() }
            .map { ElevationSquareGrid.placeOf(it).square }
            .distinct()
            .toList()
        return if (squares.size > MAXIMUM_SQUARES_PER_REQUEST) {
            ElevationFetchResult.Failed(ElevationFailure.TOO_LONG)
        } else {
            when (val loaded = loadSquares(squares)) {
                is LoadedSquares.Ok -> ElevationFetchResult.Heights(
                    withContext(defaultDispatcher) { interpolateAll(neighborhoods, loaded.files) }
                )
                LoadedSquares.Uncovered -> ElevationFetchResult.Failed(ElevationFailure.UNCOVERED)
                LoadedSquares.Unreachable -> ElevationFetchResult.Failed(ElevationFailure.UNREACHABLE)
            }
        }
    }

    private suspend fun loadSquares(squares: List<ElevationSquare>): LoadedSquares {
        val resolved = LinkedHashMap<ElevationSquare, ElevationSquareFile?>(squares.size)
        val downloadedRaw = withContext(ioDispatcher) { downloadedSquares.elevationSquares(squares) }
        for ((square, bytes) in downloadedRaw) {
            decodeValidated(bytes, square)?.let { resolved[square] = it }
        }
        val missing = squares.filter { it !in resolved }
        return if (missing.isEmpty()) {
            LoadedSquares.Ok(resolved)
        } else {
            when (val remote = remoteDataSource.readSquares(missing)) {
                is ElevationSquaresBatchAnswer.Loaded -> mergeRemoteSquares(resolved, missing, remote.squares)
                ElevationSquaresBatchAnswer.Uncovered -> LoadedSquares.Uncovered
                ElevationSquaresBatchAnswer.Unreachable -> LoadedSquares.Unreachable
            }
        }
    }

    private fun mergeRemoteSquares(
        resolved: MutableMap<ElevationSquare, ElevationSquareFile?>,
        missing: List<ElevationSquare>,
        remoteSquares: Map<ElevationSquare, ElevationSquareFile?>
    ): LoadedSquares = if (missing.all { it in remoteSquares }) {
        for (square in missing) {
            resolved[square] = remoteSquares[square]
        }
        LoadedSquares.Ok(resolved)
    } else {
        LoadedSquares.Unreachable
    }

    private fun interpolateAll(
        neighborhoods: List<ElevationSquareGrid.Neighborhood?>,
        files: Map<ElevationSquare, ElevationSquareFile?>
    ): List<Double> = neighborhoods.map { around ->
        around?.height { post ->
            val place = ElevationSquareGrid.placeOf(post)
            if (place.square !in files) {
                null
            } else {
                val file = files[place.square]
                if (file == null) 0.0 else file.heightAt(place.index)
            }
        } ?: Double.NaN
    }

    private sealed interface LoadedSquares {
        data class Ok(val files: Map<ElevationSquare, ElevationSquareFile?>) : LoadedSquares

        data object Uncovered : LoadedSquares

        data object Unreachable : LoadedSquares
    }

    companion object {
        const val MAXIMUM_SQUARES_PER_REQUEST = 64

        fun decodeValidated(bytes: ByteArray, square: ElevationSquare): ElevationSquareFile? {
            val file = runCatching { ElevationSquareFile.decode(bytes) }.getOrNull() ?: return null
            val expectedColumns = ElevationSquareGrid.columnsPerSquare(square.row)
            return file.takeIf { it.rows == ElevationSquareGrid.ROWS_PER_SQUARE && it.columns == expectedColumns }
        }
    }
}

/**
 * Launch-aware [RouteElevationSource] that serves deterministic synthetic heights in UI test
 * scenarios, stays dormant in UI tests without an elevation scenario, and delegates to
 * [ElevationSquareLibrary] in production launches.
 */
@Singleton
internal class LaunchAwareRouteElevationSource @Inject constructor(
    private val liveLibrary: ElevationSquareLibrary,
    private val launchConfigHolder: LaunchConfigHolder
) : RouteElevationSource {
    override val isAvailable: Boolean
        get() {
            val config = launchConfigHolder.current.value
            return !config.isUiTesting || config.elevationScenarioName != null
        }

    override suspend fun heights(coordinates: List<GeoCoordinate>): ElevationFetchResult {
        val config = launchConfigHolder.current.value
        return when (config.elevationScenarioName) {
            ElevationScenarios.SEEDED -> ElevationFetchResult.Heights(
                coordinates.map { coord -> seededElevationMeters(coord) }
            )
            ElevationScenarios.REFUSED -> ElevationFetchResult.Failed(ElevationFailure.UNREACHABLE)
            ElevationScenarios.WITHHELD -> ElevationFetchResult.Heights(List(coordinates.size) { Double.NaN })
            null -> if (config.isUiTesting) {
                ElevationFetchResult.Failed(ElevationFailure.UNCOVERED)
            } else {
                liveLibrary.heights(coordinates)
            }
            else -> ElevationFetchResult.Failed(ElevationFailure.UNCOVERED)
        }
    }

    private companion object {
        const val SEEDED_BASE_METERS = 530.0
        const val SEEDED_REF_LAT = 47.715
        const val SEEDED_REF_LON = 12.830
        const val SEEDED_LAT_SLOPE = 28_000.0
        const val SEEDED_LON_SLOPE = 12_000.0

        fun seededElevationMeters(coord: GeoCoordinate): Double = SEEDED_BASE_METERS +
            abs(coord.latitude - SEEDED_REF_LAT) * SEEDED_LAT_SLOPE +
            abs(coord.longitude - SEEDED_REF_LON) * SEEDED_LON_SLOPE
    }
}
