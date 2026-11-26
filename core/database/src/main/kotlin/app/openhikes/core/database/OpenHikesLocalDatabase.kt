package app.openhikes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.TrailDraftDao
import app.openhikes.core.database.dao.TrailStopRecentDao
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.TrailDraftEntity
import app.openhikes.core.database.entity.TrailStopRecentEntity

/**
 * Device-bound state database stored in `noBackupFilesDir` and excluded from both
 * cloud backup and device-to-device transfers.
 */
@Database(
    entities = [
        HikeLocalStateEntity::class,
        TrailDraftEntity::class,
        TrailStopRecentEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OpenHikesLocalDatabase : RoomDatabase() {
    abstract fun hikeLocalStateDao(): HikeLocalStateDao

    abstract fun trailDraftDao(): TrailDraftDao

    abstract fun trailStopRecentDao(): TrailStopRecentDao
}
