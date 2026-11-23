package app.openhikes.di

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.testing.TestClock
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Provides deterministic time for instrumented tests without coupling to wall-clock drift.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ClockModule::class]
)
object AndroidTestClockModule {
    @Provides
    @Singleton
    fun provideTestClock(): TestClock = TestClock()

    @Provides
    @Singleton
    fun provideOpenHikesClock(clock: TestClock): OpenHikesClock = clock
}
