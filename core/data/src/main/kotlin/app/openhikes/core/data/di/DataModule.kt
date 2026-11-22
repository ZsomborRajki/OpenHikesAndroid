package app.openhikes.core.data.di

import app.openhikes.core.data.OfflineFirstUserSettingsRepository
import app.openhikes.core.data.UserSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `:core:data` repositories.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    internal abstract fun bindUserSettingsRepository(impl: OfflineFirstUserSettingsRepository): UserSettingsRepository
}
