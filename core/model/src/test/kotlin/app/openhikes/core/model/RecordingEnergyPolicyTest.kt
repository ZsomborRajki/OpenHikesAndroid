package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingEnergyPolicyTest {
    @Test
    fun resolvesCombinedBatteryThermalStationaryAndAutoPausedProfiles() {
        val precise = RecordingEnergyPolicy.profile(RecordingEnergyPolicy.Conditions())
        assertThat(precise).isEqualTo(RecordingEnergyProfile.Precise)

        // Moderate thermal pressure does not downgrade accuracy
        val warm = RecordingEnergyPolicy.profile(
            RecordingEnergyPolicy.Conditions(thermalSeverity = ThermalSeverity.MODERATE)
        )
        assertThat(warm).isEqualTo(RecordingEnergyProfile.Precise)

        // Low power + severe thermal pressure + auto-paused prioritizes auto-paused notice and wide filter
        val degraded = RecordingEnergyPolicy.profile(
            RecordingEnergyPolicy.Conditions(
                isLowPowerModeEnabled = true,
                thermalSeverity = ThermalSeverity.SEVERE,
                isStationary = true,
                isAutoPaused = true
            )
        )
        assertThat(degraded.name).isEqualTo("low-power+thermal+auto-paused")
        assertThat(degraded.desiredAccuracyMeters).isEqualTo(RecordingEnergyPolicy.CONSERVING_ACCURACY_METERS)
        assertThat(degraded.distanceFilterMeters).isEqualTo(RecordingEnergyPolicy.STATIONARY_DISTANCE_FILTER_METERS)
        assertThat(degraded.reason).isEqualTo(RecordingEnergyReason.AUTO_PAUSED)
    }
}
