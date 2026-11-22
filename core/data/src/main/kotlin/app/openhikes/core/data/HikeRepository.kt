package app.openhikes.core.data

import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteStyleUpdate
import kotlinx.coroutines.flow.Flow

/**
 * Main-safe repository owning the local hike library, GPX import/export, and route styling.
 */
interface HikeRepository {
    val hikes: Flow<List<HikeSummary>>

    val mapRoutes: Flow<List<HikeMapRoute>>

    fun observeHike(hikeId: HikeId): Flow<Hike?>

    fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?>

    suspend fun importGpxBytes(bytes: ByteArray, fileName: String? = null): GpxImportResult

    suspend fun importGpxUri(uriString: String): GpxImportResult

    suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx>

    suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit>

    suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit>

    suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit>

    suspend fun deleteHike(hikeId: HikeId): Result<Unit>

    suspend fun seedFixtureIfConfigured(): HikeId?
}
