package app.openhikes.launch

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.openhikes.MainActivity
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapNetworkPolicy
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h891dp-port")
class MainActivityIntentLaunchTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun intentLaunchOverridesPropagateToCameraOfflinePolicyAndIsolatedDataStore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val entryPoint = EntryPointAccessors.fromApplication(
            context,
            IntentLaunchTestEntryPoint::class.java
        )
        // Reset base config to Production before launching MainActivity so all overrides come from the Intent.
        entryPoint.launchConfigHolder().mergeWith { LaunchConfig.Production }
        assertThat(entryPoint.launchConfigHolder().current.value).isEqualTo(LaunchConfig.Production)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, true)
            putExtra(LaunchConfigReader.EXTRA_MAP_START, "45.0,20.0")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, true)
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, true)
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.TRAIL_MAP))

            val renderer = entryPoint.trailMapRenderer() as FakeTrailMapRenderer
            assertThat(renderer.lastCameraState.value.center)
                .isEqualTo(GeoCoordinate(latitude = 45.0, longitude = 20.0))

            val merged = entryPoint.launchConfigHolder().current.value
            assertThat(merged.simulatesOffline).isTrue()
            assertThat(merged.useIsolatedDataStore).isTrue()
            assertThat(merged.startsWithExpandedSheet).isTrue()

            entryPoint.mapNetworkPolicy().allowsInteractiveMapRequests.test {
                assertThat(awaitItem()).isFalse()
                cancelAndIgnoreRemainingEvents()
            }

            entryPoint.userSettingsRepository().setAutoSaveTilesEnabled(true)
            val isolatedDir = File(context.cacheDir, "openhikes-test-datastore")
            val isolatedFiles = isolatedDir.listFiles().orEmpty()
            assertThat(isolatedFiles.any { it.name.startsWith("user_preferences_") && it.name.endsWith(".pb") })
                .isTrue()
            assertThat(File(context.filesDir, "datastore/user_preferences.pb").exists()).isFalse()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface IntentLaunchTestEntryPoint {
        fun launchConfigHolder(): LaunchConfigHolder
        fun trailMapRenderer(): TrailMapRenderer
        fun mapNetworkPolicy(): MapNetworkPolicy
        fun userSettingsRepository(): UserSettingsRepository
    }
}
