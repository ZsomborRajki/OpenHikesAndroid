package app.openhikes.core.data.di

import app.openhikes.core.data.AndroidGpxFileSink
import app.openhikes.core.data.AndroidGpxFileSource
import app.openhikes.core.data.AssetBundledGpxFixtureSource
import app.openhikes.core.data.BundledGpxFixtureSource
import app.openhikes.core.data.GpxFileSink
import app.openhikes.core.data.GpxFileSource
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.OfflineFirstHikeRepository
import app.openhikes.core.data.OfflineFirstUserSettingsRepository
import app.openhikes.core.data.UserSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `:core:data` repositories and GPX IO seams.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    internal abstract fun bindUserSettingsRepository(impl: OfflineFirstUserSettingsRepository): UserSettingsRepository

    @Binds
    @Singleton
    internal abstract fun bindHikeRepository(impl: OfflineFirstHikeRepository): HikeRepository

    @Binds
    @Singleton
    internal abstract fun bindGpxFileSource(impl: AndroidGpxFileSource): GpxFileSource

    @Binds
    @Singleton
    internal abstract fun bindGpxFileSink(impl: AndroidGpxFileSink): GpxFileSink

    @Binds
    @Singleton
    internal abstract fun bindBundledGpxFixtureSource(impl: AssetBundledGpxFixtureSource): BundledGpxFixtureSource
}
