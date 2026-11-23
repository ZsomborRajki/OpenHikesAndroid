package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.HikeWalkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HikeWalkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(walk: HikeWalkEntity)

    @Query("SELECT * FROM hike_walks WHERE hike_id = :hikeId ORDER BY started_at_epoch_millis DESC")
    fun observeByHikeId(hikeId: String): Flow<List<HikeWalkEntity>>

    @Query("SELECT * FROM hike_walks WHERE hike_id = :hikeId ORDER BY started_at_epoch_millis DESC")
    suspend fun getByHikeId(hikeId: String): List<HikeWalkEntity>

    @Query("DELETE FROM hike_walks WHERE hike_id = :hikeId")
    suspend fun deleteByHikeId(hikeId: String): Int
}
