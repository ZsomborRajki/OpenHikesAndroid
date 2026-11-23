package app.openhikes.di

import android.content.Context
import app.openhikes.core.location.DevicePowerMonitor
import app.openhikes.core.location.LocationFeed
import app.openhikes.core.location.MotionFeed
import app.openhikes.core.location.NotificationPoster
import app.openhikes.core.location.RecordingServiceBridge
import app.openhikes.core.location.di.LocationHardwareModule
import app.openhikes.core.location.di.RecordingJournalDir
import app.openhikes.core.testing.android.FakeDevicePowerMonitor
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeMotionFeed
import app.openhikes.core.testing.android.FakeNotificationPoster
import app.openhikes.core.testing.android.FakeRecordingServiceBridge
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import javax.inject.Singleton

/**
 * Replaces location hardware feeds with deterministic fakes in instrumented accessibility tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LocationHardwareModule::class]
)
interface AndroidTestLocationModule {
    @Binds
    @Singleton
    fun bindLocationFeed(impl: FakeLocationFeed): LocationFeed

    @Binds
    @Singleton
    fun bindMotionFeed(impl: FakeMotionFeed): MotionFeed

    @Binds
    @Singleton
    fun bindDevicePowerMonitor(impl: FakeDevicePowerMonitor): DevicePowerMonitor

    @Binds
    @Singleton
    fun bindServiceBridge(impl: FakeRecordingServiceBridge): RecordingServiceBridge

    @Binds
    @Singleton
    fun bindNotificationPoster(impl: FakeNotificationPoster): NotificationPoster

    companion object {
        @Provides
        @Singleton
        @RecordingJournalDir
        fun provideJournalDir(@ApplicationContext context: Context): File =
            File(context.cacheDir, "android-test-recording-journal")
    }
}
