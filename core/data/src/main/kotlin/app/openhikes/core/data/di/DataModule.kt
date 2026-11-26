package app.openhikes.core.data.di

import android.content.Context
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.data.AndroidGpxFileSink
import app.openhikes.core.data.AndroidGpxFileSource
import app.openhikes.core.data.AndroidStopPlaceSearchSource
import app.openhikes.core.data.ArchiveTrailGraphProvider
import app.openhikes.core.data.AssetBundledGpxFixtureSource
import app.openhikes.core.data.AssetBundledTrailGraphFixtureSource
import app.openhikes.core.data.BundledGpxFixtureSource
import app.openhikes.core.data.BundledTrailGraphFixtureSource
import app.openhikes.core.data.DefaultOfflineCountryRepository
import app.openhikes.core.data.GpxFileSink
import app.openhikes.core.data.GpxFileSource
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.OfflineCountryArchives
import app.openhikes.core.data.OfflineCountryDownloadScheduler
import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.OfflineFirstHikeRepository
import app.openhikes.core.data.OfflineFirstTrailDraftRepository
import app.openhikes.core.data.OfflineFirstUserSettingsRepository
import app.openhikes.core.data.OfflineTileArchiveSource
import app.openhikes.core.data.OsmTrailLegRouter
import app.openhikes.core.data.StopPlaceSearchSource
import app.openhikes.core.data.TrailDraftRepository
import app.openhikes.core.data.TrailGraphRouteStretchRouter
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.data.WorkManagerOfflineCountryDownloadScheduler
import app.openhikes.core.model.RouteStretchRouter
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegRouter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.UUID
import javax.inject.Singleton

/**
 * Hilt bindings for `:core:data` repositories, GPX IO, and offline country archive storage.
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

    @Binds
    @Singleton
    internal abstract fun bindOfflineCountryRepository(impl: DefaultOfflineCountryRepository): OfflineCountryRepository

    @Binds
    @Singleton
    internal abstract fun bindOfflineTileArchiveSource(impl: OfflineCountryArchives): OfflineTileArchiveSource

    @Binds
    @Singleton
    internal abstract fun bindOfflineCountryDownloadScheduler(
        impl: WorkManagerOfflineCountryDownloadScheduler
    ): OfflineCountryDownloadScheduler

    @Binds
    @Singleton
    internal abstract fun bindRouteStretchRouter(impl: TrailGraphRouteStretchRouter): RouteStretchRouter

    @Binds
    @Singleton
    internal abstract fun bindTrailDraftRepository(impl: OfflineFirstTrailDraftRepository): TrailDraftRepository

    @Binds
    @Singleton
    internal abstract fun bindStopPlaceSearchSource(impl: AndroidStopPlaceSearchSource): StopPlaceSearchSource

    @Binds
    @Singleton
    internal abstract fun bindBundledTrailGraphFixtureSource(
        impl: AssetBundledTrailGraphFixtureSource
    ): BundledTrailGraphFixtureSource

    @Binds
    @Singleton
    internal abstract fun bindTrailGraphProvider(impl: ArchiveTrailGraphProvider): TrailGraphProvider

    @Binds
    @Singleton
    internal abstract fun bindTrailLegRouter(impl: OsmTrailLegRouter): TrailLegRouter
}

@Module
@InstallIn(SingletonComponent::class)
object OfflineStoreProvidesModule {
    @Provides
    @Singleton
    fun provideOfflineCountryStore(
        @ApplicationContext context: Context,
        launchConfigHolder: LaunchConfigHolder
    ): OfflineCountryStore {
        val config = launchConfigHolder.current.value
        val directory = if (config.isUiTesting && config.offlineCountriesScenarioName != null) {
            File(context.cacheDir, "ui_test_offline_${UUID.randomUUID()}")
        } else {
            File(context.noBackupFilesDir, OfflineCountryStore.DIRECTORY_NAME)
        }
        return OfflineCountryStore(directory)
    }
}
