package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.openhikes.core.database.entity.HikeLocalStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: HikeLocalStateEntity): Long

    @Query(
        """
        UPDATE hike_local_state
        SET climb_meters = :climbMeters,
            descent_meters = :descentMeters,
            elevation_route_revision = :elevationRouteRevision
        WHERE hike_id = :hikeId
        """
    )
    suspend fun updateElevationMetrics(
        hikeId: String,
        climbMeters: Double?,
        descentMeters: Double?,
        elevationRouteRevision: String?
    ): Int

    @Query("UPDATE hike_local_state SET list_order = :listOrder WHERE hike_id = :hikeId")
    suspend fun updateListOrder(hikeId: String, listOrder: Int?): Int

    @Query("UPDATE hike_local_state SET list_order = NULL WHERE list_order IS NOT NULL")
    suspend fun clearAllListOrders(): Int

    /**
     * Updates only the elevation cache columns for [hikeId] without overwriting concurrent
     * `walk_in_progress_json`, `list_order`, `owns_recording_draft`, or offline tile claims.
     */
    @Transaction
    suspend fun upsertElevationMetrics(
        hikeId: String,
        climbMeters: Double?,
        descentMeters: Double?,
        elevationRouteRevision: String?
    ) {
        val updated = updateElevationMetrics(hikeId, climbMeters, descentMeters, elevationRouteRevision)
        if (updated == 0) {
            val inserted = insertIgnore(
                HikeLocalStateEntity(
                    hikeId = hikeId,
                    listOrder = null,
                    climbMeters = climbMeters,
                    descentMeters = descentMeters,
                    elevationRouteRevision = elevationRouteRevision,
                    ownsRecordingDraft = false,
                    autoSavedTileKeysJson = "[]"
                )
            )
            if (inserted == -1L) {
                updateElevationMetrics(hikeId, climbMeters, descentMeters, elevationRouteRevision)
            }
        }
    }

    @Transaction
    suspend fun upsertElevationMetricsBatch(updates: List<ElevationMetricsRowUpdate>) {
        for (update in updates) {
            upsertElevationMetrics(
                hikeId = update.hikeId,
                climbMeters = update.climbMeters,
                descentMeters = update.descentMeters,
                elevationRouteRevision = update.elevationRouteRevision
            )
        }
    }

    @Transaction
    suspend fun replaceListOrders(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, hikeId ->
            val updated = updateListOrder(hikeId, index)
            if (updated == 0) {
                val inserted = insertIgnore(
                    HikeLocalStateEntity(
                        hikeId = hikeId,
                        listOrder = index,
                        climbMeters = null,
                        descentMeters = null,
                        elevationRouteRevision = null,
                        ownsRecordingDraft = false,
                        autoSavedTileKeysJson = "[]"
                    )
                )
                if (inserted == -1L) {
                    updateListOrder(hikeId, index)
                }
            }
        }
    }

    @Query("DELETE FROM hike_local_state WHERE hike_id = :hikeId")
    suspend fun deleteByHikeId(hikeId: String): Int
}
