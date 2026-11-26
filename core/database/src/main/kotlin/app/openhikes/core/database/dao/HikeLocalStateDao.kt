package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.openhikes.core.database.entity.HikeLocalStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HikeLocalStateDao {
    @Query("SELECT * FROM hike_local_state")
    fun observeAll(): Flow<List<HikeLocalStateEntity>>

    @Query("SELECT * FROM hike_local_state")
    suspend fun getAll(): List<HikeLocalStateEntity>

    @Query("SELECT * FROM hike_local_state WHERE hike_id = :hikeId LIMIT 1")
    fun observeByHikeId(hikeId: String): Flow<HikeLocalStateEntity?>

    @Query("SELECT * FROM hike_local_state WHERE hike_id = :hikeId LIMIT 1")
    suspend fun getByHikeId(hikeId: String): HikeLocalStateEntity?

    @Query("SELECT hike_id FROM hike_local_state WHERE owns_recording_draft = 1")
    suspend fun getOwnedRecordingDraftIds(): List<String>

    @Query("SELECT * FROM hike_local_state WHERE walk_in_progress_json IS NOT NULL")
    suspend fun getWithWalkInProgress(): List<HikeLocalStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HikeLocalStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<HikeLocalStateEntity>)

    @Query("UPDATE hike_local_state SET list_order = NULL WHERE list_order IS NOT NULL")
    suspend fun clearAllListOrders(): Int

    @Transaction
    suspend fun replaceListOrders(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val existingById = getAll().associateBy { it.hikeId }
        val updates = orderedIds.mapIndexed { index, hikeId ->
            existingById[hikeId]?.copy(listOrder = index) ?: HikeLocalStateEntity(
                hikeId = hikeId,
                listOrder = index,
                climbMeters = null,
                descentMeters = null,
                elevationRouteRevision = null,
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = "[]"
            )
        }
        upsertAll(updates)
    }

    @Query("DELETE FROM hike_local_state WHERE hike_id = :hikeId")
    suspend fun deleteByHikeId(hikeId: String): Int
}
