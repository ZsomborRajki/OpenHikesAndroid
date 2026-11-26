package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.TrailPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailPointDao {
    @Query("SELECT * FROM trail_points WHERE hike_id = :hikeId ORDER BY sort_order ASC, created_at_epoch_millis ASC")
    fun observeByHikeId(hikeId: String): Flow<List<TrailPointEntity>>

    @Query("SELECT * FROM trail_points WHERE hike_id = :hikeId ORDER BY sort_order ASC, created_at_epoch_millis ASC")
    suspend fun getByHikeId(hikeId: String): List<TrailPointEntity>

    @Query("SELECT * FROM trail_points WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TrailPointEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM trail_points WHERE hike_id = :hikeId")
    suspend fun maxSortOrderForHike(hikeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: TrailPointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrailPointEntity>)

    @Query("UPDATE trail_points SET name = :name, symbol = :symbol, note = :note WHERE id = :id")
    suspend fun updateDetails(id: String, name: String, symbol: String?, note: String): Int

    @Query("DELETE FROM trail_points WHERE id = :id AND hike_id = :hikeId")
    suspend fun deleteById(hikeId: String, id: String): Int
}
