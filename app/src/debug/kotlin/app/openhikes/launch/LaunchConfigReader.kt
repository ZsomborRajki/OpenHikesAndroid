package app.openhikes.launch

import android.content.Intent
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.model.GeoCoordinate

/**
 * Debug-only parser for test harness launch overrides supplied via [Intent] extras or key-value maps.
 */
object LaunchConfigReader {
    const val EXTRA_UI_TESTING = "ui_testing"
    const val EXTRA_EXPANDED_SHEET = "ui_test_expanded_sheet"
    const val EXTRA_ISOLATED_DATASTORE = "ui_test_isolated_datastore"
    const val EXTRA_IN_MEMORY_DB = "ui_test_in_memory_db"
    const val EXTRA_OFFLINE = "ui_test_offline"
    const val EXTRA_MAP_START = "ui_test_map_start"
    const val EXTRA_IMPORT_GPX = "ui_test_import_gpx"
    const val EXTRA_OFFLINE_COUNTRIES = "ui_test_offline_countries"
    const val EXTRA_FAIL_FIRST_SAVE = "ui_test_fail_first_save"
    const val EXTRA_REFUSE_HIKE_DELETION = "ui_test_refuse_hike_deletion"
    const val EXTRA_ENABLE_LOCATION = "ui_test_enable_location"
    const val EXTRA_AUTO_PAUSE_SECONDS = "ui_test_auto_pause_seconds"
    const val EXTRA_DISABLE_AUTO_RECOVERY = "ui_test_disable_auto_recovery"

    fun fromIntent(intent: Intent?): LaunchConfig {
        val extras = intent?.extras ?: return LaunchConfig.Production
        val map = HashMap<String, String?>()
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            map[key] = extras.get(key)?.toString()
        }
        return fromArguments(map)
    }

    fun fromArguments(arguments: Map<String, String?>): LaunchConfig {
        val isUiTesting = arguments[EXTRA_UI_TESTING]?.toBooleanStrictOrNull() == true
        if (!isUiTesting) return LaunchConfig.Production

        return LaunchConfig(
            isUiTesting = true,
            startsWithExpandedSheet = arguments.boolFlag(EXTRA_EXPANDED_SHEET),
            useIsolatedDataStore = arguments.boolFlag(EXTRA_ISOLATED_DATASTORE),
            useInMemoryDatabase = arguments.boolFlag(EXTRA_IN_MEMORY_DB),
            simulatesOffline = arguments.boolFlag(EXTRA_OFFLINE),
            mapStart = parseMapStart(arguments[EXTRA_MAP_START]),
            importedGpxFixtureName = sanitizeFixtureName(arguments[EXTRA_IMPORT_GPX]),
            offlineCountriesScenarioName = sanitizeFixtureName(arguments[EXTRA_OFFLINE_COUNTRIES]),
            failsFirstSave = arguments.boolFlag(EXTRA_FAIL_FIRST_SAVE),
            refusesHikeDeletion = arguments.boolFlag(EXTRA_REFUSE_HIKE_DELETION),
            enableSimulatedLocation = arguments.boolFlag(EXTRA_ENABLE_LOCATION),
            autoPauseSecondsOverride = parsePositiveSeconds(arguments[EXTRA_AUTO_PAUSE_SECONDS]),
            disableAutomaticRecovery = arguments.boolFlag(EXTRA_DISABLE_AUTO_RECOVERY)
        )
    }

    fun merge(base: LaunchConfig, intent: Intent?): LaunchConfig {
        val fromIntent = fromIntent(intent)
        if (!fromIntent.isUiTesting) return base
        return LaunchConfig(
            isUiTesting = true,
            startsWithExpandedSheet = base.startsWithExpandedSheet || fromIntent.startsWithExpandedSheet,
            useIsolatedDataStore = base.useIsolatedDataStore || fromIntent.useIsolatedDataStore,
            useInMemoryDatabase = base.useInMemoryDatabase || fromIntent.useInMemoryDatabase,
            simulatesOffline = base.simulatesOffline || fromIntent.simulatesOffline,
            mapStart = fromIntent.mapStart ?: base.mapStart,
            importedGpxFixtureName = fromIntent.importedGpxFixtureName ?: base.importedGpxFixtureName,
            offlineCountriesScenarioName =
            fromIntent.offlineCountriesScenarioName ?: base.offlineCountriesScenarioName,
            failsFirstSave = base.failsFirstSave || fromIntent.failsFirstSave,
            refusesHikeDeletion = base.refusesHikeDeletion || fromIntent.refusesHikeDeletion,
            enableSimulatedLocation = base.enableSimulatedLocation || fromIntent.enableSimulatedLocation,
            autoPauseSecondsOverride = fromIntent.autoPauseSecondsOverride ?: base.autoPauseSecondsOverride,
            disableAutomaticRecovery = base.disableAutomaticRecovery || fromIntent.disableAutomaticRecovery
        )
    }

    private fun parsePositiveSeconds(raw: String?): Double? = raw?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }

    private fun Map<String, String?>.boolFlag(key: String): Boolean = this[key]?.toBooleanStrictOrNull() == true

    private fun parseMapStart(raw: String?): GeoCoordinate? = raw?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.split(',')
        ?.takeIf { it.size == 2 }
        ?.let { parts ->
            val latitude = parts[0].trim().toDoubleOrNull()
            val longitude = parts[1].trim().toDoubleOrNull()
            if (latitude != null && longitude != null) {
                runCatching { GeoCoordinate(latitude = latitude, longitude = longitude) }.getOrNull()
            } else {
                null
            }
        }

    private fun sanitizeFixtureName(raw: String?): String? = raw?.trim()?.takeIf { value ->
        value.isNotEmpty() && !value.contains('/') && !value.contains('\\') && !value.contains("..")
    }
}
