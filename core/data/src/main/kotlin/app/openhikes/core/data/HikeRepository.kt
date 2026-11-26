package app.openhikes.core.data

import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import kotlinx.coroutines.flow.Flow

/**
 * Main-safe repository owning the local hike library, GPX import/export, and route styling.
 */
@Suppress("TooManyFunctions")
interface HikeRepository {
    val hikes: Flow<List<HikeSummary>>

    val mapRoutes: Flow<List<HikeMapRoute>>

    fun observeHike(hikeId: HikeId): Flow<Hike?>

    fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?>

    fun observeWalks(hikeId: HikeId): Flow<List<HikeWalk>>

    fun observeWalk(walkId: String): Flow<HikeWalk?>

    suspend fun importGpxBytes(bytes: ByteArray, fileName: String? = null): GpxImportResult

    suspend fun importGpxUri(uriString: String): GpxImportResult

    suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx>

    suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit>

    suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit>

    suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit>

    suspend fun loadRouteEditSources(hikeId: HikeId): RouteEditSources?

    suspend fun applyRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>,
        edited: EditedLine
    ): RouteEditOutcome

    suspend fun revertRouteEdit(hikeId: HikeId, expectedCurrentRoute: List<RouteCoordinate>): RouteEditOutcome

    suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit>

    suspend fun saveWalkInProgress(hikeId: HikeId, record: TrailWalkRecord?): Result<Unit>

    suspend fun getOpenWalksInProgress(): Result<List<TrailWalkRecord>>

    suspend fun finishWalk(
        record: TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: TrailWalkEndReason
    ): Result<HikeWalk?>

    suspend fun deleteHike(hikeId: HikeId): Result<Unit>

    suspend fun createOrClaimRecordingDraft(hikeId: HikeId, startedAtEpochMillis: Long, title: String): Result<Hike>

    suspend fun finalizeRecordingDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        prepared: PreparedRecording
    ): Result<Hike>

    suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit>

    suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId? = null): Result<Int>

    suspend fun saveDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Result<Hike>

    suspend fun seedFixtureIfConfigured(): HikeId?
}
