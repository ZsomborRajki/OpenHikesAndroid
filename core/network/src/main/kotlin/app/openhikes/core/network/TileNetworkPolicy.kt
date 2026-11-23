package app.openhikes.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.model.TileNetworkConditions
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkPolicyEvaluator
import app.openhikes.core.model.TileNetworkPurpose
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Unified network and power policy for interactive map tiles and speculative offline country downloads.
 */
interface TileNetworkPolicy {
    val conditions: StateFlow<TileNetworkConditions>
    val allowsInteractiveRequests: StateFlow<Boolean>

    fun decide(purpose: TileNetworkPurpose): TileNetworkDecision
}

@Singleton
internal class AndroidTileNetworkPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchConfigHolder: LaunchConfigHolder,
    @ApplicationScope appScope: CoroutineScope
) : TileNetworkPolicy {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)

    override val conditions: StateFlow<TileNetworkConditions> = observeSystemConditions()
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = evaluateSystemConditions()
        )

    override val allowsInteractiveRequests: StateFlow<Boolean> = combine(
        conditions,
        launchConfigHolder.current.map { it.simulatesOffline }.distinctUntilChanged()
    ) { snapshot, simulatesOffline ->
        TileNetworkPolicyEvaluator.evaluate(
            purpose = TileNetworkPurpose.INTERACTIVE,
            conditions = snapshot,
            simulatesOffline = simulatesOffline
        ).isAllowed
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = decide(TileNetworkPurpose.INTERACTIVE).isAllowed
        )

    override fun decide(purpose: TileNetworkPurpose): TileNetworkDecision = TileNetworkPolicyEvaluator.evaluate(
        purpose = purpose,
        conditions = evaluateSystemConditions(),
        simulatesOffline = launchConfigHolder.current.value.simulatesOffline
    )

    private fun observeSystemConditions(): Flow<TileNetworkConditions> = callbackFlow {
        val emitCurrent: () -> Unit = { trySend(evaluateSystemConditions()) }
        val networkCallback = createNetworkCallback(emitCurrent)
        val broadcastReceiver = createSystemBroadcastReceiver(emitCurrent)
        val thermalListener = PowerManager.OnThermalStatusChangedListener { emitCurrent() }

        val registeredNetwork = runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback) != null
        }.getOrDefault(false)

        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, broadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        powerManager?.addThermalStatusListener(thermalListener)

        emitCurrent()

        awaitClose {
            if (registeredNetwork) {
                runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            }
            runCatching { context.unregisterReceiver(broadcastReceiver) }
            runCatching { powerManager?.removeThermalStatusListener(thermalListener) }
        }
    }
        .onStart { emit(evaluateSystemConditions()) }
        .distinctUntilChanged()

    private fun createNetworkCallback(onChange: () -> Unit): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
        }

    private fun createSystemBroadcastReceiver(onChange: () -> Unit): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = onChange()
    }

    private fun evaluateSystemConditions(): TileNetworkConditions {
        val cm = connectivityManager
        val activeNetwork = cm?.activeNetwork
        val capabilities = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val isOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isMetered = if (cm != null && capabilities != null) {
            cm.isActiveNetworkMetered || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } else {
            false
        }
        val isDataSaver = cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        val pm = powerManager
        val isPowerSave = pm?.isPowerSaveMode == true
        val thermalSeverity = mapThermalSeverity(pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE)
        return TileNetworkConditions(
            isOnline = isOnline,
            isMetered = isMetered,
            isDataSaverEnabled = isDataSaver,
            isPowerSaveMode = isPowerSave,
            thermalSeverity = thermalSeverity
        )
    }

    private fun mapThermalSeverity(status: Int): ThermalSeverity = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.CRITICAL
        else -> ThermalSeverity.NOMINAL
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
