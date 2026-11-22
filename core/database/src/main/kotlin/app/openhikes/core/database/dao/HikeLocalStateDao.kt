package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.HikeLocalStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HikeLocalStateDao {
    @Query("SELECT * FROM hike_local_state")
    fun observeAll(): Flow<List<HikeLocalStateEntity>>

    @Query("SELECT * FROM hike_local_state WHERE hike_id = :hikeId LIMIT 1")
    fun observeByHikeId(hikeId: String): Flow<HikeLocalStateEntity?>

    @Query("SELECT * FROM hike_local_state WHERE hike_id = :hikeId LIMIT 1")
    suspend fun getByHikeId(hikeId: String): HikeLocalStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HikeLocalStateEntity)

    @Query("DELETE FROM hike_local_state WHERE hike_id = :hikeId")
    suspend fun deleteByHikeId(hikeId: String): Int
}
