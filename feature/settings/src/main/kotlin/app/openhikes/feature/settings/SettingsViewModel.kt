package app.openhikes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
    private val errorIdGenerator = AtomicLong(0L)
    private val saveErrorState = MutableStateFlow<SettingsSaveError?>(null)
    private var lastFailedUpdate: (suspend () -> Unit)? = null

    val uiState: StateFlow<SettingsUiState> = combine(
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        },
        saveErrorState
    ) { settings, saveError ->
        SettingsUiState(
            settings = settings,
            appVersion = appBuildInfo.displayVersion,
            isLoaded = true,
            saveError = saveError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(appVersion = appBuildInfo.displayVersion)
    )

    fun onDistanceUnitChange(unit: DistanceUnit) {
        persistPreference { userSettingsRepository.setDistanceUnit(unit) }
    }

    fun onAutoPauseChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setAutoPauseEnabled(enabled) }
    }

    fun onHikeRemindersChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setHikeRemindersEnabled(enabled) }
    }

    fun onLiveUpdatesChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setLiveUpdatesEnabled(enabled) }
    }

    fun onKeepScreenAwakeChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setKeepScreenAwake(enabled) }
    }

    fun onAutoSaveTilesChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setAutoSaveTilesEnabled(enabled) }
    }

    fun onSavePhotosToLibraryChange(enabled: Boolean) {
        persistPreference { userSettingsRepository.setSavePhotosToLibrary(enabled) }
    }

    fun onRetrySave() {
        val pendingRetry = lastFailedUpdate ?: return
        persistPreference(pendingRetry)
    }

    fun onDismissSaveError() {
        saveErrorState.value = null
        lastFailedUpdate = null
    }

    private fun persistPreference(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
                lastFailedUpdate = null
                saveErrorState.value = null
            } catch (_: IOException) {
                lastFailedUpdate = update
                saveErrorState.value = SettingsSaveError(id = errorIdGenerator.incrementAndGet())
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
