package app.openhikes.core.model

import kotlin.math.max

/**
 * Device thermal pressure coarsened to platform-agnostic severity levels.
 */
enum class ThermalSeverity {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL
}

/**
 * Reason the active recording location request stepped back from [RecordingEnergyProfile.Precise].
 */
enum class RecordingEnergyReason {
    AUTO_PAUSED,
    LOW_POWER_AND_HOT,
    LOW_POWER,
    THERMAL,
    STATIONARY,
    PAUSED_WATCH
}

/**
 * Location request accuracy, minimum displacement filter, and update interval for a recording phase.
 */
data class RecordingEnergyProfile(
    val name: String,
    val desiredAccuracyMeters: Double,
    val distanceFilterMeters: Double,
    val updateIntervalMillis: Long,
    val reason: RecordingEnergyReason? = null
) {
    companion object {
        private const val BEST_ACCURACY_METERS = 3.0
        private const val WALKING_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val PAUSED_WATCH_ACCURACY_METERS = 100.0
        private const val PAUSED_WATCH_DISTANCE_FILTER_METERS = 100.0
        private const val PAUSED_WATCH_INTERVAL_MILLIS = 30_000L

        val Precise = RecordingEnergyProfile(
            name = "precise",
            desiredAccuracyMeters = BEST_ACCURACY_METERS,
            distanceFilterMeters = RecordingEnergyPolicy.WALKING_DISTANCE_FILTER_METERS,
            updateIntervalMillis = WALKING_UPDATE_INTERVAL_MILLIS,
            reason = null
        )

        val PausedWatch = RecordingEnergyProfile(
            name = "paused-watch",
            desiredAccuracyMeters = PAUSED_WATCH_ACCURACY_METERS,
            distanceFilterMeters = PAUSED_WATCH_DISTANCE_FILTER_METERS,
            updateIntervalMillis = PAUSED_WATCH_INTERVAL_MILLIS,
            reason = RecordingEnergyReason.PAUSED_WATCH
        )
    }
}

/**
 * Computes GPS accuracy and distance filter trade-offs from Battery Saver, thermal state, stillness,
 * and auto-pause status.
 */
object RecordingEnergyPolicy {
    const val WALKING_DISTANCE_FILTER_METERS = 10.0
    const val STATIONARY_DISTANCE_FILTER_METERS = 25.0
    const val CONSERVING_ACCURACY_METERS = 10.0
    const val CONSERVING_DISTANCE_FILTER_METERS = 20.0
    const val BEST_ACCURACY_METERS = 3.0

    private const val WALKING_UPDATE_INTERVAL_MILLIS = 1_000L
    private const val CONSERVING_UPDATE_INTERVAL_MILLIS = 3_000L
    private const val STATIONARY_UPDATE_INTERVAL_MILLIS = 5_000L

    data class Conditions(
        val isLowPowerModeEnabled: Boolean = false,
        val thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL,
        val isStationary: Boolean = false,
        val isAutoPaused: Boolean = false
    )

    fun conserves(thermalSeverity: ThermalSeverity): Boolean =
        thermalSeverity == ThermalSeverity.SEVERE || thermalSeverity == ThermalSeverity.CRITICAL

    fun profile(conditions: Conditions): RecordingEnergyProfile {
        val lowPower = conditions.isLowPowerModeEnabled
        val hot = conserves(conditions.thermalSeverity)
        val conserving = lowPower || hot
        val standsStill = conditions.isStationary || conditions.isAutoPaused

        val accuracy = if (conserving) CONSERVING_ACCURACY_METERS else BEST_ACCURACY_METERS
        val filter = max(
            if (conserving) CONSERVING_DISTANCE_FILTER_METERS else WALKING_DISTANCE_FILTER_METERS,
            if (standsStill) STATIONARY_DISTANCE_FILTER_METERS else 0.0
        )
        val interval = maxOf(
            if (conserving) CONSERVING_UPDATE_INTERVAL_MILLIS else WALKING_UPDATE_INTERVAL_MILLIS,
            if (standsStill) STATIONARY_UPDATE_INTERVAL_MILLIS else 0L
        )
        val components = buildList {
            if (lowPower) add("low-power")
            if (hot) add("thermal")
            if (conditions.isAutoPaused) {
                add("auto-paused")
            } else if (conditions.isStationary) {
                add("stationary")
            }
        }
        val name = if (components.isEmpty()) "precise" else components.joinToString(separator = "+")
        return RecordingEnergyProfile(
            name = name,
            desiredAccuracyMeters = accuracy,
            distanceFilterMeters = filter,
            updateIntervalMillis = interval,
            reason = resolveReason(lowPower, hot, conditions.isStationary, conditions.isAutoPaused)
        )
    }

    private fun resolveReason(
        lowPower: Boolean,
        hot: Boolean,
        stationary: Boolean,
        autoPaused: Boolean
    ): RecordingEnergyReason? = when {
        autoPaused -> RecordingEnergyReason.AUTO_PAUSED
        lowPower && hot -> RecordingEnergyReason.LOW_POWER_AND_HOT
        lowPower -> RecordingEnergyReason.LOW_POWER
        hot -> RecordingEnergyReason.THERMAL
        stationary -> RecordingEnergyReason.STATIONARY
        else -> null
    }
}
