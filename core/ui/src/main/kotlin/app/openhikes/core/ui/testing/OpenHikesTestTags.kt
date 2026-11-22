package app.openhikes.core.ui.testing

/**
 * Shared kebab-case semantic `testTag` identifiers aligned with OpenHikes UI tests.
 */
object OpenHikesTestTags {
    const val APP_ROOT = "app-root"
    const val TRAIL_MAP = "trail-map"
    const val MAP_SHEET = "map-sheet"
    const val MAP_SEARCH = "map-search"
    const val CLEAR_SEARCH_BUTTON = "clear-search-button"
    const val SETTINGS_BUTTON = "settings-button"
    const val SETTINGS_SCREEN = "settings-screen"
    const val IMPORT_GPX_BUTTON = "import-gpx-button"
    const val HIKE_TITLE_FIELD = "hike-title-field"
    const val RECORDING_PHASE = "recording-phase"
    const val RECORDING_POINT_COUNT = "recording-point-count"
    const val OFFLINE_MAPS_SEARCH = "offline-maps-search"

    fun offlineCountry(countryCode: String): String = "offline-country-$countryCode"
}
