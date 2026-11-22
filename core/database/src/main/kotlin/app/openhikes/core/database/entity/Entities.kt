package app.openhikes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hikes",
    indices = [
        Index(value = ["date_epoch_millis"]),
        Index(value = ["is_recording"])
    ]
)
data class HikeEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "custom_name") val customName: String?,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double?,
    @ColumnInfo(name = "date_epoch_millis") val dateEpochMillis: Long,
    @ColumnInfo(name = "tint_hex") val tintHex: String,
    @ColumnInfo(name = "route_width_dp") val routeWidthDp: Double,
    @ColumnInfo(name = "route_line_pattern_id") val routeLinePatternId: String,
    @ColumnInfo(name = "route_border_hex") val routeBorderHex: String,
    @ColumnInfo(name = "is_recording") val isRecording: Boolean,
    @ColumnInfo(name = "auto_follow_enabled") val autoFollowEnabled: Boolean,
    @ColumnInfo(name = "track_description") val trackDescription: String?,
    val author: String?,
    val keywords: String?,
    @ColumnInfo(name = "route_point_count") val routePointCount: Int
)

@Entity(
    tableName = "hike_route_chunks",
    primaryKeys = ["hike_id", "chunk_index"],
    foreignKeys = [
        ForeignKey(
            entity = HikeEntity::class,
            parentColumns = ["id"],
            childColumns = ["hike_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["hike_id"])]
)
data class HikeRouteChunkEntity(
    @ColumnInfo(name = "hike_id") val hikeId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "chunk_blob", typeAffinity = ColumnInfo.BLOB) val chunkBlob: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HikeRouteChunkEntity) return false
        return hikeId == other.hikeId &&
            chunkIndex == other.chunkIndex &&
            chunkBlob.contentEquals(other.chunkBlob)
    }

    override fun hashCode(): Int {
        var result = hikeId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + chunkBlob.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "trail_points",
    foreignKeys = [
        ForeignKey(
            entity = HikeEntity::class,
            parentColumns = ["id"],
            childColumns = ["hike_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["hike_id"])]
)
data class TrailPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "hike_id") val hikeId: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "elevation_meters") val elevationMeters: Double?,
    val name: String,
    val symbol: String?,
    val note: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)

@Entity(
    tableName = "hike_walks",
    foreignKeys = [
        ForeignKey(
            entity = HikeEntity::class,
            parentColumns = ["id"],
            childColumns = ["hike_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["hike_id"])]
)
data class HikeWalkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "hike_id") val hikeId: String,
    @ColumnInfo(name = "started_at_epoch_millis") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at_epoch_millis") val endedAtEpochMillis: Long,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double
)

@Entity(tableName = "hike_local_state")
data class HikeLocalStateEntity(
    @PrimaryKey @ColumnInfo(name = "hike_id") val hikeId: String,
    @ColumnInfo(name = "list_order") val listOrder: Int?,
    @ColumnInfo(name = "climb_meters") val climbMeters: Double?,
    @ColumnInfo(name = "descent_meters") val descentMeters: Double?,
    @ColumnInfo(name = "elevation_route_revision") val elevationRouteRevision: String?,
    @ColumnInfo(name = "owns_recording_draft") val ownsRecordingDraft: Boolean,
    @ColumnInfo(name = "auto_saved_tile_keys_json") val autoSavedTileKeysJson: String
)
