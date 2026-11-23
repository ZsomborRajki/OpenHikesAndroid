package app.openhikes.core.testing.android

import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [UserSettingsRepository] fake for deterministic JVM and Compose tests.
 */
class FakeUserSettingsRepository(
    initialSettings: UserSettings = UserSettings(),
    settingsFlowOverride: Flow<UserSettings>? = null
) : UserSettingsRepository {
    private val settingsState = MutableStateFlow(initialSettings)

    override val userSettings: Flow<UserSettings> = settingsFlowOverride ?: settingsState.asStateFlow()

    val currentSettings: UserSettings
        get() = settingsState.value

    /**
     * When non-null, consumed once on the next preference mutation and thrown before updating state.
     */
    var nextWriteFailure: IOException? = null

    private fun consumeWriteFailureIfPresent() {
        val failure = nextWriteFailure ?: return
        nextWriteFailure = null
        throw failure
    }

    override suspend fun setDistanceUnit(unit: DistanceUnit) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(distanceUnit = unit) }
    }

    override suspend fun setShowsHikingRoutes(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(showsHikingRoutes = enabled) }
    }

    override suspend fun setAutoPauseEnabled(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(autoPauseEnabled = enabled) }
    }

    override suspend fun setHikeRemindersEnabled(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(hikeRemindersEnabled = enabled) }
    }

    override suspend fun setLiveUpdatesEnabled(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(liveUpdatesEnabled = enabled) }
    }

    override suspend fun setKeepScreenAwake(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(keepScreenAwake = enabled) }
    }

    override suspend fun setAutoSaveTilesEnabled(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(autoSaveTilesEnabled = enabled) }
    }

    override suspend fun setSavePhotosToLibrary(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(savePhotosToLibrary = enabled) }
    }

    override suspend fun setMapStyleId(styleId: String) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(mapStyleId = styleId) }
    }

    override suspend fun setOfflineCountriesExtraDetail(enabled: Boolean) {
        consumeWriteFailureIfPresent()
        settingsState.update { it.copy(offlineCountriesExtraDetail = enabled) }
    }
}
