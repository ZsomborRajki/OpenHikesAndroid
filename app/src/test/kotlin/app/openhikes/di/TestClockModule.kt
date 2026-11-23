package app.openhikes.di

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.testing.TestClock
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the production wall clock with a deterministic [TestClock] in Robolectric unit tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ClockModule::class]
)
object TestClockModule {
    @Provides
    @Singleton
    fun provideTestClock(): TestClock = TestClock()

    @Provides
    @Singleton
    fun provideOpenHikesClock(clock: TestClock): OpenHikesClock = clock
}
