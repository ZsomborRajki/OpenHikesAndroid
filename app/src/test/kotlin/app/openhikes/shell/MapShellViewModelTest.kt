package app.openhikes.shell

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.MapTrailDraftOverlay
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailDraftMapInteractionHandler
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteShade
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphEdge
import app.openhikes.core.model.TrailGraphEdgeId
import app.openhikes.core.model.TrailGraphNode
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.RecordingRoute
import app.openhikes.core.ui.navigation.RouteEditRoute
import app.openhikes.core.ui.navigation.TrailMakerRoute
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
            trailDraftOverlayState = TrailDraftOverlayState(),
            mapCameraTracker = MapCameraTracker(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            photoCaptureController = PhotoCaptureController(),
            trailGraphProvider = FakeTrailGraphProvider(isAvailable = false),
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
        assertThat(shellVm.chromeUiState.value.overlayState.routes).isEmpty()
        assertThat(shellVm.chromeUiState.value.overlayState.libraryLines.map { it.hikeId }).contains(hikeId)
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

    @Test
    fun trailDraftOverlayAndHandlerHideWhenRecordingClaimsMapAndRestoreWhenPopped() = runTest {
        val claimCoordinator = MapClaimCoordinator()
        val draftOverlayState = TrailDraftOverlayState()
        val draftOverlay = MapTrailDraftOverlay(snapsToPaths = true)
        val handler = noOpTrailDraftHandler()
        draftOverlayState.show(draftOverlay)
        draftOverlayState.bindHandler(handler)
        val shellVm = createShellViewModel(
            hikes = emptyList(),
            claimCoordinator = claimCoordinator,
            editOverlayState = RouteEditOverlayState(),
            draftOverlayState = draftOverlayState
        )

        val draftJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shellVm.trailDraftOverlay.collect {}
        }

        shellVm.syncBackStack(listOf(MapHomeRoute, TrailMakerRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = TrailMakerRoute, depth = 1, selectedHikeId = null))
        advanceUntilIdle()
        assertThat(shellVm.trailDraftOverlay.value).isEqualTo(draftOverlay)
        assertThat(shellVm.trailDraftHandler()).isSameInstanceAs(handler)

        shellVm.syncBackStack(listOf(MapHomeRoute, TrailMakerRoute, RecordingRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = RecordingRoute, depth = 2))
        advanceUntilIdle()
        assertThat(shellVm.trailDraftOverlay.value).isNull()
        assertThat(shellVm.trailDraftHandler()).isNull()

        shellVm.syncBackStack(listOf(MapHomeRoute, TrailMakerRoute))
        advanceUntilIdle()
        assertThat(shellVm.trailDraftOverlay.value).isEqualTo(draftOverlay)
        assertThat(shellVm.trailDraftHandler()).isSameInstanceAs(handler)

        draftJob.cancel()
    }

    @Test
    fun libraryLinesExcludeSelectedAndHideDuringTrailMakerAndOpenOnMapTapUnlessRecording() = runTest {
        val firstId = HikeId("h-first")
        val secondId = HikeId("h-second")
        val firstA = GeoCoordinate(47.700, 12.800)
        val firstB = GeoCoordinate(47.705, 12.805)
        val secondA = GeoCoordinate(47.750, 12.850)
        val secondB = GeoCoordinate(47.755, 12.855)
        val claimCoordinator = MapClaimCoordinator()
        val recordingController = FakeRecordingController()
        val shellVm = createShellViewModel(
            hikes = listOf(sampleHike(firstId, firstA, firstB), sampleHike(secondId, secondA, secondB)),
            claimCoordinator = claimCoordinator,
            editOverlayState = RouteEditOverlayState(),
            recordingController = recordingController
        )

        val chromeJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shellVm.chromeUiState.collect {}
        }
        advanceUntilIdle()

        // Home: both finished trails render as faint library lines, none as selected full routes.
        assertThat(shellVm.chromeUiState.value.overlayState.routes).isEmpty()
        assertThat(shellVm.chromeUiState.value.overlayState.libraryLines.map { it.hikeId })
            .containsExactly(firstId, secondId)

        // Tapping near first trail requests opening it.
        shellVm.onCameraChanged(MapCameraState(center = firstA, zoom = 14.0))
        shellVm.onMapTap(GeoCoordinate(47.7025, 12.8025))
        assertThat(shellVm.openHikeFromMapRequest.value?.second).isEqualTo(firstId)

        // Selecting first trail promotes it to routes and removes it from libraryLines.
        val detailRoute = HikeDetailRoute(firstId)
        shellVm.syncBackStack(listOf(MapHomeRoute, detailRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = detailRoute, depth = 1, selectedHikeId = firstId))
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.map { it.hikeId }).containsExactly(firstId)
        assertThat(shellVm.chromeUiState.value.overlayState.libraryLines.map { it.hikeId }).containsExactly(secondId)

        // Tapping selected trail does not fire another library-line open request.
        val countBeforeSelectedTap = shellVm.openHikeFromMapRequest.value?.first
        shellVm.onMapTap(GeoCoordinate(47.7025, 12.8025))
        assertThat(shellVm.openHikeFromMapRequest.value?.first).isEqualTo(countBeforeSelectedTap)

        // Tapping the unselected second trail opens it.
        shellVm.onMapTap(GeoCoordinate(47.7525, 12.8525))
        assertThat(shellVm.openHikeFromMapRequest.value?.second).isEqualTo(secondId)

        // Recording suppresses tapping library lines.
        val countBeforeRecordingTap = shellVm.openHikeFromMapRequest.value?.first
        recordingController.setSessionState(RecordingSessionState(sessionId = "rec", phase = RecordingPhase.RECORDING))
        shellVm.onMapTap(GeoCoordinate(47.7525, 12.8525))
        assertThat(shellVm.openHikeFromMapRequest.value?.first).isEqualTo(countBeforeRecordingTap)

        // Trail Maker hides all library lines and routes.
        shellVm.syncBackStack(listOf(MapHomeRoute, TrailMakerRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = TrailMakerRoute, depth = 1, selectedHikeId = null))
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes).isEmpty()
        assertThat(shellVm.chromeUiState.value.overlayState.libraryLines).isEmpty()

        // Consuming a map open request clears only the matching token.
        val token = shellVm.openHikeFromMapRequest.value!!.first
        shellVm.onConsumeOpenHikeFromMapRequest(token - 1)
        assertThat(shellVm.openHikeFromMapRequest.value?.first).isEqualTo(token)
        shellVm.onConsumeOpenHikeFromMapRequest(token)
        assertThat(shellVm.openHikeFromMapRequest.value).isNull()

        chromeJob.cancel()
    }

    @Test
    fun selectedRouteGuardUsesFullResolutionGeometryOnThinnedBendVertices() = runTest {
        val selectedId = HikeId("h-selected-dense")
        val crossingId = HikeId("h-crossing-faint")
        // Build 320-point route with 260 sharp oscillations followed by a smaller bend that thinning drops.
        val selectedRoute = buildList {
            for (step in 0 until 280) {
                val lat = 47.600 + step * 0.0001
                val lon = 12.900 + if (step % 2 == 0) 0.0004 else -0.0004
                add(RouteCoordinate(lat, lon))
            }
            add(RouteCoordinate(47.6285, 12.9000))
            add(RouteCoordinate(47.6290, 12.90025)) // Dropped bend (~18 m east of chord, > zoom-20 tolerance)
            add(RouteCoordinate(47.6295, 12.9000))
        }
        val droppedBend = GeoCoordinate(47.6290, 12.90025)
        val crossingHike = sampleHike(
            crossingId,
            GeoCoordinate(47.6290, 12.8998),
            GeoCoordinate(47.6290, 12.9008)
        )
        val claimCoordinator = MapClaimCoordinator()
        val shellVm = createShellViewModel(
            hikes = listOf(
                Hike(
                    id = selectedId,
                    title = "Dense Trail",
                    distanceMeters = 5_000.0,
                    dateEpochMillis = 20_000L,
                    route = selectedRoute
                ),
                crossingHike
            ),
            claimCoordinator = claimCoordinator,
            editOverlayState = RouteEditOverlayState()
        )
        val chromeJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shellVm.chromeUiState.collect {}
        }

        val detailRoute = HikeDetailRoute(selectedId)
        shellVm.syncBackStack(listOf(MapHomeRoute, detailRoute))
        claimCoordinator.registerClaim(MapClaim(routeKey = detailRoute, depth = 1, selectedHikeId = selectedId))
        advanceUntilIdle()

        shellVm.onCameraChanged(MapCameraState(center = droppedBend, zoom = 20.0))
        shellVm.onMapTap(droppedBend)
        assertThat(shellVm.openHikeFromMapRequest.value).isNull()

        chromeJob.cancel()
    }

    @Test
    fun shadesSelectedRouteByElevationOrDifficultySkipsUngradedAndClearsWhenOff() = runTest {
        val (gradedHike, ungradedHike, graphProvider) = steepRidgeShadingFixture()
        val settingsRepo = FakeUserSettingsRepository()
        val claimCoordinator = MapClaimCoordinator()
        val shellVm = createShellViewModel(
            hikes = listOf(gradedHike, ungradedHike),
            claimCoordinator = claimCoordinator,
            editOverlayState = RouteEditOverlayState(),
            userSettingsRepository = settingsRepo,
            trailGraphProvider = graphProvider
        )
        val chromeJob = launch(UnconfinedTestDispatcher(testScheduler)) { shellVm.chromeUiState.collect {} }

        claimCoordinator.registerClaim(
            MapClaim(routeKey = HikeDetailRoute(gradedHike.id), depth = 1, selectedHikeId = gradedHike.id)
        )
        advanceUntilIdle()
        val elevationShades = shellVm.chromeUiState.value.overlayState.routes.single().shadedStretches.map { it.shade }
        assertThat(elevationShades).containsExactly(RouteShade.HARD)
        assertThat(graphProvider.assembledGraphCalls).isEqualTo(0)

        settingsRepo.setRouteColoring(RouteColoring.DIFFICULTY)
        advanceUntilIdle()
        val difficultyShades = shellVm.chromeUiState.value.overlayState.routes.single().shadedStretches.map { it.shade }
        assertThat(difficultyShades).containsExactly(RouteShade.HARD)
        assertThat(graphProvider.assembledGraphCalls).isEqualTo(1)

        claimCoordinator.registerClaim(
            MapClaim(routeKey = HikeDetailRoute(ungradedHike.id), depth = 2, selectedHikeId = ungradedHike.id)
        )
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.single().shadedStretches).isEmpty()
        assertThat(graphProvider.assembledGraphCalls).isEqualTo(1)

        claimCoordinator.releaseClaim(HikeDetailRoute(ungradedHike.id))
        settingsRepo.setRouteColoring(RouteColoring.OFF)
        advanceUntilIdle()
        assertThat(shellVm.chromeUiState.value.overlayState.routes.single().shadedStretches).isEmpty()

        chromeJob.cancel()
    }

    private fun steepRidgeShadingFixture(): Triple<Hike, Hike, FakeTrailGraphProvider> {
        val route = List(25) { index ->
            RouteCoordinate(
                latitude = 47.70 + (index * 25.0) / 111_320.0,
                longitude = 12.83,
                elevationMeters = 500.0 + index * 25.0 * 0.25
            )
        }
        val gradedHike = Hike(
            id = HikeId("h-shade"),
            title = "Steep Ridge",
            distanceMeters = 600.0,
            dateEpochMillis = 10_000L,
            route = route
        )
        val ungradedHike = gradedHike.copy(
            id = HikeId("h-ungraded"),
            title = "Ungraded Path",
            difficultyMetersByGrade = mapOf("unmapped" to 600.0)
        )
        val graph = TrailGraph(
            nodes = listOf(
                TrailGraphNode(1L, route.first().latitude, route.first().longitude),
                TrailGraphNode(2L, route.last().latitude, route.last().longitude)
            ),
            edges = listOf(
                TrailGraphEdge(
                    id = TrailGraphEdgeId(50L, 0),
                    fromNodeId = 1L,
                    toNodeId = 2L,
                    lengthMeters = 600.0,
                    highway = "path",
                    sacScale = "alpine_hiking"
                )
            )
        )
        return Triple(gradedHike, ungradedHike, FakeTrailGraphProvider(graph = graph, cachedGraph = graph))
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
        editOverlayState: RouteEditOverlayState,
        draftOverlayState: TrailDraftOverlayState = TrailDraftOverlayState(),
        recordingController: FakeRecordingController = FakeRecordingController(),
        userSettingsRepository: FakeUserSettingsRepository = FakeUserSettingsRepository(),
        hikeRepository: FakeHikeRepository = FakeHikeRepository(hikes),
        trailGraphProvider: TrailGraphProvider = FakeTrailGraphProvider(isAvailable = false)
    ): MapShellViewModel = MapShellViewModel(
        userSettingsRepository = userSettingsRepository,
        hikeRepository = hikeRepository,
        offlineCountryRepository = FakeOfflineCountryRepository(),
        mapClaimCoordinator = claimCoordinator,
        recordingController = recordingController,
        trailWalkController = FakeTrailWalkController(),
        routeHighlightState = RouteHighlightState(),
        walkOverlayState = WalkOverlayState(),
        routeEditOverlayState = editOverlayState,
        trailDraftOverlayState = draftOverlayState,
        mapCameraTracker = MapCameraTracker(),
        trailPlacesOverlayState = TrailPlacesOverlayState(),
        trailPhotosOverlayState = TrailPhotosOverlayState(),
        photoCaptureController = PhotoCaptureController(),
        trailGraphProvider = trailGraphProvider,
        mapProviderKeys = MapProviderKeys.Empty,
        launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )

    private fun noOpTrailDraftHandler(): TrailDraftMapInteractionHandler = object : TrailDraftMapInteractionHandler {
        override fun onDropPin(coordinate: GeoCoordinate, legEnds: TrailLegEnds?) = Unit
        override fun onTapStopPin(waypointId: String) = Unit
        override fun onTapDroppedPin() = Unit
        override fun onChooseAlternative(legIndex: Int, alternativeIndex: Int) = Unit
        override fun onBeginPinDrag(index: Int): Boolean = false
        override fun onMovePinDrag(coordinate: GeoCoordinate) = Unit
        override fun onEndPinDrag() = Unit
        override fun onCancelPinDrag() = Unit
    }

    private fun noOpRouteEditHandler(): RouteEditMapInteractionHandler = object : RouteEditMapInteractionHandler {
        override fun onDragHandle(handle: RouteTrimHandle, towards: GeoCoordinate) = Unit
        override fun onAdjustHandle(handle: RouteTrimHandle, direction: Int) = Unit
        override fun onBeginPull(coordinate: GeoCoordinate, reachMeters: Double): Boolean = false
        override fun onMovePull(coordinate: GeoCoordinate) = Unit
        override fun onEndPull(coordinate: GeoCoordinate, movedEnough: Boolean) = Unit
        override fun onCancelPull() = Unit
    }
}
