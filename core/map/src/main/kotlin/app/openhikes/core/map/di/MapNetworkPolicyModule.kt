package app.openhikes.core.map.di

import app.openhikes.core.map.AndroidMapNetworkPolicy
import app.openhikes.core.map.MapNetworkPolicy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the production [MapNetworkPolicy] observing connectivity and Android Data Saver.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MapNetworkPolicyModule {
    @Binds
    @Singleton
    internal abstract fun bindMapNetworkPolicy(impl: AndroidMapNetworkPolicy): MapNetworkPolicy
}
