package app.openhikes.core.data

import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Main-safe repository contract for reading and updating OpenHikes user settings.
 */
interface UserSettingsRepository {
    val userSettings: Flow<UserSettings>

    suspend fun setDistanceUnit(unit: DistanceUnit)

    suspend fun setShowsHikingRoutes(enabled: Boolean)

    suspend fun setAutoPauseEnabled(enabled: Boolean)

    suspend fun setHikeRemindersEnabled(enabled: Boolean)

    suspend fun setLiveUpdatesEnabled(enabled: Boolean)

    suspend fun setKeepScreenAwake(enabled: Boolean)

    suspend fun setAutoSaveTilesEnabled(enabled: Boolean)

    suspend fun setSavePhotosToLibrary(enabled: Boolean)

    suspend fun setMapStyleId(styleId: String)

    suspend fun setOfflineCountriesExtraDetail(enabled: Boolean)

    suspend fun setJunctionWarningsEnabled(enabled: Boolean)
}
