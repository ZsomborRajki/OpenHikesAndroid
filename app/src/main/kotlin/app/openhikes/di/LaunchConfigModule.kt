package app.openhikes.di

import app.openhikes.core.common.LaunchConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the default [LaunchConfig] for production builds; test suites replace this module via `@TestInstallIn`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LaunchConfigModule {
    @Provides
    @Singleton
    fun provideLaunchConfig(): LaunchConfig = LaunchConfig.Production
}
