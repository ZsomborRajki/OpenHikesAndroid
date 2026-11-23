package app.openhikes.di

import android.os.SystemClock
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.SystemOpenHikesClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Production clock module kept separate from [CoreModule] so unit and instrumented test graphs
 * can swap in a deterministic [OpenHikesClock] via `@TestInstallIn`.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun provideClock(): OpenHikesClock = SystemOpenHikesClock(
        monotonicMillisProvider = SystemClock::elapsedRealtime
    )
}
