package app.openhikes.core.datastore

import androidx.datastore.core.DataStore
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.UserSettings
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Typed Proto DataStore backing for OpenHikes user settings.
 */
@Suppress("TooManyFunctions")
class OpenHikesPreferencesDataSource @Inject constructor(private val userPreferences: DataStore<UserPreferences>) {
    val userSettings: Flow<UserSettings> = userPreferences.data
        .catch { exception ->
            if (exception is IOException) {
                emit(UserPreferences.getDefaultInstance())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toUserSettings() }

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

    suspend fun setOfflineCountriesExtraDetail(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { offlineCountriesExtraDetail = enabled }
        }
    }

    suspend fun setOfflineCountriesElevationData(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { offlineCountriesElevationData = enabled }
        }
    }

    suspend fun setJunctionWarningsEnabled(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { junctionWarningsEnabled = enabled }
        }
    }

    suspend fun setShowsPlacePins(enabled: Boolean) {
        userPreferences.updateData { prefs ->
            prefs.copy { showsPlacePins = enabled }
        }
    }

    suspend fun setPlaceSymbolVisible(symbol: TrailPlaceSymbol, visible: Boolean) {
        userPreferences.updateData { prefs ->
            val current = prefs.hiddenPlaceSymbolsList
                .mapNotNull(TrailPlaceSymbol::fromStoredId)
                .toMutableSet()
            if (visible) {
                current.remove(symbol)
            } else {
                current.add(symbol)
            }
            val sorted = current.map { it.rawValue }.sorted()
            prefs.copy {
                hiddenPlaceSymbols.clear()
                hiddenPlaceSymbols.addAll(sorted)
            }
        }
    }

    suspend fun setHikeListSort(sort: HikeListSort) {
        userPreferences.updateData { prefs ->
            prefs.copy { hikeListSort = sort.rawValue }
        }
    }

    suspend fun setRouteColoring(coloring: RouteColoring) {
        userPreferences.updateData { prefs ->
            prefs.copy { routeColoring = coloring.rawValue }
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
        mapStyleId = mapStyleId.ifBlank { MapStyleId.OPENHIKES },
        offlineCountriesExtraDetail = if (hasOfflineCountriesExtraDetail()) {
            offlineCountriesExtraDetail
        } else {
            UserSettings.DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL
        },
        offlineCountriesElevationData = if (hasOfflineCountriesElevationData()) {
            offlineCountriesElevationData
        } else {
            UserSettings.DEFAULT_OFFLINE_COUNTRIES_ELEVATION_DATA
        },
        junctionWarningsEnabled = junctionWarningsEnabled,
        showsPlacePins = if (hasShowsPlacePins()) {
            showsPlacePins
        } else {
            UserSettings.DEFAULT_SHOWS_PLACE_PINS
        },
        hiddenPlaceSymbols = hiddenPlaceSymbolsList
            .mapNotNull(TrailPlaceSymbol::fromStoredId)
            .toSet(),
        hikeListSort = HikeListSort.fromStoredId(hikeListSort),
        routeColoring = RouteColoring.fromStoredId(routeColoring)
    )
}
