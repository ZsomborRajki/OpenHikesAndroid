package app.openhikes.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.openhikes.core.database.entity.HikePhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HikePhotoDao {
    @Query("SELECT * FROM hike_photos WHERE hike_id = :hikeId ORDER BY captured_at_epoch_millis ASC, id ASC")
    fun observeByHikeId(hikeId: String): Flow<List<HikePhotoEntity>>

    @Query("SELECT * FROM hike_photos WHERE hike_id = :hikeId ORDER BY captured_at_epoch_millis ASC, id ASC")
    suspend fun getByHikeId(hikeId: String): List<HikePhotoEntity>

    @Query("SELECT * FROM hike_photos")
    suspend fun getAll(): List<HikePhotoEntity>

    @Query("SELECT * FROM hike_photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HikePhotoEntity?

    @Query(
        """
        SELECT * FROM hike_photos
        WHERE hike_id = :hikeId AND asset_local_identifier = :assetLocalIdentifier
        LIMIT 1
        """
    )
    suspend fun findByHikeAndAssetId(hikeId: String, assetLocalIdentifier: String): HikePhotoEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(photo: HikePhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(photos: List<HikePhotoEntity>)

    @Query("UPDATE hike_photos SET place_id = :placeId WHERE hike_id = :hikeId AND id = :photoId")
    suspend fun updatePlaceId(hikeId: String, photoId: String, placeId: String?): Int

    @Query("UPDATE hike_photos SET place_id = NULL WHERE hike_id = :hikeId AND place_id = :placeId")
    suspend fun unfileFromPlace(hikeId: String, placeId: String): Int

    @Query(
        """
        UPDATE hike_photos
        SET asset_local_identifier = :assetLocalIdentifier
        WHERE hike_id = :hikeId AND id = :photoId
        """
    )
    suspend fun linkAssetIdentifier(hikeId: String, photoId: String, assetLocalIdentifier: String): Int

    @Query("DELETE FROM hike_photos WHERE hike_id = :hikeId AND id = :photoId")
    suspend fun deleteById(hikeId: String, photoId: String): Int
}
