package app.openhikes.feature.settings

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.UserSettings

/**
 * Recoverable settings persistence failure shown in [SettingsScreen] until retried or dismissed.
 */
@Immutable
data class SettingsSaveError(val id: Long)

/**
 * Immutable UI state rendered by [SettingsScreen].
 */
@Immutable
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val appVersion: String = "",
    val offlineInstalledBytes: Long = 0L,
    val isLoaded: Boolean = false,
    val saveError: SettingsSaveError? = null
)
