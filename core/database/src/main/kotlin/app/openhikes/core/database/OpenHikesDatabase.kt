package app.openhikes.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.database.entity.TrailPointEntity

/**
 * Portable hiking library database (hikes, routes, places, and walk history).
 * Excluded from automatic cloud backup via `data_extraction_rules.xml`.
 */
@Database(
    entities = [
        HikeEntity::class,
        HikeRouteChunkEntity::class,
        TrailPointEntity::class,
        HikeWalkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class OpenHikesDatabase : RoomDatabase() {
    abstract fun hikeDao(): HikeDao

    abstract fun trailPointDao(): TrailPointDao

    abstract fun hikeWalkDao(): HikeWalkDao
}
