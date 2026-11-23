package app.openhikes.core.model

/**
 * Distinguishes user-facing map viewing from background/offline downloads when evaluating network rules.
 */
enum class TileNetworkPurpose {
    INTERACTIVE,
    SPECULATIVE
}

/**
 * Current device connectivity and power snapshot evaluated by [TileNetworkPolicyEvaluator].
 */
data class TileNetworkConditions(
    val isOnline: Boolean = true,
    val isMetered: Boolean = false,
    val isDataSaverEnabled: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL
)

/**
 * Reason why a map or offline-country network request is currently disallowed.
 */
enum class TileNetworkDenialReason(val wireName: String) {
    OFFLINE("offline"),
    LOW_DATA_MODE("low-data-mode"),
    LOW_POWER_MODE("low-power-mode"),
    THERMAL("thermal"),
    CELLULAR_SPECULATIVE("cellular-speculative")
}

/**
 * Outcome of evaluating [TileNetworkPolicyEvaluator] for a given [TileNetworkPurpose].
 */
sealed interface TileNetworkDecision {
    val isAllowed: Boolean

    data object Allowed : TileNetworkDecision {
        override val isAllowed: Boolean = true
    }

    data class Denied(val reason: TileNetworkDenialReason) : TileNetworkDecision {
        override val isAllowed: Boolean = false
    }
}

/**
 * Pure policy evaluator governing interactive map requests and speculative offline country downloads.
 *
 * Interactive requests are permitted on cellular unless offline or Data Saver is active on a metered
 * network. Speculative downloads additionally pause on metered connections, Battery Saver, or
 * severe/critical thermal pressure.
 */
object TileNetworkPolicyEvaluator {
    fun evaluate(
        purpose: TileNetworkPurpose,
        conditions: TileNetworkConditions,
        simulatesOffline: Boolean = false
    ): TileNetworkDecision = when {
        simulatesOffline || !conditions.isOnline ->
            TileNetworkDecision.Denied(TileNetworkDenialReason.OFFLINE)

        conditions.isDataSaverEnabled && conditions.isMetered ->
            TileNetworkDecision.Denied(TileNetworkDenialReason.LOW_DATA_MODE)

        purpose == TileNetworkPurpose.INTERACTIVE -> TileNetworkDecision.Allowed

        else -> evaluateSpeculative(conditions)
    }

    private fun evaluateSpeculative(conditions: TileNetworkConditions): TileNetworkDecision = when {
        conditions.isPowerSaveMode -> TileNetworkDecision.Denied(TileNetworkDenialReason.LOW_POWER_MODE)
        isThermallyConserving(conditions.thermalSeverity) ->
            TileNetworkDecision.Denied(TileNetworkDenialReason.THERMAL)
        conditions.isMetered -> TileNetworkDecision.Denied(TileNetworkDenialReason.CELLULAR_SPECULATIVE)
        else -> TileNetworkDecision.Allowed
    }

    fun isThermallyConserving(severity: ThermalSeverity): Boolean =
        severity == ThermalSeverity.SEVERE || severity == ThermalSeverity.CRITICAL
}
