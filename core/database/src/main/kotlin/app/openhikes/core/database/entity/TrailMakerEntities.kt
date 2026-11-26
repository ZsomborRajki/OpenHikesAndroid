package app.openhikes.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Single device-local row storing the in-progress Trail Maker drawing in `noBackupFilesDir`.
 */
@Entity(tableName = "trail_draft")
data class TrailDraftEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "draft_json") val draftJson: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * Device-local recent place picked in Trail Maker stop search.
 */
@Entity(
    tableName = "trail_stop_recents",
    indices = [Index(value = ["used_at_epoch_millis"])]
)
data class TrailStopRecentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "used_at_epoch_millis") val usedAtEpochMillis: Long
)
