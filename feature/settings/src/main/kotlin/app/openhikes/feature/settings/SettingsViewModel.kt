package app.openhikes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.DistanceUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinates Settings screen state and persists user preference changes via [UserSettingsRepository].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    appBuildInfo: AppBuildInfo
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = userSettingsRepository.userSettings
        .map { settings ->
            SettingsUiState(
                settings = settings,
                appVersion = appBuildInfo.displayVersion,
                isLoaded = true
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(appVersion = appBuildInfo.displayVersion)
        )

    fun onDistanceUnitChange(unit: DistanceUnit) {
        viewModelScope.launch { userSettingsRepository.setDistanceUnit(unit) }
    }

    fun onAutoPauseChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setAutoPauseEnabled(enabled) }
    }

    fun onHikeRemindersChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setHikeRemindersEnabled(enabled) }
    }

    fun onLiveUpdatesChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setLiveUpdatesEnabled(enabled) }
    }

    fun onKeepScreenAwakeChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setKeepScreenAwake(enabled) }
    }

    fun onAutoSaveTilesChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setAutoSaveTilesEnabled(enabled) }
    }

    fun onSavePhotosToLibraryChange(enabled: Boolean) {
        viewModelScope.launch { userSettingsRepository.setSavePhotosToLibrary(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
