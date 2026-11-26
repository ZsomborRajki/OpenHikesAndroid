package app.openhikes.feature.recording

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.location.RecordingEntryCoordinator
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import app.openhikes.core.ui.navigation.RecordingRoute
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun consumesAutoStartClaimsMapTracksLiveCoordinateAndReleasesOnClear() = runTest {
        val controller = FakeRecordingController()
        val entryCoordinator = RecordingEntryCoordinator().apply { requestAutoStart() }
        val coordinator = MapClaimCoordinator()
        val viewModel = RecordingViewModel(
            recordingController = controller,
            recordingEntryCoordinator = entryCoordinator,
            hikeRepository = FakeHikeRepository(),
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = coordinator,
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig(isUiTesting = true, enableSimulatedLocation = true))
        )
        val store = ViewModelStore().apply { put("rec", viewModel) }

        advanceUntilIdle()
        assertThat(controller.startCount).isEqualTo(1)
        assertThat(entryCoordinator.pendingAutoStart.value).isFalse()
        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo(RecordingRoute)

        controller.emitTrace(
            RecordingTraceSnapshot(
                hikeId = HikeId("rec-1"),
                points = listOf(RouteCoordinate(latitude = 47.71842, longitude = 12.83177))
            )
        )
        advanceUntilIdle()
        val command = coordinator.pendingCameraCommand.value as? MapCameraCommand.MoveTo
        assertThat(command?.center).isEqualTo(GeoCoordinate(47.71842, 12.83177))

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
    }

    @Test
    fun permissionGatingRequestsRuntimePermissionOutsideSimulatedMode() = runTest {
        val controller = FakeRecordingController()
        val viewModel = RecordingViewModel(
            recordingController = controller,
            recordingEntryCoordinator = RecordingEntryCoordinator(),
            hikeRepository = FakeHikeRepository(),
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
        )

        viewModel.uiState.test {
            awaitItem()
            viewModel.onAction(RecordingAction.Start)
            advanceUntilIdle()
            val prompted = expectMostRecentItem()
            assertThat(prompted.requestLocationPermission).isTrue()
            assertThat(controller.startCount).isEqualTo(0)

            viewModel.onAction(RecordingAction.PermissionResult(grantedFine = false))
            advanceUntilIdle()
            val denied = expectMostRecentItem()
            assertThat(denied.requestLocationPermission).isFalse()
            assertThat(denied.session.failure).isEqualTo(RecordingFailure.LocationDenied)

            viewModel.onAction(RecordingAction.PermissionResult(grantedFine = true))
            advanceUntilIdle()
            assertThat(controller.state.value.phase).isEqualTo(RecordingPhase.WAITING_FOR_FIX)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stopFlowHandlesZeroPointDiscardAndPreselectedTitleSaveAndRetry() = runTest {
        val controller = FakeRecordingController()
        val viewModel = RecordingViewModel(
            recordingController = controller,
            recordingEntryCoordinator = RecordingEntryCoordinator(),
            hikeRepository = FakeHikeRepository(),
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig(isUiTesting = true, enableSimulatedLocation = true))
        )

        viewModel.uiState.test {
            awaitItem()
            viewModel.onAction(RecordingAction.Start)
            advanceUntilIdle()

            viewModel.onAction(RecordingAction.Stop)
            advanceUntilIdle()
            val discardedEmpty = expectMostRecentItem()
            assertThat(discardedEmpty.discardCompleted).isTrue()
            viewModel.consumeNavigation()

            viewModel.onAction(RecordingAction.Start)
            advanceUntilIdle()
            controller.emitStats(RecordingStatsSnapshot(distanceMeters = 120.0, pointCount = 2))
            controller.emitTrace(
                RecordingTraceSnapshot(
                    hikeId = HikeId(controller.state.value.sessionId!!),
                    points = listOf(
                        RouteCoordinate(47.71842, 12.83177),
                        RouteCoordinate(47.71920, 12.83090)
                    )
                )
            )

            viewModel.onAction(RecordingAction.Stop)
            advanceUntilIdle()
            val askName = expectMostRecentItem()
            assertThat(askName.stopDialogStep).isEqualTo(RecordingStopStep.ASK_NAME)
            assertThat(askName.stopNameDraft).isEqualTo(askName.offeredTitle)

            controller.nextStopFailure = IOException("Save failed")
            viewModel.onAction(RecordingAction.ConfirmStopSave)
            advanceUntilIdle()
            val failed = expectMostRecentItem()
            assertThat(failed.session.canRetrySave).isTrue()

            viewModel.onAction(RecordingAction.RetrySave)
            advanceUntilIdle()
            val saved = expectMostRecentItem()
            assertThat(saved.savedHikeId).isNotNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reopenedViewModelObservesConfirmedStopCompletingAfterBackNavigation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = FakeRecordingController(externalScope = backgroundScope).apply {
            stopGate = gate
        }
        val firstVm = RecordingViewModel(
            recordingController = controller,
            recordingEntryCoordinator = RecordingEntryCoordinator(),
            hikeRepository = FakeHikeRepository(),
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig(isUiTesting = true, enableSimulatedLocation = true))
        )
        val firstStore = ViewModelStore().apply { put("first", firstVm) }
        firstVm.onAction(RecordingAction.Start)
        advanceUntilIdle()
        val sessionId = checkNotNull(controller.state.value.sessionId)
        controller.emitStats(RecordingStatsSnapshot(distanceMeters = 120.0, pointCount = 2))
        controller.emitTrace(
            RecordingTraceSnapshot(
                hikeId = HikeId(sessionId),
                points = listOf(RouteCoordinate(47.71842, 12.83177), RouteCoordinate(47.71920, 12.83090))
            )
        )

        firstVm.onAction(RecordingAction.Stop)
        advanceUntilIdle()
        firstVm.onAction(RecordingAction.ConfirmStopSave)
        runCurrent()
        assertThat(controller.state.value.phase).isEqualTo(RecordingPhase.SAVING)

        // Pressing Back clears the first ViewModel while finalization is suspended on applicationScope.
        firstStore.clear()

        val secondVm = RecordingViewModel(
            recordingController = controller,
            recordingEntryCoordinator = RecordingEntryCoordinator(),
            hikeRepository = FakeHikeRepository(),
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig(isUiTesting = true, enableSimulatedLocation = true))
        )
        val secondStore = ViewModelStore().apply { put("second", secondVm) }
        secondVm.uiState.test {
            assertThat(expectMostRecentItem().session.phase).isEqualTo(RecordingPhase.SAVING)
            gate.complete(Unit)
            advanceUntilIdle()
            val settled = expectMostRecentItem()
            assertThat(settled.session.phase).isEqualTo(RecordingPhase.IDLE)
            assertThat(settled.savedHikeId).isEqualTo(HikeId(sessionId))
            cancelAndIgnoreRemainingEvents()
        }
        secondStore.clear()
    }
}
