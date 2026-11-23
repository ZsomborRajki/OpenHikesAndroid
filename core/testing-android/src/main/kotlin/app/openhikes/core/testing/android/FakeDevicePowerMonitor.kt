package app.openhikes.core.testing.android

import app.openhikes.core.location.DevicePowerMonitor
import app.openhikes.core.location.DevicePowerState
import app.openhikes.core.model.ThermalSeverity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [DevicePowerMonitor] fake simulating Battery Saver and thermal throttling transitions.
 */
@Singleton
class FakeDevicePowerMonitor @Inject constructor() : DevicePowerMonitor {
    private val state = MutableStateFlow(DevicePowerState())

    override val powerState: Flow<DevicePowerState> = state.asStateFlow()

    override fun currentState(): DevicePowerState = state.value

    fun setPowerState(isPowerSaveMode: Boolean = false, thermalSeverity: ThermalSeverity = ThermalSeverity.NOMINAL) {
        state.value = DevicePowerState(
            isPowerSaveMode = isPowerSaveMode,
            thermalSeverity = thermalSeverity
        )
    }
}
