package app.openhikes.core.common

import app.openhikes.core.model.GeoCoordinate

/**
 * Launch configuration injected at the composition root.
 *
 * Production builds always use [LaunchConfig.Production]. Debug builds and test harnesses can
 * supply deterministic overrides for offline mode, isolated stores, and initial map framing.
 */
data class LaunchConfig(
    val isUiTesting: Boolean = false,
    val startsWithExpandedSheet: Boolean = false,
    val useIsolatedDataStore: Boolean = false,
    val useInMemoryDatabase: Boolean = false,
    val simulatesOffline: Boolean = false,
    val mapStart: GeoCoordinate? = null,
    val importedGpxFixtureName: String? = null,
    val offlineCountriesScenarioName: String? = null,
    val failsFirstSave: Boolean = false,
    val refusesHikeDeletion: Boolean = false,
    val enableSimulatedLocation: Boolean = false,
    val autoPauseSecondsOverride: Double? = null,
    val disableAutomaticRecovery: Boolean = false,
    val seedWalkPresetName: String? = null,
    val trailGraphFixtureName: String? = null,
    val trailPointsScenarioName: String? = null,
    val seedPhotoCount: Int = 0,
    val omitSeededPhotoFiles: Boolean = false,
    val stubPhotoLibraryCount: Int? = null,
    val stubPhotoLibraryAccess: String? = null
) {
    companion object {
        val Production = LaunchConfig()
    }
}
