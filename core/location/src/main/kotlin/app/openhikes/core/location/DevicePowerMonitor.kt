package app.openhikes.core.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.openhikes.core.model.ThermalSeverity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Snapshot of device Battery Saver and thermal throttling state read by recording energy policy.
 */
data class DevicePowerState(
    val isPowerSaveMode: Boolean = false,
    val thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL
)

/**
 * Observes OS Battery Saver and thermal throttling changes for adaptive GPS accuracy/filter selection.
 */
interface DevicePowerMonitor {
    val powerState: Flow<DevicePowerState>

    fun currentState(): DevicePowerState
}

@Singleton
internal class AndroidDevicePowerMonitor @Inject constructor(@ApplicationContext context: Context) :
    DevicePowerMonitor {
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override val powerState: Flow<DevicePowerState> = callbackFlow {
        trySend(currentState())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(currentState())
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val thermalListener = PowerManager.OnThermalStatusChangedListener {
            trySend(currentState())
        }
        powerManager?.addThermalStatusListener(thermalListener)

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            powerManager?.removeThermalStatusListener(thermalListener)
        }
    }.distinctUntilChanged()

    override fun currentState(): DevicePowerState {
        val manager = powerManager ?: return DevicePowerState()
        val severity = when (manager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
            else -> ThermalSeverity.NOMINAL
        }
        return DevicePowerState(
            isPowerSaveMode = manager.isPowerSaveMode,
            thermalSeverity = severity
        )
    }
}
