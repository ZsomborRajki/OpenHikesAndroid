package app.openhikes.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchConfigTest {
    @Test
    fun productionDefaultsDisableAllTestOverrides() {
        val config = LaunchConfig.Production

        assertThat(config.isUiTesting).isFalse()
        assertThat(config.startsWithExpandedSheet).isFalse()
        assertThat(config.useIsolatedDataStore).isFalse()
        assertThat(config.useInMemoryDatabase).isFalse()
        assertThat(config.simulatesOffline).isFalse()
        assertThat(config.mapStart).isNull()
        assertThat(config.importedGpxFixtureName).isNull()
        assertThat(config.offlineCountriesScenarioName).isNull()
        assertThat(config.failsFirstSave).isFalse()
        assertThat(config.refusesHikeDeletion).isFalse()
    }
}
