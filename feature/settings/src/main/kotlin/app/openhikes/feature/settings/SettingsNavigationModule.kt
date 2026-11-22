package app.openhikes.feature.settings

import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.SettingsRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Settings destination into the app's Navigation 3 entry provider graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsNavigationModule {
    @Provides
    @IntoSet
    fun provideSettingsEntryProvider(): EntryProviderInstaller = { navigator ->
        entry<SettingsRoute> {
            SettingsRoute(onClose = { navigator.pop() })
        }
    }
}
