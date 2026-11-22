package app.openhikes.core.ui.testing

/**
 * Shared kebab-case semantic `testTag` identifiers aligned with OpenHikes UI tests.
 */
object OpenHikesTestTags {
    const val APP_ROOT = "app-root"
    const val TRAIL_MAP = "trail-map"
    const val MAP_SHEET = "map-sheet"
    const val MAP_SIDE_PANEL = "map-side-panel"
    const val MAP_SEARCH = "map-search"
    const val CLEAR_SEARCH_BUTTON = "clear-search-button"
    const val MAP_ATTRIBUTION = "map-attribution"
    const val MAP_ATTRIBUTION_DIALOG = "map-attribution-dialog"
    const val SHEET_DRAG_HANDLE = "sheet-drag-handle"
    const val HIKING_ROUTES_TOGGLE = "hiking-routes-toggle"
    const val MAP_ZOOM_IN_BUTTON = "map-zoom-in-button"
    const val MAP_ZOOM_OUT_BUTTON = "map-zoom-out-button"
    const val MAP_RESET_NORTH_BUTTON = "map-reset-north-button"
    const val SETTINGS_BUTTON = "settings-button"
    const val SETTINGS_SCREEN = "settings-screen"
    const val SETTINGS_CLOSE = "settings-close"
    const val SETTINGS_SAVE_ERROR = "settings-save-error"
    const val SETTINGS_RETRY_SAVE = "settings-retry-save"
    const val DISTANCE_UNIT_METRIC = "distance-unit-metric"
    const val DISTANCE_UNIT_IMPERIAL = "distance-unit-imperial"
    const val AUTO_PAUSE_TOGGLE = "auto-pause-toggle"
    const val HIKE_REMINDERS_TOGGLE = "hike-reminders-toggle"
    const val LIVE_UPDATES_TOGGLE = "live-updates-toggle"
    const val KEEP_SCREEN_AWAKE_TOGGLE = "keep-screen-awake-toggle"
    const val AUTO_SAVE_TILES_TOGGLE = "auto-save-tiles-toggle"
    const val SAVE_PHOTOS_TO_LIBRARY_TOGGLE = "save-photos-to-library-toggle"
    const val PRIVACY_POLICY_LINK = "privacy-policy-link"
    const val TERMS_LINK = "terms-link"
    const val PROJECT_GITHUB_LINK = "project-github-link"
    const val APP_VERSION_ROW = "app-version-row"
    const val IMPORT_GPX_BUTTON = "import-gpx-button"
    const val HIKE_TITLE_FIELD = "hike-title-field"
    const val HIKE_LIST = "hike-list"
    const val HIKE_EMPTY_STATE = "hike-empty-state"
    const val HIKE_SEARCH_EMPTY_STATE = "hike-search-empty-state"
    const val HIKE_SEARCH_SECTION_HEADER = "hike-search-section-header"
    const val HIKE_DETAIL_SCREEN = "hike-detail-screen"
    const val HIKE_DETAIL_BACK = "hike-detail-back"
    const val HIKE_DETAIL_TITLE = "hike-detail-title"
    const val HIKE_RENAME_BUTTON = "hike-rename-button"
    const val HIKE_RENAME_SAVE_BUTTON = "hike-rename-save-button"
    const val HIKE_RENAME_CANCEL_BUTTON = "hike-rename-cancel-button"
    const val HIKE_DELETE_BUTTON = "hike-delete-button"
    const val HIKE_DELETE_DIALOG = "hike-delete-dialog"
    const val HIKE_DELETE_CONFIRM_BUTTON = "hike-delete-confirm-button"
    const val HIKE_DELETE_CANCEL_BUTTON = "hike-delete-cancel-button"
    const val HIKE_ZOOM_BUTTON = "hike-zoom-button"
    const val HIKE_EXPORT_GPX_BUTTON = "hike-export-gpx-button"
    const val HIKE_IMPORT_ERROR = "hike-import-error"
    const val HIKE_ACTION_ERROR = "hike-action-error"
    const val ELEVATION_CHART = "elevation-chart"
    const val ELEVATION_READOUT = "elevation-readout"
    const val ROUTE_STYLE_ROW = "route-style-row"
    const val ROUTE_STYLE_SCREEN = "route-style-screen"
    const val ROUTE_STYLE_BACK = "route-style-back"
    const val ROUTE_WIDTH_SLIDER = "route-width-slider"
    const val ROUTE_PATTERN_CAPTION = "route-pattern-caption"
    const val RECORDING_PHASE = "recording-phase"
    const val RECORDING_POINT_COUNT = "recording-point-count"
    const val OFFLINE_MAPS_SEARCH = "offline-maps-search"

    fun offlineCountry(countryCode: String): String = "offline-country-$countryCode"

    fun hikeRow(hikeId: String): String = "hike-row-$hikeId"

    fun hikeRowDelete(hikeId: String): String = "hike-row-delete-$hikeId"

    fun routePattern(patternId: String): String = "route-pattern-$patternId"

    fun routeColorSwatch(hex: String): String = "route-color-${hex.trimStart('#').lowercase()}"

    fun routeBorderSwatch(hex: String): String = if (hex.isBlank()) {
        "route-border-default"
    } else {
        "route-border-${hex.trimStart('#').lowercase()}"
    }
}
