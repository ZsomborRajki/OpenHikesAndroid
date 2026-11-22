package app.openhikes.feature.settings

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.UserSettings

/**
 * Immutable UI state rendered by [SettingsScreen].
 */
@Immutable
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val appVersion: String = "",
    val isLoaded: Boolean = false
)
