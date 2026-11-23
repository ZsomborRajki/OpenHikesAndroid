package app.openhikes.core.location.di

import android.content.Context
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.location.AndroidDevicePowerMonitor
import app.openhikes.core.location.AndroidLocationFeed
import app.openhikes.core.location.AndroidNotificationPoster
import app.openhikes.core.location.AndroidRecordingServiceBridge
import app.openhikes.core.location.DefaultMotionFeed
import app.openhikes.core.location.DefaultRecordingController
import app.openhikes.core.location.DevicePowerMonitor
import app.openhikes.core.location.LocationFeed
import app.openhikes.core.location.MotionFeed
import app.openhikes.core.location.NotificationPoster
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingServiceBridge
import app.openhikes.core.location.TrackJournal
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RecordingJournalDir

/**
 * Software/repository bindings for the recording engine and track journal.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindingsModule {
    @Binds
    @Singleton
    internal abstract fun bindRecordingController(impl: DefaultRecordingController): RecordingController

    companion object {
        @Provides
        @Singleton
        fun provideTrackJournal(
            @RecordingJournalDir journalDir: File,
            clock: OpenHikesClock,
            @IoDispatcher ioDispatcher: CoroutineDispatcher
        ): TrackJournal = TrackJournal(
            directory = journalDir,
            clock = clock,
            ioDispatcher = ioDispatcher
        )
    }
}

/**
 * Hardware/OS bindings replaced by deterministic fakes in JVM and instrumented test modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationHardwareModule {
    @Binds
    @Singleton
    internal abstract fun bindLocationFeed(impl: AndroidLocationFeed): LocationFeed

    @Binds
    @Singleton
    internal abstract fun bindMotionFeed(impl: DefaultMotionFeed): MotionFeed

    @Binds
    @Singleton
    internal abstract fun bindDevicePowerMonitor(impl: AndroidDevicePowerMonitor): DevicePowerMonitor

    @Binds
    @Singleton
    internal abstract fun bindRecordingServiceBridge(impl: AndroidRecordingServiceBridge): RecordingServiceBridge

    @Binds
    @Singleton
    internal abstract fun bindNotificationPoster(impl: AndroidNotificationPoster): NotificationPoster

    companion object {
        private const val JOURNAL_DIR_NAME = "recording"

        @Provides
        @Singleton
        @RecordingJournalDir
        fun provideRecordingJournalDir(@ApplicationContext context: Context): File =
            File(context.noBackupFilesDir, JOURNAL_DIR_NAME)
    }
}
