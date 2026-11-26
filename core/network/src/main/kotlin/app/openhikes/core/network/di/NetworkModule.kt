package app.openhikes.core.network.di

import android.content.Context
import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.network.AndroidTileNetworkPolicy
import app.openhikes.core.network.DownloadOkHttpClient
import app.openhikes.core.network.HttpRoutingGraphSquareDataSource
import app.openhikes.core.network.HttpVectorTileManifestDataSource
import app.openhikes.core.network.LaunchAwareOfflineCountryServer
import app.openhikes.core.network.ManifestOkHttpClient
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.OpenHikesUserAgent
import app.openhikes.core.network.PreviewTileOkHttpClient
import app.openhikes.core.network.RoutingGraphSquareDataSource
import app.openhikes.core.network.RoutingSquaresDir
import app.openhikes.core.network.TileNetworkPolicy
import app.openhikes.core.network.TileOkHttpClient
import app.openhikes.core.network.TileServerBaseUrl
import app.openhikes.core.network.VectorArchivesFile
import app.openhikes.core.network.VectorTileArchivesManager
import app.openhikes.core.network.VectorTileManifestDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Production Hilt bindings for OkHttp clients, tile manifests, network policy, and offline country server.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingsModule {
    @Binds
    @Singleton
    internal abstract fun bindTileNetworkPolicy(impl: AndroidTileNetworkPolicy): TileNetworkPolicy

    @Binds
    @Singleton
    internal abstract fun bindVectorTileManifestDataSource(
        impl: HttpVectorTileManifestDataSource
    ): VectorTileManifestDataSource

    @Binds
    @Singleton
    internal abstract fun bindOfflineCountryServer(impl: LaunchAwareOfflineCountryServer): OfflineCountryServer

    @Binds
    @Singleton
    internal abstract fun bindRoutingGraphSquareDataSource(
        impl: HttpRoutingGraphSquareDataSource
    ): RoutingGraphSquareDataSource
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkProvidesModule {
    private const val TILE_TIMEOUT_SECONDS = 10L
    private const val MANIFEST_TIMEOUT_SECONDS = 20L
    private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 60L
    private const val MANIFEST_CACHE_BYTES = 16L * 1024L * 1024L
    private const val PREVIEW_TILE_CACHE_BYTES = 32L * 1024L * 1024L
    private const val HTTP_CACHE_DIR_NAME = "openhikes_manifest_http_cache"
    private const val PREVIEW_TILE_HTTP_CACHE_DIR_NAME = "openhikes_tile_preview_http_cache"

    @Provides
    @Singleton
    @TileServerBaseUrl
    fun provideTileServerBaseUrl(): String = VectorTileArchiveManifest.DEFAULT_SERVER_BASE_URL

    @Provides
    @Singleton
    @VectorArchivesFile
    fun provideVectorArchivesFile(@ApplicationContext context: Context): File =
        File(context.noBackupFilesDir, VectorTileArchivesManager.STATE_FILE_NAME)

    @Provides
    @Singleton
    @RoutingSquaresDir
    fun provideRoutingSquaresDir(@ApplicationContext context: Context): File =
        File(context.noBackupFilesDir, HttpRoutingGraphSquareDataSource.CACHE_DIR_NAME)

    @Provides
    @Singleton
    @TileOkHttpClient
    fun provideTileOkHttpClient(buildInfo: AppBuildInfo): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(userAgentInterceptor(buildInfo))
        .connectTimeout(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @PreviewTileOkHttpClient
    fun providePreviewTileOkHttpClient(@ApplicationContext context: Context, buildInfo: AppBuildInfo): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, PREVIEW_TILE_HTTP_CACHE_DIR_NAME), PREVIEW_TILE_CACHE_BYTES))
            .addInterceptor(userAgentInterceptor(buildInfo))
            .connectTimeout(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @ManifestOkHttpClient
    fun provideManifestOkHttpClient(@ApplicationContext context: Context, buildInfo: AppBuildInfo): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, HTTP_CACHE_DIR_NAME), MANIFEST_CACHE_BYTES))
            .addInterceptor(userAgentInterceptor(buildInfo))
            .connectTimeout(MANIFEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MANIFEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @DownloadOkHttpClient
    fun provideDownloadOkHttpClient(buildInfo: AppBuildInfo): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(userAgentInterceptor(buildInfo))
        .connectTimeout(MANIFEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun userAgentInterceptor(buildInfo: AppBuildInfo): Interceptor {
        val header = OpenHikesUserAgent.build(buildInfo)
        return Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header(OpenHikesUserAgent.HEADER_NAME, header)
                .build()
            chain.proceed(request)
        }
    }
}
