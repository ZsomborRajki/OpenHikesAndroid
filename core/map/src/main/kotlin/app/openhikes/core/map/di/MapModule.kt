package app.openhikes.core.map.di

import app.openhikes.core.map.MapLibreTrailMapRenderer
import app.openhikes.core.map.TrailMapRenderer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Production Hilt binding for [TrailMapRenderer].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapModule {
    @Binds
    @Singleton
    internal abstract fun bindTrailMapRenderer(impl: MapLibreTrailMapRenderer): TrailMapRenderer
}
