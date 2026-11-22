package app.openhikes.di

import app.openhikes.core.common.LaunchConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces production [LaunchConfigModule] in JVM tests so DataStore and maps remain isolated.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LaunchConfigModule::class]
)
object TestLaunchConfigModule {
    @Provides
    @Singleton
    fun provideTestLaunchConfig(): LaunchConfig = LaunchConfig(
        isUiTesting = true,
        useIsolatedDataStore = true,
        useInMemoryDatabase = true,
        simulatesOffline = true
    )
}
