package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.TrailPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailPointDao {
    @Query("SELECT * FROM trail_points WHERE hike_id = :hikeId ORDER BY sort_order ASC")
    fun observeByHikeId(hikeId: String): Flow<List<TrailPointEntity>>

    @Query("SELECT * FROM trail_points WHERE hike_id = :hikeId ORDER BY sort_order ASC")
    suspend fun getByHikeId(hikeId: String): List<TrailPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrailPointEntity>)
}
