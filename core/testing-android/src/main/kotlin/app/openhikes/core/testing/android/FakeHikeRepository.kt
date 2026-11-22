package app.openhikes.core.testing.android

import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.HikeTitleEdit
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.TrailPoint
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [HikeRepository] fake for deterministic ViewModel, Compose screen, and shell tests.
 */
class FakeHikeRepository(initialHikes: List<Hike> = emptyList()) : HikeRepository {
    private val hikesState = MutableStateFlow(initialHikes)
    private val localStateMap = MutableStateFlow<Map<HikeId, HikeLocalState>>(emptyMap())
    private val uriPayloads = HashMap<String, Pair<String?, ByteArray>>()

    var nextImportFailure: GpxImportFailure? = null
    var nextRenameFailure: Throwable? = null
    var nextStyleFailure: Throwable? = null
    var nextDeleteFailure: Throwable? = null
    var nextExportFailure: Throwable? = null
    var seededFixtureBytes: Pair<String, ByteArray>? = null
    var lastExportedUri: Pair<String, String>? = null
        private set

    override val hikes: Flow<List<HikeSummary>> = hikesState.map { list ->
        val local = localStateMap.value
        list.sortedByDescending { it.dateEpochMillis }.map { hike ->
            val state = local[hike.id]
            hike.toSummary(climbMeters = state?.climbMeters, descentMeters = state?.descentMeters)
        }
    }

    override val mapRoutes: Flow<List<HikeMapRoute>> = hikesState.map { list ->
        list.filterNot { it.isRecording }.map { hike ->
            HikeMapRoute(
                hikeId = hike.id,
                route = hike.route,
                tintHex = hike.tintHex,
                routeBorderHex = hike.routeBorderHex,
                routeWidthDp = hike.routeWidthDp,
                linePattern = hike.linePattern
            )
        }
    }

    fun registerUriPayload(uriString: String, bytes: ByteArray, fileName: String? = null) {
        uriPayloads[uriString] = fileName to bytes
    }

    override fun observeHike(hikeId: HikeId): Flow<Hike?> =
        hikesState.map { list -> list.firstOrNull { it.id == hikeId } }

    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> = localStateMap.map { map -> map[hikeId] }

    override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult {
        val failure = nextImportFailure?.also { nextImportFailure = null }
        val result = if (failure != null) {
            GpxImportResult.Failure(failure)
        } else {
            when (val parse = GpxParser.parseAll(bytes)) {
                is GpxParseResult.Failure -> GpxImportResult.Failure(parse.reason)
                is GpxParseResult.Success -> {
                    val tracks = parse.value.filter { it.route.size >= 2 }
                    if (tracks.isEmpty()) {
                        GpxImportResult.Failure(GpxImportFailure.TOO_SHORT)
                    } else {
                        saveTracks(tracks, fileName)
                    }
                }
            }
        }
        return result
    }

    private fun saveTracks(tracks: List<app.openhikes.core.model.GpxTrack>, fileName: String?): GpxImportResult {
        val baseIndex = hikesState.value.size
        val imported = tracks.mapIndexed { index, track ->
            val id = HikeId(UUID.randomUUID().toString())
            val title = HikeTitle.imported(track.name, fileName).ifBlank { "Imported Hike" }
            val stats = HikeRouteStatistics.from(track.distanceMeters, track.route)
            val hike = Hike(
                id = id,
                title = title,
                distanceMeters = track.distanceMeters,
                durationSeconds = stats.durationSeconds,
                dateEpochMillis = track.startTimeEpochMillis ?: 0L,
                tintHex = RouteTint.forIndex(baseIndex + index),
                routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
                linePattern = RouteLinePattern.Default,
                routeBorderHex = RouteBorder.NONE_HEX,
                trackDescription = track.trackDescription,
                author = track.author,
                keywords = track.keywords,
                route = track.route,
                places = track.places.mapIndexed { idx, wpt ->
                    TrailPoint(
                        id = "${id.value}-wpt-$idx",
                        hikeId = id,
                        coordinate = wpt.coordinate,
                        elevationMeters = wpt.elevationMeters,
                        name = wpt.name,
                        symbol = wpt.symbol,
                        note = wpt.note
                    )
                }
            )
            localStateMap.update { current ->
                current + (
                    id to HikeLocalState(
                        hikeId = id,
                        climbMeters = stats.elevationGainMeters,
                        descentMeters = stats.elevationLossMeters
                    )
                    )
            }
            hike
        }
        hikesState.update { current -> imported + current }
        return GpxImportResult.Success(
            imported.map { hike ->
                val state = localStateMap.value[hike.id]
                hike.toSummary(state?.climbMeters, state?.descentMeters)
            }
        )
    }

    override suspend fun importGpxUri(uriString: String): GpxImportResult {
        val (fileName, bytes) = uriPayloads[uriString]
            ?: return GpxImportResult.Failure(GpxImportFailure.UNREADABLE)
        return importGpxBytes(bytes, fileName)
    }

    override suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx> {
        val failure = nextExportFailure?.also { nextExportFailure = null }
        val hike = if (failure == null) hikesState.value.firstOrNull { it.id == hikeId } else null
        return when {
            failure != null -> Result.failure(failure)
            hike != null -> Result.success(GpxExporter.export(hike))
            else -> Result.failure(NoSuchElementException("Hike not found"))
        }
    }

    override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit> {
        val exported = exportHike(hikeId).getOrElse { return Result.failure(it) }
        lastExportedUri = uriString to exported.xml
        return Result.success(Unit)
    }

    override suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit> {
        nextRenameFailure?.let {
            nextRenameFailure = null
            return Result.failure(it)
        }
        var found = false
        hikesState.update { list ->
            list.map { hike ->
                if (hike.id == hikeId) {
                    found = true
                    when (val edit = HikeTitleEdit.of(draftTitle, hike.displayTitle)) {
                        HikeTitleEdit.Unchanged -> hike
                        is HikeTitleEdit.Renamed -> hike.copy(customName = edit.customName)
                    }
                } else {
                    hike
                }
            }
        }
        return if (found) Result.success(Unit) else Result.failure(NoSuchElementException("Hike not found"))
    }

    override suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit> {
        nextStyleFailure?.let {
            nextStyleFailure = null
            return Result.failure(it)
        }
        var found = false
        hikesState.update { list ->
            list.map { hike ->
                if (hike.id == hikeId) {
                    found = true
                    hike.copy(
                        tintHex = style.tintHex,
                        routeBorderHex = style.routeBorderHex,
                        routeWidthDp = style.clampedWidthDp,
                        linePattern = style.linePattern
                    )
                } else {
                    hike
                }
            }
        }
        return if (found) Result.success(Unit) else Result.failure(NoSuchElementException("Hike not found"))
    }

    override suspend fun deleteHike(hikeId: HikeId): Result<Unit> {
        nextDeleteFailure?.let {
            nextDeleteFailure = null
            return Result.failure(it)
        }
        val before = hikesState.value.size
        hikesState.update { list -> list.filterNot { it.id == hikeId } }
        localStateMap.update { map -> map - hikeId }
        return if (hikesState.value.size < before) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("Hike not found"))
        }
    }

    override suspend fun seedFixtureIfConfigured(): HikeId? {
        val (fileName, bytes) = seededFixtureBytes ?: return null
        val result = importGpxBytes(bytes, fileName)
        return (result as? GpxImportResult.Success)?.importedHikes?.firstOrNull()?.id
    }
}
