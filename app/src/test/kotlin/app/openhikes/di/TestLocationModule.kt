package app.openhikes.di

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
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.io.File
import java.nio.file.Files
import javax.inject.Singleton

/**
 * Replaces hardware GPS, motion, power, service bridge, notification poster, and journal dir
 * with deterministic fakes for Robolectric integration tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LocationHardwareModule::class]
)
interface TestLocationModule {
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
        fun provideTestJournalDir(): File = Files.createTempDirectory("openhikes-test-journal").toFile()
    }
}
