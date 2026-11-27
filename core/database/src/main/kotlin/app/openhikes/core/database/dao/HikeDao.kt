package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeOriginalRouteChunkEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
interface HikeDao {
    @Query("SELECT * FROM hikes WHERE is_recording = 0 ORDER BY date_epoch_millis DESC, id ASC")
    fun observeAllHikes(): Flow<List<HikeEntity>>

    @Query("SELECT * FROM hikes WHERE is_recording = 0 ORDER BY date_epoch_millis DESC, id ASC")
    suspend fun getAllFinishedHikes(): List<HikeEntity>

    @Query("SELECT id FROM hikes WHERE is_recording = 1")
    suspend fun getRecordingHikeIds(): List<String>

    @Transaction
    @Query("SELECT * FROM hikes WHERE id = :hikeId LIMIT 1")
    fun observeHikeWithRoute(hikeId: String): Flow<HikeWithRouteRow?>

    @Query("SELECT * FROM hikes WHERE id = :hikeId LIMIT 1")
    suspend fun getHikeById(hikeId: String): HikeEntity?

    @Query("SELECT COUNT(*) FROM hikes")
    suspend fun getHikeCount(): Int

    @Query("SELECT COUNT(*) FROM hike_route_chunks")
    fun observeRouteChunkCount(): Flow<Int>

    @Query(
        """
        SELECT h.id AS hike_id,
               c.chunk_index AS chunk_index,
               c.chunk_blob AS chunk_blob,
               h.tint_hex AS tint_hex,
               h.route_border_hex AS route_border_hex,
               h.route_width_dp AS route_width_dp,
               h.route_line_pattern_id AS route_line_pattern_id
        FROM hikes h
        INNER JOIN hike_route_chunks c ON h.id = c.hike_id
        WHERE h.is_recording = 0
        ORDER BY h.date_epoch_millis DESC, h.id ASC, c.chunk_index ASC
        """
    )
    fun observeAllMapRouteChunks(): Flow<List<HikeMapRouteChunkRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHike(hike: HikeEntity)

    @Update
    suspend fun updateHike(hike: HikeEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteChunks(chunks: List<HikeRouteChunkEntity>)

    @Transaction
    suspend fun insertHikeWithRoute(hike: HikeEntity, routeChunks: List<HikeRouteChunkEntity>) {
        insertHike(hike)
        if (routeChunks.isNotEmpty()) {
            insertRouteChunks(routeChunks)
        }
    }

    @Query("SELECT * FROM hike_route_chunks WHERE hike_id = :hikeId ORDER BY chunk_index ASC")
    suspend fun getRouteChunks(hikeId: String): List<HikeRouteChunkEntity>

    @Query("DELETE FROM hike_route_chunks WHERE hike_id = :hikeId")
    suspend fun deleteRouteChunks(hikeId: String): Int

    /**
     * Updates an existing hike row in place (falling back to insert when absent) and replaces
     * only its active `hike_route_chunks`. Using [updateHike] instead of `INSERT OR REPLACE`
     * avoids firing `ON DELETE CASCADE` on `hike_original_route_chunks`, `hike_walks`, and
     * `trail_points`.
     */
    @Transaction
    suspend fun replaceHikeWithRoute(hike: HikeEntity, routeChunks: List<HikeRouteChunkEntity>) {
        if (updateHike(hike) == 0) {
            insertHike(hike)
        }
        deleteRouteChunks(hike.id)
        if (routeChunks.isNotEmpty()) {
            insertRouteChunks(routeChunks)
        }
    }

    /**
     * Updates an already-existing hike row in place and replaces only its active `hike_route_chunks`.
     * Returns `false` without inserting anything if the hike was deleted concurrently.
     */
    @Transaction
    suspend fun updateExistingHikeWithRoute(hike: HikeEntity, routeChunks: List<HikeRouteChunkEntity>): Boolean {
        if (updateHike(hike) == 0) return false
        deleteRouteChunks(hike.id)
        if (routeChunks.isNotEmpty()) {
            insertRouteChunks(routeChunks)
        }
        return true
    }

    @Query("UPDATE hikes SET custom_name = :customName WHERE id = :hikeId")
    suspend fun updateCustomName(hikeId: String, customName: String?): Int

    @Query("UPDATE hikes SET auto_follow_enabled = :enabled WHERE id = :hikeId")
    suspend fun updateAutoFollowEnabled(hikeId: String, enabled: Boolean): Int

    @Query(
        """
        UPDATE hikes
        SET tint_hex = :tintHex,
            route_border_hex = :routeBorderHex,
            route_width_dp = :routeWidthDp,
            route_line_pattern_id = :routeLinePatternId
        WHERE id = :hikeId
        """
    )
    suspend fun updateRouteStyle(
        hikeId: String,
        tintHex: String,
        routeBorderHex: String,
        routeWidthDp: Double,
        routeLinePatternId: String
    ): Int

    @Query("SELECT * FROM hike_original_route_chunks WHERE hike_id = :hikeId ORDER BY chunk_index ASC")
    suspend fun getOriginalRouteChunks(hikeId: String): List<HikeOriginalRouteChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOriginalRouteChunks(chunks: List<HikeOriginalRouteChunkEntity>)

    @Query("DELETE FROM hike_original_route_chunks WHERE hike_id = :hikeId")
    suspend fun deleteOriginalRouteChunks(hikeId: String): Int

    @Transaction
    suspend fun replaceOriginalRouteChunks(hikeId: String, chunks: List<HikeOriginalRouteChunkEntity>) {
        deleteOriginalRouteChunks(hikeId)
        if (chunks.isNotEmpty()) {
            insertOriginalRouteChunks(chunks)
        }
    }

    @Query("DELETE FROM hikes WHERE id = :hikeId")
    suspend fun deleteHikeById(hikeId: String): Int
}
