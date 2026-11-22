package app.openhikes.di

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.DefaultOpenHikesDispatchers
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.MainDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.OpenHikesDispatchers
import app.openhikes.core.common.SystemOpenHikesClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Root Hilt bindings for dispatchers, application coroutine scope, and clock.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    @IoDispatcher
    @Suppress("InjectDispatcher")
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @DefaultDispatcher
    @Suppress("InjectDispatcher")
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher defaultDispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + defaultDispatcher)

    @Provides
    @Singleton
    fun provideOpenHikesDispatchers(
        @IoDispatcher io: CoroutineDispatcher,
        @DefaultDispatcher default: CoroutineDispatcher,
        @MainDispatcher main: CoroutineDispatcher
    ): OpenHikesDispatchers = DefaultOpenHikesDispatchers(
        io = io,
        default = default,
        main = main
    )

    @Provides
    @Singleton
    fun provideClock(): OpenHikesClock = SystemOpenHikesClock()
}
