package app.openhikes.di

import app.openhikes.core.common.LaunchConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Provides deterministic offline, isolated-DataStore launch settings for instrumented tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LaunchConfigModule::class]
)
object AndroidTestLaunchConfigModule {
    @Provides
    @Singleton
    fun provideInstrumentedLaunchConfig(): LaunchConfig = LaunchConfig(
        isUiTesting = true,
        useIsolatedDataStore = true,
        useInMemoryDatabase = true,
        simulatesOffline = true
    )
}
