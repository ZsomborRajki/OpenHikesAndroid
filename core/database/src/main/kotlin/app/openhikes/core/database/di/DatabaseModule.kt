package app.openhikes.core.database.di

import android.content.Context
import androidx.room.Room
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailDraftDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.dao.TrailStopRecentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt bindings for the portable and device-local Room databases.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val PORTABLE_DB_NAME = "openhikes_portable.db"
    private const val LOCAL_DB_NAME = "openhikes_local.db"

    @Provides
    @Singleton
    fun provideOpenHikesDatabase(
        @ApplicationContext context: Context,
        launchConfigHolder: LaunchConfigHolder
    ): OpenHikesDatabase {
        val useInMemory = launchConfigHolder.current.value.useInMemoryDatabase
        val builder = if (useInMemory) {
            Room.inMemoryDatabaseBuilder(context, OpenHikesDatabase::class.java)
        } else {
            Room.databaseBuilder(context, OpenHikesDatabase::class.java, PORTABLE_DB_NAME)
        }
        return builder.fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides
    @Singleton
    fun provideOpenHikesLocalDatabase(
        @ApplicationContext context: Context,
        launchConfigHolder: LaunchConfigHolder
    ): OpenHikesLocalDatabase {
        val useInMemory = launchConfigHolder.current.value.useInMemoryDatabase
        val builder = if (useInMemory) {
            Room.inMemoryDatabaseBuilder(context, OpenHikesLocalDatabase::class.java)
        } else {
            val localFile = File(context.noBackupFilesDir, LOCAL_DB_NAME)
            localFile.parentFile?.mkdirs()
            Room.databaseBuilder(context, OpenHikesLocalDatabase::class.java, localFile.absolutePath)
        }
        return builder.fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides
    fun provideHikeDao(database: OpenHikesDatabase): HikeDao = database.hikeDao()

    @Provides
    fun provideTrailPointDao(database: OpenHikesDatabase): TrailPointDao = database.trailPointDao()

    @Provides
    fun provideHikeWalkDao(database: OpenHikesDatabase): HikeWalkDao = database.hikeWalkDao()

    @Provides
    fun provideHikePhotoDao(database: OpenHikesDatabase): HikePhotoDao = database.hikePhotoDao()

    @Provides
    fun provideHikeLocalStateDao(database: OpenHikesLocalDatabase): HikeLocalStateDao = database.hikeLocalStateDao()

    @Provides
    fun provideTrailDraftDao(database: OpenHikesLocalDatabase): TrailDraftDao = database.trailDraftDao()

    @Provides
    fun provideTrailStopRecentDao(database: OpenHikesLocalDatabase): TrailStopRecentDao = database.trailStopRecentDao()
}
