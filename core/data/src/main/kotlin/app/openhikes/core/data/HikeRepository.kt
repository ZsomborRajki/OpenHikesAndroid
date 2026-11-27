package app.openhikes.core.data

import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.ExportedHikeArchive
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.HikeTrailBreakdowns
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.LibraryLine
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * Immutable cached photo copy prepared for Android's share sheet, paired with its user-facing
 * display name for `FileProvider.getUriForFile(context, authority, file, displayName)`.
 */
data class PreparedSharePhoto(val file: File, val displayName: String)

/**
 * Main-safe repository owning the local hike library, GPX import/export, and route styling.
 */
@Suppress("TooManyFunctions")
interface HikeRepository {
    val hikes: Flow<List<HikeSummary>>

    val mapRoutes: Flow<List<HikeMapRoute>>

    val libraryLines: Flow<List<LibraryLine>>

    /**
     * Emits a fresh revision token whenever portable hike rows, route chunks, or walk records
     * mutate so [LibraryTotals] screens re-sum even when distance, climb, and walk count are unchanged.
     */
    val totalsInvalidation: Flow<Long>

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

    /**
     * Persists measured OSM surface and SAC difficulty breakdowns on [hikeId] only when its
     * current route revision still matches [expectedRouteRevision], dropping stale answers if
     * the route was edited while analysis ran.
     */
    suspend fun saveTrailBreakdowns(
        hikeId: HikeId,
        expectedRouteRevision: String,
        breakdowns: HikeTrailBreakdowns
    ): Result<Boolean>

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

    suspend fun addPlace(hikeId: HikeId, place: TrailPoint): Result<Boolean>

    suspend fun editPlace(
        hikeId: HikeId,
        placeId: String,
        name: String,
        symbol: TrailPlaceSymbol?,
        note: String
    ): Result<Boolean>

    suspend fun removePlace(hikeId: HikeId, placeId: String): Result<Unit>

    suspend fun addCameraPhoto(
        hikeId: HikeId,
        rawBytes: ByteArray,
        capturedAtEpochMillis: Long? = null,
        coordinate: GeoCoordinate? = null,
        placeId: String? = null,
        saveToSystemLibrary: Boolean = false
    ): Result<HikePhoto>

    suspend fun addPickedPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        assetLocalIdentifier: String? = null,
        fallbackCoordinate: GeoCoordinate? = null,
        placeId: String? = null
    ): Result<HikePhoto>

    suspend fun addPickedPhotoUri(
        hikeId: HikeId,
        uriString: String,
        fallbackCoordinate: GeoCoordinate? = null,
        placeId: String? = null
    ): Result<HikePhoto>

    suspend fun addDiscoveredPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        match: LibraryPhotoMatch,
        expectedPlanFingerprint: Long
    ): Result<HikePhoto?>

    suspend fun removePhoto(hikeId: HikeId, photoId: String): Result<Unit>

    suspend fun readPhotoBytes(photo: HikePhoto): ByteArray?

    suspend fun readPhotoThumbnailBytes(photo: HikePhoto): ByteArray?

    suspend fun hasPhotoFile(photo: HikePhoto): Boolean

    suspend fun prepareSharePhotoFile(
        hikeTitle: String,
        positionOneBased: Int,
        photo: HikePhoto
    ): Result<PreparedSharePhoto>

    suspend fun exportHikeArchive(hikeId: HikeId): Result<ExportedHikeArchive>

    suspend fun exportHikeArchiveToUri(hikeId: HikeId, uriString: String): Result<Unit>

    suspend fun measurePhotoStorageBytes(): Long

    suspend fun reclaimOrphanedPhotos(): Result<Int>

    suspend fun saveCustomHikeOrder(orderedIds: List<HikeId>): Result<Unit>

    suspend fun clearCustomHikeOrder(): Result<Unit>

    suspend fun validateElevationMetrics(hikeIds: List<HikeId>): Result<Boolean>

    suspend fun computeLibraryTotals(
        asOfEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result<LibraryTotals>

    suspend fun seedFixtureIfConfigured(): HikeId?
}
