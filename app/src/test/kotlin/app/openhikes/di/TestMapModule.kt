package app.openhikes.di

import app.openhikes.core.map.MapStylePreviewProvider
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.map.di.MapModule
import app.openhikes.core.testing.android.FakeMapStylePreviewProvider
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces MapLibre and live tile preview fetching with deterministic fakes in JVM Robolectric tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [MapModule::class]
)
interface TestMapModule {
    @Binds
    @Singleton
    fun bindFakeTrailMapRenderer(impl: FakeTrailMapRenderer): TrailMapRenderer

    @Binds
    @Singleton
    fun bindFakeMapStylePreviewProvider(impl: FakeMapStylePreviewProvider): MapStylePreviewProvider
}
