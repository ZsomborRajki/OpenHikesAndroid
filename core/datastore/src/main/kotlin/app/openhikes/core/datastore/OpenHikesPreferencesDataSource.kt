package app.openhikes.core.datastore

import androidx.datastore.core.DataStore
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.UserSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Typed Proto DataStore backing for OpenHikes user settings.
 */
class OpenHikesPreferencesDataSource @Inject constructor(private val userPreferences: DataStore<UserPreferences>) {
    val userSettings: Flow<UserSettings> = userPreferences.data.map { prefs ->
        prefs.toUserSettings()
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        userPreferences.updateData { prefs ->
            prefs.copy {
                distanceUnit = when (unit) {
                    DistanceUnit.METRIC -> DistanceUnitProto.DISTANCE_UNIT_METRIC
                    DistanceUnit.IMPERIAL -> DistanceUnitProto.DISTANCE_UNIT_IMPERIAL
                }
            }
        }
    }

    suspend fun setShowsHikingRoutes(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { showsHikingRoutes = enabled }
        }
    }

    suspend fun setAutoPauseEnabled(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { autoPauseEnabled = enabled }
        }
    }

    suspend fun setHikeRemindersEnabled(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { hikeRemindersEnabled = enabled }
        }
    }

    suspend fun setLiveUpdatesEnabled(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { liveUpdatesEnabled = enabled }
        }
    }

    suspend fun setKeepScreenAwake(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { keepScreenAwake = enabled }
        }
    }

    suspend fun setAutoSaveTilesEnabled(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { autoSaveTilesEnabled = enabled }
        }
    }

    suspend fun setSavePhotosToLibrary(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { savePhotosToLibrary = enabled }
        }
    }

    suspend fun setMapStyleId(styleId: String) {
        userPreferences.updateData { prefs ->
            prefs.copy { mapStyleId = styleId }
        }
    }
}

private fun UserPreferences.toUserSettings(): UserSettings {
    val resolvedUnit = when (distanceUnit) {
        DistanceUnitProto.DISTANCE_UNIT_IMPERIAL -> DistanceUnit.IMPERIAL
        DistanceUnitProto.DISTANCE_UNIT_METRIC,
        DistanceUnitProto.DISTANCE_UNIT_UNSPECIFIED,
        DistanceUnitProto.UNRECOGNIZED -> DistanceUnit.METRIC
    }

    return UserSettings(
        distanceUnit = resolvedUnit,
        showsHikingRoutes = if (hasShowsHikingRoutes()) {
            showsHikingRoutes
        } else {
            UserSettings.DEFAULT_SHOWS_HIKING_ROUTES
        },
        autoPauseEnabled = if (hasAutoPauseEnabled()) {
            autoPauseEnabled
        } else {
            UserSettings.DEFAULT_AUTO_PAUSE_ENABLED
        },
        hikeRemindersEnabled = if (hasHikeRemindersEnabled()) {
            hikeRemindersEnabled
        } else {
            UserSettings.DEFAULT_HIKE_REMINDERS_ENABLED
        },
        liveUpdatesEnabled = if (hasLiveUpdatesEnabled()) {
            liveUpdatesEnabled
        } else {
            UserSettings.DEFAULT_LIVE_UPDATES_ENABLED
        },
        keepScreenAwake = keepScreenAwake,
        autoSaveTilesEnabled = autoSaveTilesEnabled,
        savePhotosToLibrary = savePhotosToLibrary,
        mapStyleId = mapStyleId.ifBlank { MapStyleId.OPENHIKES }
    )
}
