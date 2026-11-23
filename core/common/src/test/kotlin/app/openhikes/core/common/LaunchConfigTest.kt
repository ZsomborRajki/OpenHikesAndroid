package app.openhikes.core.common

import app.openhikes.core.model.GeoCoordinate
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
        assertThat(config.enableSimulatedLocation).isFalse()
        assertThat(config.autoPauseSecondsOverride).isNull()
        assertThat(config.disableAutomaticRecovery).isFalse()
    }

    @Test
    fun holderPublishesInitialAndMergedConfigurations() {
        val holder = LaunchConfigHolder(LaunchConfig.Production)
        assertThat(holder.current.value).isEqualTo(LaunchConfig.Production)

        holder.mergeWith { base ->
            base.copy(
                isUiTesting = true,
                simulatesOffline = true,
                mapStart = GeoCoordinate(latitude = 45.0, longitude = 20.0)
            )
        }

        assertThat(holder.current.value.isUiTesting).isTrue()
        assertThat(holder.current.value.simulatesOffline).isTrue()
        assertThat(holder.current.value.mapStart).isEqualTo(GeoCoordinate(45.0, 20.0))
    }
}
