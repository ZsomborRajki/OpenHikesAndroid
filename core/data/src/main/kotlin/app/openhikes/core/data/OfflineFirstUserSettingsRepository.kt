package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.datastore.OpenHikesPreferencesDataSource
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.UserSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Proto DataStore-backed [UserSettingsRepository] switching all disk writes onto [ioDispatcher].
 */
@Suppress("TooManyFunctions")
internal class OfflineFirstUserSettingsRepository @Inject constructor(
    private val preferencesDataSource: OpenHikesPreferencesDataSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UserSettingsRepository {
    override val userSettings: Flow<UserSettings> = preferencesDataSource.userSettings

    override suspend fun setDistanceUnit(unit: DistanceUnit) = withContext(ioDispatcher) {
        preferencesDataSource.setDistanceUnit(unit)
    }

    override suspend fun setShowsHikingRoutes(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setShowsHikingRoutes(enabled)
    }

    override suspend fun setAutoPauseEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setAutoPauseEnabled(enabled)
    }

    override suspend fun setHikeRemindersEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setHikeRemindersEnabled(enabled)
    }

    override suspend fun setLiveUpdatesEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setLiveUpdatesEnabled(enabled)
    }

    override suspend fun setKeepScreenAwake(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setKeepScreenAwake(enabled)
    }

    override suspend fun setAutoSaveTilesEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setAutoSaveTilesEnabled(enabled)
    }

    override suspend fun setSavePhotosToLibrary(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setSavePhotosToLibrary(enabled)
    }

    override suspend fun setMapStyleId(styleId: String) = withContext(ioDispatcher) {
        preferencesDataSource.setMapStyleId(styleId)
    }

    override suspend fun setOfflineCountriesExtraDetail(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setOfflineCountriesExtraDetail(enabled)
    }

    override suspend fun setOfflineCountriesElevationData(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setOfflineCountriesElevationData(enabled)
    }

    override suspend fun setJunctionWarningsEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setJunctionWarningsEnabled(enabled)
    }

    override suspend fun setShowsPlacePins(enabled: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setShowsPlacePins(enabled)
    }

    override suspend fun setPlaceSymbolVisible(symbol: TrailPlaceSymbol, visible: Boolean) = withContext(ioDispatcher) {
        preferencesDataSource.setPlaceSymbolVisible(symbol, visible)
    }

    override suspend fun setHikeListSort(sort: HikeListSort) = withContext(ioDispatcher) {
        preferencesDataSource.setHikeListSort(sort)
    }

    override suspend fun setRouteColoring(coloring: RouteColoring) = withContext(ioDispatcher) {
        preferencesDataSource.setRouteColoring(coloring)
    }
}
