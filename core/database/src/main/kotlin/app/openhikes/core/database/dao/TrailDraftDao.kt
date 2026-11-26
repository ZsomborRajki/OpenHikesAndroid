package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.TrailDraftEntity
import app.openhikes.core.database.entity.TrailStopRecentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailDraftDao {
    @Query("SELECT * FROM trail_draft WHERE id = 0 LIMIT 1")
    suspend fun getDraft(): TrailDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrailDraftEntity)

    @Query("DELETE FROM trail_draft")
    suspend fun clear()
}

@Dao
interface TrailStopRecentDao {
    @Query("SELECT * FROM trail_stop_recents ORDER BY used_at_epoch_millis DESC")
    fun observeAll(): Flow<List<TrailStopRecentEntity>>

    @Query("SELECT * FROM trail_stop_recents ORDER BY used_at_epoch_millis DESC")
    suspend fun getAll(): List<TrailStopRecentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrailStopRecentEntity)

    @Query("DELETE FROM trail_stop_recents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trail_stop_recents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
