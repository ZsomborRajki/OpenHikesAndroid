package app.openhikes.core.map

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidMapNetworkPolicyTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + testDispatcher)

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun reactsLiveToDataSaverBroadcastAndLaunchConfigOfflineChanges() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val shadowConnectivity = shadowOf(connectivityManager)
        val activeNetwork = requireNotNull(connectivityManager.activeNetwork)

        val meteredCaps = ShadowNetworkCapabilities.newInstance()
        val shadowCaps = shadowOf(meteredCaps)
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCaps.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowConnectivity.setNetworkCapabilities(activeNetwork, meteredCaps)
        shadowConnectivity.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)

        val launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
        val policy = AndroidMapNetworkPolicy(
            context = context,
            launchConfigHolder = launchConfigHolder,
            appScope = appScope
        )

        policy.allowsInteractiveMapRequests.test {
            assertThat(awaitItem()).isTrue()

            // Enable Android Data Saver on metered connection and broadcast change
            shadowConnectivity.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitItem()).isFalse()

            // Disable Data Saver -> remote requests resume
            shadowConnectivity.setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(awaitItem()).isTrue()

            // Debug offline launch override blocks remote requests even when network is available
            launchConfigHolder.mergeWith { it.copy(isUiTesting = true, simulatesOffline = true) }
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
