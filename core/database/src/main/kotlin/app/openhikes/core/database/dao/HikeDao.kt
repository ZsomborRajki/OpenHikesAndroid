package app.openhikes.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeRouteEntity
import kotlinx.coroutines.flow.Flow

data class HikeWithRouteRow(
    @Embedded val hike: HikeEntity,
    @ColumnInfo(name = "route_blob") val routeBlob: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HikeWithRouteRow) return false
        return hike == other.hike &&
            (routeBlob?.contentEquals(other.routeBlob) ?: (other.routeBlob == null))
    }

    override fun hashCode(): Int = 31 * hike.hashCode() + (routeBlob?.contentHashCode() ?: 0)
}

data class HikeMapRouteRow(
    @ColumnInfo(name = "hike_id") val hikeId: String,
    @ColumnInfo(name = "route_blob") val routeBlob: ByteArray,
    @ColumnInfo(name = "tint_hex") val tintHex: String,
    @ColumnInfo(name = "route_border_hex") val routeBorderHex: String,
    @ColumnInfo(name = "route_width_dp") val routeWidthDp: Double,
    @ColumnInfo(name = "route_line_pattern_id") val routeLinePatternId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HikeMapRouteRow) return false
        return hikeId == other.hikeId &&
            routeBlob.contentEquals(other.routeBlob) &&
            tintHex == other.tintHex &&
            routeBorderHex == other.routeBorderHex &&
            routeWidthDp == other.routeWidthDp &&
            routeLinePatternId == other.routeLinePatternId
    }

    override fun hashCode(): Int {
        var result = hikeId.hashCode()
        result = 31 * result + routeBlob.contentHashCode()
        result = 31 * result + tintHex.hashCode()
        result = 31 * result + routeBorderHex.hashCode()
        result = 31 * result + routeWidthDp.hashCode()
        result = 31 * result + routeLinePatternId.hashCode()
        return result
    }
}

@Dao
interface HikeDao {
    @Query("SELECT * FROM hikes ORDER BY date_epoch_millis DESC, id ASC")
    fun observeAllHikes(): Flow<List<HikeEntity>>

    @Query(
        """
        SELECT h.*, r.route_blob AS route_blob
        FROM hikes h
        LEFT JOIN hike_routes r ON h.id = r.hike_id
        WHERE h.id = :hikeId
        LIMIT 1
        """
    )
    fun observeHikeWithRoute(hikeId: String): Flow<HikeWithRouteRow?>

    @Query("SELECT * FROM hikes WHERE id = :hikeId LIMIT 1")
    suspend fun getHikeById(hikeId: String): HikeEntity?

    @Query("SELECT COUNT(*) FROM hikes")
    suspend fun getHikeCount(): Int

    @Query(
        """
        SELECT h.id AS hike_id,
               r.route_blob AS route_blob,
               h.tint_hex AS tint_hex,
               h.route_border_hex AS route_border_hex,
               h.route_width_dp AS route_width_dp,
               h.route_line_pattern_id AS route_line_pattern_id
        FROM hikes h
        INNER JOIN hike_routes r ON h.id = r.hike_id
        WHERE h.is_recording = 0
        ORDER BY h.date_epoch_millis DESC, h.id ASC
        """
    )
    fun observeAllMapRoutes(): Flow<List<HikeMapRouteRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHike(hike: HikeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: HikeRouteEntity)

    @Transaction
    suspend fun insertHikeWithRoute(hike: HikeEntity, route: HikeRouteEntity) {
        insertHike(hike)
        insertRoute(route)
    }

    @Query("UPDATE hikes SET custom_name = :customName WHERE id = :hikeId")
    suspend fun updateCustomName(hikeId: String, customName: String?): Int

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

    @Query("DELETE FROM hikes WHERE id = :hikeId")
    suspend fun deleteHikeById(hikeId: String): Int
}
