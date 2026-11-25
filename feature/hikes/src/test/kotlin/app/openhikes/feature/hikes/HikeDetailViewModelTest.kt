package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.location.TrailWalkEnd
import app.openhikes.core.location.TrailWalkPermissionIssue
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HikeDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun preparesProfileDispatchesFitBoundsUpdatesScrubRenameExportAndReleasesOnClear() = runTest {
        val hike = sampleDetailHike()
        val repo = FakeHikeRepository(listOf(hike))
        val coordinator = MapClaimCoordinator()
        val highlightState = RouteHighlightState()
        val viewModel = createViewModel(
            hike = hike,
            repo = repo,
            coordinator = coordinator,
            highlightState = highlightState
        )
        val store = ViewModelStore().apply { put("detail", viewModel) }

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isLoaded).isTrue()
            assertThat(loaded.profile?.samples).hasSize(2)
            assertThat(loaded.statistics?.elevationGainMeters).isWithin(1e-6).of(30.0)
            assertThat(coordinator.activeClaim.value?.selectedHikeId).isEqualTo(hike.id)
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            coordinator.consumeCameraCommand(coordinator.pendingCameraCommand.value!!.id)
            viewModel.onZoomToRoute()
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            viewModel.onScrubDistance(loaded.profile!!.totalDistanceMeters)
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-6).of(loaded.profile.totalDistanceMeters)
            assertThat(highlightState.highlight.value?.hikeId).isEqualTo(hike.id)

            repo.nextRenameFailure = IOException("Disk error")
            viewModel.onStartRename()
            viewModel.onTitleDraftChange("Sunny Loop")
            viewModel.onCommitRename()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().actionError).isEqualTo(HikeDetailError.RENAME_FAILED)

            viewModel.onCommitRename()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hike?.displayTitle).isEqualTo("Sunny Loop")

            viewModel.onRequestExportGpx()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().pendingExport?.xml).contains("Sunny Loop")
            viewModel.onExportDestinationPicked("content://exports/out.gpx")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().exportSuccess).isTrue()
            assertThat(repo.lastExportedUri?.first).isEqualTo("content://exports/out.gpx")

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
        assertThat(highlightState.highlight.value).isNull()
    }

    @Test
    fun controlsWalkLifecycleSyncsFollowScrubAndHandlesEndOutcomes() = runTest {
        val hike = sampleDetailHike()
        val walkController = FakeTrailWalkController()
        val viewModel = createViewModel(hike = hike, walkController = walkController)
        val openedSummaries = mutableListOf<Pair<HikeId, String>>()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            walkController.setProgress(followMarkerProgress(hike.id, 120.0))
            advanceUntilIdle()
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-3).of(120.0)

            viewModel.onWalkAction(HikeWalkAction.SetAutoFollow(false)) { _, _ -> }
            advanceUntilIdle()
            walkController.setProgress(followMarkerProgress(hike.id, 200.0))
            advanceUntilIdle()
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-3).of(120.0)
            assertThat(walkController.lastAutoFollowChange).isEqualTo(hike.id to false)

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.PAUSED)

            walkController.nextEndResult = TrailWalkEnd.Refused
            viewModel.onWalkAction(HikeWalkAction.RequestEndWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showEndWalkDialog).isTrue()

            viewModel.onWalkAction(HikeWalkAction.ConfirmEndWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showEndWalkRefusedDialog).isTrue()
            assertThat(openedSummaries).isEmpty()

            viewModel.onWalkAction(HikeWalkAction.DismissEndRefused) { _, _ -> }
            viewModel.onWalkAction(HikeWalkAction.ConfirmEndWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(openedSummaries).hasSize(1)
            assertThat(openedSummaries.single().first).isEqualTo(hike.id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun requestsPreciseLocationBeforeStartingOrResumingWalkWhenNotSimulated() = runTest {
        val hike = sampleDetailHike()
        val walkController = FakeTrailWalkController()
        val viewModel = createViewModel(
            hike = hike,
            walkController = walkController,
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // Start Hike requests permission first instead of starting immediately.
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().requestLocationPermission).isTrue()
            assertThat(walkController.startByHandCalls).isEqualTo(0)

            // Coarse-only grant reports precise-location error and never starts the walk.
            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = false, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            val coarseState = expectMostRecentItem()
            assertThat(coarseState.requestLocationPermission).isFalse()
            assertThat(walkController.startByHandCalls).isEqualTo(0)
            assertThat(coarseState.walkSession.permissionIssue)
                .isEqualTo(TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED)

            // Fine grant starts the walk.
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = true, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            assertThat(walkController.startByHandCalls).isEqualTo(1)
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            // Pause then Resume also checks permission before calling resume().
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.PAUSED)

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().requestLocationPermission).isTrue()
            assertThat(walkController.resumeCalls).isEqualTo(0)

            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = true, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            assertThat(walkController.resumeCalls).isEqualTo(1)
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(
        hike: Hike,
        repo: FakeHikeRepository = FakeHikeRepository(listOf(hike)),
        walkController: FakeTrailWalkController = FakeTrailWalkController(),
        coordinator: MapClaimCoordinator = MapClaimCoordinator(),
        highlightState: RouteHighlightState = RouteHighlightState(),
        launchConfigHolder: LaunchConfigHolder = LaunchConfigHolder(LaunchConfig(enableSimulatedLocation = true))
    ): HikeDetailViewModel = HikeDetailViewModel(
        rawHikeId = hike.id.value,
        hikeRepository = repo,
        userSettingsRepository = FakeUserSettingsRepository(),
        offlineCountryRepository = FakeOfflineCountryRepository(),
        trailWalkController = walkController,
        recordingController = FakeRecordingController(),
        mapClaimCoordinator = coordinator,
        routeHighlightState = highlightState,
        launchConfigHolder = launchConfigHolder,
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )

    private fun followMarkerProgress(hikeId: HikeId, distanceMeters: Double): TrailWalkProgressState =
        TrailWalkProgressState(
            hikeId = hikeId,
            followMarkerHikeId = hikeId,
            followMarkerDistanceMeters = distanceMeters
        )

    private fun sampleDetailHike(): Hike = Hike(
        id = HikeId("h1"),
        title = "Thumsee Loop",
        distanceMeters = 1500.0,
        dateEpochMillis = 20_000L,
        route = listOf(
            RouteCoordinate(47.7180, 12.8300, elevationMeters = 530.0, timestampEpochMillis = 0L),
            RouteCoordinate(47.7200, 12.8320, elevationMeters = 560.0, timestampEpochMillis = 600_000L)
        )
    )
}
