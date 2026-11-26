package app.openhikes.shell

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.RecordingRoute
import app.openhikes.core.ui.navigation.RouteEditRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            routeEditOverlayState = RouteEditOverlayState(),
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

    @Test
    fun routeEditOverlayAndHandlerHideWhenRecordingClaimsMapAndRestoreWhenPopped() = runTest {
        val hikeId = HikeId("h-edit")
        val a = GeoCoordinate(47.718, 12.831)
        val b = GeoCoordinate(47.720, 12.835)
        val claimCoordinator = MapClaimCoordinator()
        val editOverlayState = RouteEditOverlayState()
        val editOverlay = MapRouteEditOverlay(
            hikeId = hikeId,
            tintHex = RouteTint.DEFAULT_HEX,
            widthDp = 3f,
            keptWalkedSegments = listOf(listOf(a, b)),
            startHandle = a,
            endHandle = b
        )
        val handler = noOpRouteEditHandler()
        editOverlayState.show(editOverlay)
        editOverlayState.bindHandler(hikeId, handler)
        val shellVm = createShellViewModel(
            hikes = listOf(sampleHike(hikeId, a, b)),
            claimCoordinator = claimCoordinator,
            editOverlayState = editOverlayState
        )

        val chromeJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shellVm.chromeUiState.collect {}
        }
        val editJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shellVm.routeEditOverlay.collect {}
        }

        val editRoute = RouteEditRoute(hikeId)
        shellVm.syncBackStack(listOf(MapHomeRoute, editRoute))
        claimCoordinator.registerClaim(
            MapClaim(routeKey = editRoute, depth = 1, selectedHikeId = hikeId, hiddenRouteHikeId = hikeId)
        )
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.map { it.hikeId }).doesNotContain(hikeId)
        assertThat(shellVm.routeEditOverlay.value).isEqualTo(editOverlay)
        assertThat(shellVm.routeEditHandler()).isSameInstanceAs(handler)

        shellVm.syncBackStack(listOf(MapHomeRoute, editRoute, RecordingRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = RecordingRoute, depth = 2))
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.map { it.hikeId }).contains(hikeId)
        assertThat(shellVm.routeEditOverlay.value).isNull()
        assertThat(shellVm.routeEditHandler()).isNull()

        shellVm.syncBackStack(listOf(MapHomeRoute, editRoute))
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.map { it.hikeId }).doesNotContain(hikeId)
        assertThat(shellVm.routeEditOverlay.value).isEqualTo(editOverlay)
        assertThat(shellVm.routeEditHandler()).isSameInstanceAs(handler)

        chromeJob.cancel()
        editJob.cancel()
    }

    private fun sampleHike(hikeId: HikeId, a: GeoCoordinate, b: GeoCoordinate): Hike = Hike(
        id = hikeId,
        title = "Edited Trail",
        distanceMeters = 500.0,
        dateEpochMillis = 10_000L,
        route = listOf(RouteCoordinate(a.latitude, a.longitude), RouteCoordinate(b.latitude, b.longitude))
    )

    private fun createShellViewModel(
        hikes: List<Hike>,
        claimCoordinator: MapClaimCoordinator,
        editOverlayState: RouteEditOverlayState
    ): MapShellViewModel = MapShellViewModel(
        userSettingsRepository = FakeUserSettingsRepository(),
        hikeRepository = FakeHikeRepository(hikes),
        offlineCountryRepository = FakeOfflineCountryRepository(),
        mapClaimCoordinator = claimCoordinator,
        recordingController = FakeRecordingController(),
        trailWalkController = FakeTrailWalkController(),
        routeHighlightState = RouteHighlightState(),
        walkOverlayState = WalkOverlayState(),
        routeEditOverlayState = editOverlayState,
        mapProviderKeys = MapProviderKeys.Empty,
        launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )

    private fun noOpRouteEditHandler(): RouteEditMapInteractionHandler = object : RouteEditMapInteractionHandler {
        override fun onDragHandle(handle: RouteTrimHandle, towards: GeoCoordinate) = Unit
        override fun onAdjustHandle(handle: RouteTrimHandle, direction: Int) = Unit
        override fun onBeginPull(coordinate: GeoCoordinate, reachMeters: Double): Boolean = false
        override fun onMovePull(coordinate: GeoCoordinate) = Unit
        override fun onEndPull(coordinate: GeoCoordinate, movedEnough: Boolean) = Unit
        override fun onCancelPull() = Unit
    }
}
