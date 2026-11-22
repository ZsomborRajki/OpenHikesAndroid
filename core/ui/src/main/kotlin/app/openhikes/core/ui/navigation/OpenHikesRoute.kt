package app.openhikes.core.ui.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Marker interface for all Navigation 3 route keys in OpenHikes.
 */
interface OpenHikesRoute : NavKey

/**
 * Root home screen hosted inside the adaptive bottom sheet / docked side panel.
 */
@Serializable
data object MapHomeRoute : OpenHikesRoute

/**
 * Application settings destination pushed onto the shared adaptive back stack.
 */
@Serializable
data object SettingsRoute : OpenHikesRoute

/**
 * Multibound installer registering a feature module's destinations on the shared [EntryProviderScope].
 */
typealias EntryProviderInstaller = EntryProviderScope<NavKey>.(OpenHikesNavigator) -> Unit
