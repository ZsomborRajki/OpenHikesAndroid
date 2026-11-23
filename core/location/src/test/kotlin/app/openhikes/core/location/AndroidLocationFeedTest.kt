package app.openhikes.core.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingEnergyPolicy
import app.openhikes.core.model.RecordingEnergyProfile
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidLocationFeedTest {
    private lateinit var context: Context
    private lateinit var shadowLocationManager: ShadowLocationManager
    private lateinit var feed: AndroidLocationFeed

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        context = app
        val manager = context.getSystemService(LocationManager::class.java)
        shadowLocationManager = Shadows.shadowOf(manager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        feed = AndroidLocationFeed(context)
    }

    @Test
    fun activeProfilesDeliverTimedStationarySamplesWhileAutoPausedAppliesDistanceFilter() = runTest {
        val collected = mutableListOf<LocationFix>()
        val collectJob = feed.fixes
            .onEach { collected.add(it) }
            .launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        try {
            feed.startUpdates(RecordingEnergyProfile.Precise)
            shadowLocationManager.simulateLocation(locationAt(47.71842, 12.83177, 1_000L))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            shadowLocationManager.simulateLocation(locationAt(47.71842, 12.83177, 3_000L))
            ShadowLooper.idleMainLooper()
            assertThat(collected).hasSize(2)

            val autoPaused = RecordingEnergyPolicy.profile(RecordingEnergyPolicy.Conditions(isAutoPaused = true))
            feed.updateProfile(autoPaused)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            shadowLocationManager.simulateLocation(locationAt(47.71842, 12.83177, 9_000L))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
            shadowLocationManager.simulateLocation(locationAt(47.71842, 12.83177, 15_000L))
            ShadowLooper.idleMainLooper()
            assertThat(collected).hasSize(3)
        } finally {
            feed.stopUpdates()
            collectJob.cancel()
        }
    }

    private fun locationAt(lat: Double, lon: Double, timeMillis: Long): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            time = timeMillis
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 4f
            altitude = 540.0
            speed = 0f
        }
}
