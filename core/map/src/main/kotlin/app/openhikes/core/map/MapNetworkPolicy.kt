package app.openhikes.core.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.LaunchConfigHolder
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
 * Policy governing interactive foreground map style/tile requests.
 */
interface MapNetworkPolicy {
    /**
     * `true` when foreground interactive map requests are permitted by connectivity, Data Saver,
     * and launch overrides.
     */
    val allowsInteractiveMapRequests: StateFlow<Boolean>
}

internal fun shouldAllowInteractiveMapRequests(
    hasActiveNetwork: Boolean,
    isMetered: Boolean,
    restrictBackgroundStatus: Int,
    simulatesOffline: Boolean
): Boolean = !simulatesOffline &&
    hasActiveNetwork &&
    !(isMetered && restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

@Singleton
internal class AndroidMapNetworkPolicy @Inject constructor(
    @ApplicationContext private val context: Context,
    launchConfigHolder: LaunchConfigHolder,
    @ApplicationScope appScope: CoroutineScope
) : MapNetworkPolicy {
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    override val allowsInteractiveMapRequests: StateFlow<Boolean> = combine(
        observeSystemAllowsInteractiveRequests(),
        launchConfigHolder.current.map { it.simulatesOffline }.distinctUntilChanged()
    ) { systemAllowed, simulatesOffline ->
        systemAllowed && !simulatesOffline
    }
        .distinctUntilChanged()
        .stateIn(
            scope = appScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = evaluateSystemAllowsInteractiveRequests() &&
                !launchConfigHolder.current.value.simulatesOffline
        )

    private fun observeSystemAllowsInteractiveRequests(): Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            awaitClose {}
            return@callbackFlow
        }

        val emitCurrent = { trySend(evaluateSystemAllowsInteractiveRequests()) }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                emitCurrent()
            }

            override fun onLost(network: Network) {
                emitCurrent()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                emitCurrent()
            }
        }

        val dataSaverReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                emitCurrent()
            }
        }

        val registeredCallback = runCatching {
            manager.registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrDefault(false)

        context.registerReceiver(
            dataSaverReceiver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )

        emitCurrent()

        awaitClose {
            if (registeredCallback) {
                runCatching { manager.unregisterNetworkCallback(networkCallback) }
            }
            runCatching { context.unregisterReceiver(dataSaverReceiver) }
        }
    }
        .onStart { emit(evaluateSystemAllowsInteractiveRequests()) }
        .distinctUntilChanged()

    private fun evaluateSystemAllowsInteractiveRequests(): Boolean {
        val manager = connectivityManager
        val activeNetwork = manager?.activeNetwork
        val capabilities = if (activeNetwork != null) manager.getNetworkCapabilities(activeNetwork) else null
        return if (manager != null && capabilities != null) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isMetered = manager.isActiveNetworkMetered ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = hasInternet,
                isMetered = isMetered,
                restrictBackgroundStatus = manager.restrictBackgroundStatus,
                simulatesOffline = false
            )
        } else {
            false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
