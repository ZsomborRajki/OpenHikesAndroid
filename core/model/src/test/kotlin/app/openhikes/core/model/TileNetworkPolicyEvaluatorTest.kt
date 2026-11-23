package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileNetworkPolicyEvaluatorTest {
    @Test
    fun interactiveAllowedOnCellularAndThermalButBlockedOfflineOrUnderDataSaver() {
        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.INTERACTIVE,
                TileNetworkConditions(
                    isOnline = true,
                    isMetered = true,
                    isDataSaverEnabled = false,
                    isPowerSaveMode = true,
                    thermalSeverity = ThermalSeverity.SEVERE
                )
            )
        ).isEqualTo(TileNetworkDecision.Allowed)

        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.INTERACTIVE,
                TileNetworkConditions(isOnline = true, isMetered = true, isDataSaverEnabled = true)
            )
        ).isEqualTo(TileNetworkDecision.Denied(TileNetworkDenialReason.LOW_DATA_MODE))

        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.INTERACTIVE,
                TileNetworkConditions(isOnline = true),
                simulatesOffline = true
            )
        ).isEqualTo(TileNetworkDecision.Denied(TileNetworkDenialReason.OFFLINE))
    }

    @Test
    fun speculativeBlockedByBatterySaverSevereThermalOrMeteredNetwork() {
        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.SPECULATIVE,
                TileNetworkConditions(isOnline = true, isPowerSaveMode = true)
            )
        ).isEqualTo(TileNetworkDecision.Denied(TileNetworkDenialReason.LOW_POWER_MODE))

        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.SPECULATIVE,
                TileNetworkConditions(isOnline = true, thermalSeverity = ThermalSeverity.LIGHT)
            )
        ).isEqualTo(TileNetworkDecision.Allowed)

        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.SPECULATIVE,
                TileNetworkConditions(isOnline = true, thermalSeverity = ThermalSeverity.SEVERE)
            )
        ).isEqualTo(TileNetworkDecision.Denied(TileNetworkDenialReason.THERMAL))

        assertThat(
            TileNetworkPolicyEvaluator.evaluate(
                TileNetworkPurpose.SPECULATIVE,
                TileNetworkConditions(isOnline = true, isMetered = true)
            )
        ).isEqualTo(TileNetworkDecision.Denied(TileNetworkDenialReason.CELLULAR_SPECULATIVE))
    }
}
