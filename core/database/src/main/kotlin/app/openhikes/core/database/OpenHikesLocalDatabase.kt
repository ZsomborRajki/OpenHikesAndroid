package app.openhikes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.entity.HikeLocalStateEntity

/**
 * Device-bound state database stored in `noBackupFilesDir` and excluded from both
 * cloud backup and device-to-device transfers.
 */
@Database(
    entities = [HikeLocalStateEntity::class],
    version = 2,
    exportSchema = false
)
abstract class OpenHikesLocalDatabase : RoomDatabase() {
    abstract fun hikeLocalStateDao(): HikeLocalStateDao
}
