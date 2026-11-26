package app.openhikes.core.data

import android.database.SQLException
import androidx.room.withTransaction
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteElevationRevision
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Manages live recording drafts (`isRecording = true`), positive local draft ownership, atomic
 * finalization into a saved [Hike] + [HikeWalkEntity], and safe orphan cleanup.
 */
@Suppress("LongParameterList")
internal class RecordingDraftStore(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val hikePhotoDao: HikePhotoDao,
    private val hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val localStateRepairer: HikeLocalStateRepairer,
    private val photoStore: HikePhotoStore,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    suspend fun createOrClaimRecordingDraft(hikeId: HikeId, startedAtEpochMillis: Long, title: String): Result<Hike> =
        withContext(ioDispatcher) {
            runStorageCatching {
                val existing = hikeDao.getHikeById(hikeId.value)
                if (existing != null) {
                    if (existing.isRecording) {
                        claimLocalDraft(hikeId.value)
                    }
                    val chunks = hikeDao.getRouteChunks(hikeId.value)
                    val trailPoints = trailPointDao.getByHikeId(hikeId.value)
                    val photos = hikePhotoDao.getByHikeId(hikeId.value)
                    return@runStorageCatching Result.success(existing.toDomain(chunks, trailPoints, photos))
                }

                val tintIndex = hikeDao.getHikeCount()
                val boundedTitle = HikeTitle.bounded(title).orEmpty().ifBlank { DEFAULT_DRAFT_TITLE }
                val entity = HikeEntity(
                    id = hikeId.value,
                    title = boundedTitle,
                    customName = null,
                    distanceMeters = 0.0,
                    durationSeconds = null,
                    dateEpochMillis = startedAtEpochMillis,
                    tintHex = RouteTint.forIndex(tintIndex),
                    routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
                    routeLinePatternId = RouteLinePattern.Default.id,
                    routeBorderHex = RouteBorder.NONE_HEX,
                    isRecording = true,
                    autoFollowEnabled = true,
                    trackDescription = null,
                    author = null,
                    keywords = null,
                    routePointCount = 0
                )
                database.withTransaction {
                    hikeDao.insertHike(entity)
                }
                claimLocalDraft(hikeId.value)
                Result.success(entity.toDomain(emptyList(), emptyList()))
            }
        }

    suspend fun finalizeRecordingDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        prepared: PreparedRecording
    ): Result<Hike> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated save failure"))
        }

        runStorageCatching {
            val existing = hikeDao.getHikeById(hikeId.value)
            if (existing != null && !existing.isRecording) {
                val points = trailPointDao.getByHikeId(hikeId.value)
                val photos = hikePhotoDao.getByHikeId(hikeId.value)
                return@runStorageCatching Result.success(existing.toDomain(emptyList(), points, photos))
            }

            val finalizedEntity =
                buildFinalizedEntity(hikeId, title, customName, startedAtEpochMillis, prepared, existing)
            val chunks = withContext(defaultDispatcher) {
                RouteBlobCodec.encodeChunked(prepared.route).mapIndexed { index, blob ->
                    HikeRouteChunkEntity(hikeId = hikeId.value, chunkIndex = index, chunkBlob = blob)
                }
            }
            val routeRevision = TrailWalkRecord.routeRevision(prepared.route)
            val coveredIntervals = if (prepared.distanceMeters > 0.0) {
                Json.encodeToString(listOf(0.0, prepared.distanceMeters))
            } else {
                "[]"
            }
            val walk = HikeWalkEntity(
                id = UUID.randomUUID().toString(),
                hikeId = hikeId.value,
                startedAtEpochMillis = startedAtEpochMillis,
                endedAtEpochMillis = maxOf(startedAtEpochMillis, endedAtEpochMillis),
                distanceMeters = prepared.distanceMeters,
                durationSeconds = prepared.recordedSeconds,
                coveredIntervalsJson = coveredIntervals,
                furthestDistanceMeters = prepared.distanceMeters,
                routeDistanceMeters = prepared.distanceMeters,
                endReasonId = TrailWalkEndReason.RECORDED.storedId,
                routeRevision = routeRevision
            )

            database.withTransaction {
                hikeDao.replaceHikeWithRoute(finalizedEntity, chunks)
                hikeWalkDao.insert(walk)
            }
            upsertFinalizedLocalState(hikeId.value, prepared)
            val points = trailPointDao.getByHikeId(hikeId.value)
            val photos = hikePhotoDao.getByHikeId(hikeId.value)
            Result.success(finalizedEntity.toDomain(chunks, points, photos))
        }
    }

    private suspend fun buildFinalizedEntity(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        prepared: PreparedRecording,
        existing: HikeEntity?
    ): HikeEntity {
        val boundedTitle = HikeTitle.bounded(title).orEmpty().ifBlank { DEFAULT_DRAFT_TITLE }
        val boundedCustomName = HikeTitle.bounded(customName)
        val tintHex = existing?.tintHex ?: RouteTint.forIndex(hikeDao.getHikeCount())
        return HikeEntity(
            id = hikeId.value,
            title = boundedTitle,
            customName = boundedCustomName,
            distanceMeters = prepared.distanceMeters,
            durationSeconds = prepared.recordedSeconds,
            dateEpochMillis = startedAtEpochMillis,
            tintHex = tintHex,
            routeWidthDp = existing?.routeWidthDp ?: HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            routeLinePatternId = existing?.routeLinePatternId ?: RouteLinePattern.Default.id,
            routeBorderHex = existing?.routeBorderHex ?: RouteBorder.NONE_HEX,
            isRecording = false,
            autoFollowEnabled = existing?.autoFollowEnabled ?: true,
            trackDescription = existing?.trackDescription,
            author = existing?.author,
            keywords = existing?.keywords,
            routePointCount = prepared.route.size
        )
    }

    private suspend fun upsertFinalizedLocalState(hikeId: String, prepared: PreparedRecording) {
        val currentLocal = hikeLocalStateDao.getByHikeId(hikeId)
        localStateRepairer.upsertBestEffort(
            HikeLocalStateEntity(
                hikeId = hikeId,
                listOrder = currentLocal?.listOrder,
                climbMeters = prepared.elevationGainMeters,
                descentMeters = prepared.elevationLossMeters,
                elevationRouteRevision = RouteElevationRevision.of(prepared.route),
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = currentLocal?.autoSavedTileKeysJson ?: "[]",
                walkInProgressJson = currentLocal?.walkInProgressJson
            )
        )
    }

    suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit> = withContext(ioDispatcher) {
        runStorageCatching {
            val existing = hikeDao.getHikeById(hikeId.value)
            if (existing == null) {
                localStateRepairer.deleteBestEffort(hikeId.value)
                return@runStorageCatching Result.success(Unit)
            }
            if (!existing.isRecording) {
                return@runStorageCatching Result.success(Unit)
            }
            val photos = hikePhotoDao.getByHikeId(hikeId.value).map { it.toDomain() }
            hikeDao.deleteHikeById(hikeId.value)
            localStateRepairer.deleteBestEffort(hikeId.value)
            photoStore.deleteFiles(photos)
            Result.success(Unit)
        }
    }

    suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?): Result<Int> = withContext(ioDispatcher) {
        runStorageCatching {
            val ownedDraftIds = hikeLocalStateDao.getOwnedRecordingDraftIds().toSet()
            val recordingIds = hikeDao.getRecordingHikeIds()
            var deletedCount = 0
            for (draftId in recordingIds) {
                if (draftId != exceptHikeId?.value && ownedDraftIds.contains(draftId)) {
                    val photos = hikePhotoDao.getByHikeId(draftId).map { it.toDomain() }
                    if (hikeDao.deleteHikeById(draftId) > 0) {
                        deletedCount += 1
                        photoStore.deleteFiles(photos)
                    }
                    localStateRepairer.deleteBestEffort(draftId)
                }
            }
            Result.success(deletedCount)
        }
    }

    private suspend fun <T> runStorageCatching(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SQLException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }

    private suspend fun claimLocalDraft(hikeId: String) {
        val existing = hikeLocalStateDao.getByHikeId(hikeId)
        val updated = existing?.copy(ownsRecordingDraft = true) ?: HikeLocalStateEntity(
            hikeId = hikeId,
            listOrder = null,
            climbMeters = null,
            descentMeters = null,
            elevationRouteRevision = null,
            ownsRecordingDraft = true,
            autoSavedTileKeysJson = "[]"
        )
        hikeLocalStateDao.upsert(updated)
    }

    private companion object {
        const val DEFAULT_DRAFT_TITLE = "Hike"
    }
}
