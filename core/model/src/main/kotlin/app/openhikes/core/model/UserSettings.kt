package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * User-configurable preferences persisted locally via Proto DataStore.
 */
@Serializable
data class UserSettings(
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val showsHikingRoutes: Boolean = DEFAULT_SHOWS_HIKING_ROUTES,
    val autoPauseEnabled: Boolean = DEFAULT_AUTO_PAUSE_ENABLED,
    val hikeRemindersEnabled: Boolean = DEFAULT_HIKE_REMINDERS_ENABLED,
    val liveUpdatesEnabled: Boolean = DEFAULT_LIVE_UPDATES_ENABLED,
    val keepScreenAwake: Boolean = DEFAULT_KEEP_SCREEN_AWAKE,
    val autoSaveTilesEnabled: Boolean = DEFAULT_AUTO_SAVE_TILES_ENABLED,
    val savePhotosToLibrary: Boolean = DEFAULT_SAVE_PHOTOS_TO_LIBRARY,
    val mapStyleId: String = MapStyleId.OPENHIKES,
    val offlineCountriesExtraDetail: Boolean = DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL,
    val junctionWarningsEnabled: Boolean = DEFAULT_JUNCTION_WARNINGS_ENABLED,
    val showsPlacePins: Boolean = DEFAULT_SHOWS_PLACE_PINS,
    val hiddenPlaceSymbols: Set<TrailPlaceSymbol> = emptySet(),
    val hikeListSort: HikeListSort = HikeListSort.Default,
    val routeColoring: RouteColoring = RouteColoring.Default
) {
    val shownSearchPlaceSymbols: Set<TrailPlaceSymbol>
        get() = TrailPointQuery.searchableSymbols.toSet() - hiddenPlaceSymbols

    companion object {
        const val DEFAULT_SHOWS_HIKING_ROUTES = true
        const val DEFAULT_AUTO_PAUSE_ENABLED = true
        const val DEFAULT_HIKE_REMINDERS_ENABLED = true
        const val DEFAULT_LIVE_UPDATES_ENABLED = true
        const val DEFAULT_KEEP_SCREEN_AWAKE = false
        const val DEFAULT_AUTO_SAVE_TILES_ENABLED = false
        const val DEFAULT_SAVE_PHOTOS_TO_LIBRARY = false
        const val DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL = true
        const val DEFAULT_JUNCTION_WARNINGS_ENABLED = false
        const val DEFAULT_SHOWS_PLACE_PINS = true
    }
}
