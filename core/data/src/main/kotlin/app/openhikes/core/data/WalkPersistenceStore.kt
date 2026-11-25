package app.openhikes.core.data

import android.database.SQLException
import androidx.room.withTransaction
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns device-local open-walk persistence ([HikeLocalStateEntity.walkInProgressJson]) and
 * portable finished-walk storage ([HikeWalkEntity]), enforcing atomic rollback on write refusal.
 */
@Suppress("LongParameterList")
internal class WalkPersistenceStore(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeWalks(hikeId: HikeId): Flow<List<HikeWalk>> = hikeWalkDao.observeByHikeId(hikeId.value)
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(defaultDispatcher)

    fun observeWalk(walkId: String): Flow<HikeWalk?> = hikeWalkDao.observeById(walkId)
        .map { row -> row?.toDomain() }
        .flowOn(defaultDispatcher)

    suspend fun saveWalkInProgress(hikeId: HikeId, record: TrailWalkRecord?): Result<Unit> = withContext(ioDispatcher) {
        runStorageCatching {
            val encoded = record?.let { json.encodeToString(it) }
            val existing = hikeLocalStateDao.getByHikeId(hikeId.value)
            val updated = existing?.copy(walkInProgressJson = encoded) ?: HikeLocalStateEntity(
                hikeId = hikeId.value,
                listOrder = null,
                climbMeters = null,
                descentMeters = null,
                elevationRouteRevision = null,
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = "[]",
                walkInProgressJson = encoded
            )
            hikeLocalStateDao.upsert(updated)
            Result.success(Unit)
        }
    }

    suspend fun getOpenWalksInProgress(): Result<List<TrailWalkRecord>> = withContext(ioDispatcher) {
        runStorageCatching {
            val rows = hikeLocalStateDao.getWithWalkInProgress()
            val decoded = rows.mapNotNull { row ->
                row.walkInProgressJson?.let { raw ->
                    runCatching { json.decodeFromString<TrailWalkRecord>(raw) }.getOrNull()
                }
            }
            Result.success(decoded)
        }
    }

    suspend fun finishWalk(
        record: TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: TrailWalkEndReason
    ): Result<HikeWalk?> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated walk save failure"))
        }
        runStorageCatching {
            val hikeExists = hikeDao.getHikeById(record.hikeId) != null
            val walkEntity = if (hikeExists && record.coverage.meetsMinimum) {
                buildWalkEntity(record, endedAtEpochMillis, reason)
            } else {
                null
            }
            val existingWalk = walkEntity?.let { hikeWalkDao.getById(it.id) }
            if (walkEntity != null) {
                database.withTransaction {
                    hikeWalkDao.insert(walkEntity)
                }
            }
            val cleared = clearWalkInProgress(record.hikeId)
            if (cleared.isFailure) {
                if (walkEntity != null && existingWalk == null) {
                    runCatching { hikeWalkDao.deleteById(walkEntity.id) }
                }
                Result.failure(cleared.exceptionOrNull() ?: IOException("Could not clear walk in progress"))
            } else {
                Result.success(walkEntity?.toDomain())
            }
        }
    }

    suspend fun seedWalk(walk: HikeWalk): Result<Unit> = withContext(ioDispatcher) {
        runStorageCatching {
            val entity = HikeWalkEntity(
                id = walk.id,
                hikeId = walk.hikeId.value,
                startedAtEpochMillis = walk.startedAtEpochMillis,
                endedAtEpochMillis = walk.endedAtEpochMillis,
                distanceMeters = walk.distanceMeters,
                durationSeconds = walk.durationSeconds,
                coveredIntervalsJson = json.encodeToString(walk.coveredIntervals),
                furthestDistanceMeters = walk.furthestDistanceMeters,
                routeDistanceMeters = walk.routeDistanceMeters,
                endReasonId = walk.endReason.storedId,
                routeRevision = walk.routeRevision
            )
            hikeWalkDao.insert(entity)
            Result.success(Unit)
        }
    }

    private suspend fun clearWalkInProgress(hikeId: String): Result<Unit> = runStorageCatching {
        val existing = hikeLocalStateDao.getByHikeId(hikeId)
        if (existing != null && existing.walkInProgressJson != null) {
            hikeLocalStateDao.upsert(existing.copy(walkInProgressJson = null))
        }
        Result.success(Unit)
    }

    private fun buildWalkEntity(
        record: TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: TrailWalkEndReason
    ): HikeWalkEntity = HikeWalkEntity(
        id = record.durableWalkId,
        hikeId = record.hikeId,
        startedAtEpochMillis = record.startedAtEpochMillis,
        endedAtEpochMillis = maxOf(record.startedAtEpochMillis, endedAtEpochMillis),
        distanceMeters = record.coverage.coveredMeters,
        durationSeconds = record.activeSeconds(endedAtEpochMillis),
        coveredIntervalsJson = json.encodeToString(record.coverage.intervals),
        furthestDistanceMeters = record.coverage.furthestDistanceMeters,
        routeDistanceMeters = record.routeDistanceMeters,
        endReasonId = reason.storedId,
        routeRevision = record.routeRevision
    )

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

    companion object {
        const val SEED_WALK_HALF_LOOP = "HalfLoop"
        const val HALF_LOOP_DURATION_SECONDS = 45.0 * 60.0
        private const val HALF_FACTOR = 2.0
        private const val MILLIS_PER_SECOND = 1000.0

        fun buildHalfLoopSeedWalk(
            hikeId: HikeId,
            routeDistanceMeters: Double,
            routeRevision: String,
            nowEpochMillis: Long
        ): HikeWalk {
            val half = routeDistanceMeters / HALF_FACTOR
            return HikeWalk(
                id = "seed-half-loop-${hikeId.value}",
                hikeId = hikeId,
                startedAtEpochMillis = nowEpochMillis - (HALF_LOOP_DURATION_SECONDS * MILLIS_PER_SECOND).toLong(),
                endedAtEpochMillis = nowEpochMillis,
                distanceMeters = half,
                durationSeconds = HALF_LOOP_DURATION_SECONDS,
                coveredIntervals = listOf(0.0, half),
                furthestDistanceMeters = half,
                routeDistanceMeters = routeDistanceMeters,
                endReason = TrailWalkEndReason.ENDED,
                routeRevision = routeRevision
            )
        }
    }
}
