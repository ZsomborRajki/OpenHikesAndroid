package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.database.dao.TrailDraftDao
import app.openhikes.core.database.dao.TrailStopRecentDao
import app.openhikes.core.database.entity.TrailDraftEntity
import app.openhikes.core.database.entity.TrailStopRecentEntity
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailStopRecent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Device-local repository storing the in-progress Trail Maker draft and recent stop search picks.
 */
interface TrailDraftRepository {
    val recentStops: Flow<List<TrailStopRecent>>

    suspend fun loadDraft(): StoredTrailDraft

    suspend fun saveDraft(draft: StoredTrailDraft)

    suspend fun clearDraft()

    suspend fun recordRecentStop(recent: TrailStopRecent)

    suspend fun removeRecentStop(id: String)
}

@Singleton
internal class OfflineFirstTrailDraftRepository @Inject constructor(
    private val trailDraftDao: TrailDraftDao,
    private val trailStopRecentDao: TrailStopRecentDao,
    private val clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TrailDraftRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val recentStops: Flow<List<TrailStopRecent>> = trailStopRecentDao.observeAll().map { entities ->
        entities.map { entity ->
            TrailStopRecent(
                id = entity.id,
                name = entity.name,
                subtitle = entity.subtitle,
                latitude = entity.latitude,
                longitude = entity.longitude
            )
        }
    }

    override suspend fun loadDraft(): StoredTrailDraft = withContext(ioDispatcher) {
        val entity = runCatching { trailDraftDao.getDraft() }.getOrNull() ?: return@withContext StoredTrailDraft.Empty
        runCatching { json.decodeFromString(StoredTrailDraft.serializer(), entity.draftJson) }
            .getOrDefault(StoredTrailDraft.Empty)
    }

    override suspend fun saveDraft(draft: StoredTrailDraft) {
        withContext(ioDispatcher) {
            if (draft.isEmpty) {
                runCatching { trailDraftDao.clear() }
                return@withContext
            }
            val encoded = json.encodeToString(StoredTrailDraft.serializer(), draft)
            runCatching {
                trailDraftDao.upsert(
                    TrailDraftEntity(
                        id = TrailDraftEntity.SINGLETON_ID,
                        draftJson = encoded,
                        updatedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
            }
        }
    }

    override suspend fun clearDraft() {
        withContext(ioDispatcher) {
            runCatching { trailDraftDao.clear() }
        }
    }

    override suspend fun recordRecentStop(recent: TrailStopRecent) {
        if (recent.name.isBlank()) return
        withContext(ioDispatcher) {
            runCatching {
                val existing = trailStopRecentDao.getAll()
                val duplicateIds = existing
                    .filter { entry ->
                        TrailStopRecent(
                            id = entry.id,
                            name = entry.name,
                            subtitle = entry.subtitle,
                            latitude = entry.latitude,
                            longitude = entry.longitude
                        ).isSamePlaceAs(recent)
                    }
                    .map { it.id }
                if (duplicateIds.isNotEmpty()) {
                    trailStopRecentDao.deleteByIds(duplicateIds)
                }
                trailStopRecentDao.upsert(
                    TrailStopRecentEntity(
                        id = recent.id,
                        name = recent.name,
                        subtitle = recent.subtitle,
                        latitude = recent.latitude,
                        longitude = recent.longitude,
                        usedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
                val allAfter = trailStopRecentDao.getAll()
                if (allAfter.size > TrailStopRecent.LIMIT) {
                    trailStopRecentDao.deleteByIds(allAfter.drop(TrailStopRecent.LIMIT).map { it.id })
                }
            }
        }
    }

    override suspend fun removeRecentStop(id: String) {
        withContext(ioDispatcher) {
            runCatching { trailStopRecentDao.deleteById(id) }
        }
    }
}
