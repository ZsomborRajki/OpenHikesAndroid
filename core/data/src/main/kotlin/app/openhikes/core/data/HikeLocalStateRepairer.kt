package app.openhikes.core.data

import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.model.HikeRouteStatistics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repairs missing derived [HikeLocalStateEntity] rows from authoritative portable hike records
 * whenever an initial local-state write failed or device-local storage was cleared.
 */
internal class HikeLocalStateRepairer(
    private val hikeDao: HikeDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    private val repairMutex = Mutex()

    suspend fun upsertBestEffort(entity: HikeLocalStateEntity): Boolean = try {
        hikeLocalStateDao.upsert(entity)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    suspend fun deleteBestEffort(hikeId: String) {
        try {
            hikeLocalStateDao.deleteByHikeId(hikeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Portable deletion already succeeded; orphaned local cache is non-authoritative.
        }
    }

    suspend fun repairMissingForHikes(
        entities: List<HikeEntity>,
        existingByHikeId: Map<String, HikeLocalStateEntity>
    ): Map<String, HikeLocalStateEntity> {
        val missing = entities.filter { !it.isRecording && it.id !in existingByHikeId }
        if (missing.isEmpty()) return existingByHikeId

        return repairMutex.withLock {
            val merged = LinkedHashMap(existingByHikeId)
            for (entity in missing) {
                val existing = getLocalStateBestEffort(entity.id)
                if (existing != null) {
                    merged[entity.id] = existing
                } else {
                    val repaired = computeDerivedState(entity)
                    upsertBestEffort(repaired)
                    merged[entity.id] = repaired
                }
            }
            merged
        }
    }

    suspend fun repairIfMissing(hikeId: String): HikeLocalStateEntity? = repairMutex.withLock {
        getLocalStateBestEffort(hikeId)?.let { return@withLock it }
        val hikeWithRoute = withContext(ioDispatcher) { hikeDao.observeHikeWithRoute(hikeId).first() }
            ?: return@withLock null
        if (hikeWithRoute.hike.isRecording) return@withLock null
        val repaired = buildDerivedState(hikeWithRoute.hike, hikeWithRoute.routeChunks)
        upsertBestEffort(repaired)
        repaired
    }

    private suspend fun getLocalStateBestEffort(hikeId: String): HikeLocalStateEntity? = try {
        withContext(ioDispatcher) { hikeLocalStateDao.getByHikeId(hikeId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun computeDerivedState(entity: HikeEntity): HikeLocalStateEntity {
        val chunks = withContext(ioDispatcher) {
            hikeDao.observeHikeWithRoute(entity.id).first()?.routeChunks.orEmpty()
        }
        return buildDerivedState(entity, chunks)
    }

    private suspend fun buildDerivedState(
        entity: HikeEntity,
        chunks: List<HikeRouteChunkEntity>
    ): HikeLocalStateEntity {
        val stats = withContext(defaultDispatcher) {
            val route = RouteBlobCodec.decodeChunksOrEmpty(
                chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob }
            )
            HikeRouteStatistics.from(entity.distanceMeters, route)
        }
        return HikeLocalStateEntity(
            hikeId = entity.id,
            listOrder = null,
            climbMeters = stats.elevationGainMeters,
            descentMeters = stats.elevationLossMeters,
            elevationRouteRevision = null,
            ownsRecordingDraft = false,
            autoSavedTileKeysJson = "[]"
        )
    }
}
