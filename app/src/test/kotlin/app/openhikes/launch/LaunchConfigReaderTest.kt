package app.openhikes.launch

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.model.GeoCoordinate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchConfigReaderTest {
    @Test
    fun ignoresFlagsWhenUiTestingFlagIsAbsent() {
        val config = LaunchConfigReader.fromArguments(
            mapOf(
                LaunchConfigReader.EXTRA_EXPANDED_SHEET to "true",
                LaunchConfigReader.EXTRA_OFFLINE to "true",
                LaunchConfigReader.EXTRA_IMPORT_GPX to "ThumseeLoopFast"
            )
        )

        assertThat(config).isEqualTo(LaunchConfig.Production)
    }

    @Test
    fun parsesValidOverridesAndRejectsInvalidCoordinatesOrPathTraversal() {
        val config = LaunchConfigReader.fromArguments(
            mapOf(
                LaunchConfigReader.EXTRA_UI_TESTING to "true",
                LaunchConfigReader.EXTRA_EXPANDED_SHEET to "true",
                LaunchConfigReader.EXTRA_ISOLATED_DATASTORE to "true",
                LaunchConfigReader.EXTRA_OFFLINE to "true",
                LaunchConfigReader.EXTRA_MAP_START to "47.7215, 12.8250",
                LaunchConfigReader.EXTRA_IMPORT_GPX to "ThumseeLoopFast",
                LaunchConfigReader.EXTRA_OFFLINE_COUNTRIES to "../escape"
            )
        )

        assertThat(config.isUiTesting).isTrue()
        assertThat(config.startsWithExpandedSheet).isTrue()
        assertThat(config.useIsolatedDataStore).isTrue()
        assertThat(config.simulatesOffline).isTrue()
        assertThat(config.mapStart).isEqualTo(GeoCoordinate(47.7215, 12.8250))
        assertThat(config.importedGpxFixtureName).isEqualTo("ThumseeLoopFast")
        assertThat(config.offlineCountriesScenarioName).isNull()

        val invalidCoords = LaunchConfigReader.fromArguments(
            mapOf(
                LaunchConfigReader.EXTRA_UI_TESTING to "true",
                LaunchConfigReader.EXTRA_MAP_START to "95.0,12.0"
            )
        )
        assertThat(invalidCoords.mapStart).isNull()
    }
}
