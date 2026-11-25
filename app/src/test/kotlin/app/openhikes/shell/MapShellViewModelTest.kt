package app.openhikes.shell

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapShellViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun recordingRecoveryProceedsWithoutWaitingForOfflineMapStartupVerification() = runTest {
        val gate = CompletableDeferred<Unit>()
        val offlineRepo = FakeOfflineCountryRepository(OfflineCountriesState(isPrepared = false)).apply {
            startGate = gate
        }
        val recordingController = FakeRecordingController()
        val walkController = FakeTrailWalkController()

        MapShellViewModel(
            userSettingsRepository = FakeUserSettingsRepository(),
            hikeRepository = FakeHikeRepository(),
            offlineCountryRepository = offlineRepo,
            mapClaimCoordinator = MapClaimCoordinator(),
            recordingController = recordingController,
            trailWalkController = walkController,
            routeHighlightState = RouteHighlightState(),
            walkOverlayState = WalkOverlayState(),
            mapProviderKeys = MapProviderKeys.Empty,
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        assertThat(recordingController.recoverCount).isEqualTo(1)
        assertThat(walkController.restoreCalls).isEqualTo(1)
        assertThat(offlineRepo.state.value.isPrepared).isFalse()

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(offlineRepo.state.value.isPrepared).isTrue()
    }
}
