package app.openhikes.feature.trailmaker

import app.openhikes.core.location.LocationPermissionStatus
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailLegGap
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegPath
import app.openhikes.core.model.TrailLegRoute
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailStopSearchResult
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.model.TrailWaypoint
import app.openhikes.core.testing.FakeTrailLegRouter
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeStopPlaceSearchSource
import app.openhikes.core.testing.android.FakeTrailDraftRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrailMakerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val startCoord = GeoCoordinate(47.7200, 12.8200)
    private val midCoord = GeoCoordinate(47.7240, 12.8260)
    private val endCoord = GeoCoordinate(47.7280, 12.8320)

    private fun createViewModel(
        draftRepository: FakeTrailDraftRepository = FakeTrailDraftRepository(),
        hikeRepository: FakeHikeRepository = FakeHikeRepository(),
        router: FakeTrailLegRouter = FakeTrailLegRouter(),
        searchSource: FakeStopPlaceSearchSource = FakeStopPlaceSearchSource(),
        locationFeed: FakeLocationFeed = FakeLocationFeed(),
        overlayState: TrailDraftOverlayState = TrailDraftOverlayState(),
        claimCoordinator: MapClaimCoordinator = MapClaimCoordinator(),
        appScope: CoroutineScope = CoroutineScope(SupervisorJob() + testDispatcher)
    ): TrailMakerViewModel = TrailMakerViewModel(
        trailDraftRepository = draftRepository,
        hikeRepository = hikeRepository,
        userSettingsRepository = FakeUserSettingsRepository(),
        trailLegRouter = router,
        stopPlaceSearchSource = searchSource,
        locationFeed = locationFeed,
        clock = TestClock(1_780_000_000_000L),
        mapClaimCoordinator = claimCoordinator,
        trailDraftOverlayState = overlayState,
        applicationScope = appScope
    )

    @Test
    fun restoresPersistedDraftAndRoutesSnappedLegWithAlternatives() = runTest(testDispatcher) {
        val wp1 = TrailWaypoint.at(startCoord, name = "Thumsee", id = "a")
        val wp2 = TrailWaypoint.at(endCoord, name = "Kaitl", id = "b")
        val draftRepo = FakeTrailDraftRepository(
            initialDraft = StoredTrailDraft(
                waypoints = listOf(wp1, wp2),
                travelMode = TrailTravelMode.HIKING,
                snapsToPaths = true
            )
        )
        val altPath = TrailLegPath(
            listOf(startCoord, GeoCoordinate(47.7290, 12.8190), endCoord),
            distanceMeters = 1_650.0
        )
        val router = FakeTrailLegRouter(
            handler = { _, _ ->
                TrailLegRoute(
                    coordinates = listOf(startCoord, midCoord, endCoord),
                    distanceMeters = 1_300.0,
                    snap = TrailLegSnap.Snapped,
                    alternatives = listOf(altPath)
                )
            }
        )
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(draftRepository = draftRepo, router = router, overlayState = overlayState)
        advanceUntilIdle()

        assertThat(vm.uiState.value.canBeSaved).isTrue()
        assertThat(vm.uiState.value.canSnapToPaths).isTrue()
        assertThat(vm.uiState.value.distanceMeters).isWithin(0.1).of(1_300.0)
        assertThat(overlayState.overlay.value?.legs?.single()?.alternatives).hasSize(1)

        // Picking the alternative updates distance and persists.
        overlayState.interactionHandler?.onChooseAlternative(legIndex = 0, alternativeIndex = 0)
        advanceUntilIdle()
        assertThat(vm.uiState.value.distanceMeters).isWithin(0.1).of(1_650.0)
    }

    @Test
    fun refusalIsNotRetriedAutomaticallyUntilUserTapsTryAgain() = runTest(testDispatcher) {
        var attempts = 0
        val router = FakeTrailLegRouter(
            handler = { ends, _ ->
                attempts += 1
                if (attempts == 1) {
                    TrailLegRoute.straight(ends, TrailLegSnap.Refused(TrailLegOutage.OFFLINE))
                } else {
                    TrailLegRoute(
                        coordinates = listOf(ends.start, midCoord, ends.end),
                        distanceMeters = 1_400.0,
                        snap = TrailLegSnap.Snapped
                    )
                }
            }
        )
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(router = router, overlayState = overlayState)
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.interactionHandler)
        handler.onDropPin(startCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        handler.onDropPin(endCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        advanceUntilIdle()

        assertThat(attempts).isEqualTo(1)
        assertThat(vm.uiState.value.hasRetryableLegs).isTrue()
        assertThat(vm.uiState.value.worstLegSnap).isEqualTo(TrailLegSnap.Refused(TrailLegOutage.OFFLINE))

        // Tapping Retry asks the router again and succeeds.
        vm.onAction(TrailMakerAction.RetryRefusedLegs)
        advanceUntilIdle()

        assertThat(attempts).isEqualTo(2)
        assertThat(vm.uiState.value.hasRetryableLegs).isFalse()
        assertThat(vm.uiState.value.distanceMeters).isWithin(0.1).of(1_400.0)
    }

    @Test
    fun switchingTravelModeClearsRoutingAndReportsUnsupportedDirections() = runTest(testDispatcher) {
        val router = FakeTrailLegRouter()
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(router = router, overlayState = overlayState)
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.interactionHandler)
        handler.onDropPin(startCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        handler.onDropPin(endCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        advanceUntilIdle()

        vm.onAction(TrailMakerAction.SelectTravelMode(TrailTravelMode.CYCLING))
        advanceUntilIdle()

        assertThat(vm.uiState.value.travelMode).isEqualTo(TrailTravelMode.CYCLING)
        assertThat(vm.uiState.value.canBeSaved).isTrue()
        assertThat(vm.uiState.value.worstLegSnap).isEqualTo(TrailLegSnap.Unmapped(TrailLegGap.NO_DIRECTIONS))
        assertThat(vm.uiState.value.canSnapToPaths).isFalse()
    }

    @Test
    fun stopSearchRecordsRecentAndSupportsMyLocationPick() = runTest(testDispatcher) {
        val draftRepo = FakeTrailDraftRepository()
        val searchResult = TrailStopSearchResult(
            id = "res-1",
            name = "Thumsee Trailhead",
            subtitle = "Bad Reichenhall",
            coordinate = startCoord
        )
        val searchSource = FakeStopPlaceSearchSource(
            searchResults = { Result.success(listOf(searchResult)) }
        )
        val locationFeed = FakeLocationFeed().apply {
            setPermission(LocationPermissionStatus.GRANTED_FINE)
            lastKnownCoordinateOverride = endCoord
        }
        val vm = createViewModel(
            draftRepository = draftRepo,
            searchSource = searchSource,
            locationFeed = locationFeed
        )
        advanceUntilIdle()

        // Search and pick Start.
        vm.onAction(TrailMakerAction.OpenStopSearch(TrailStopSearchTarget.Open(TrailStopRole.Start)))
        vm.onAction(TrailMakerAction.UpdateSearchQuery("Thumsee"))
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertThat(vm.uiState.value.stopSearch?.results).containsExactly(searchResult)

        vm.onAction(TrailMakerAction.PickSearchResult(searchResult))
        advanceUntilIdle()
        assertThat(vm.uiState.value.stopSearch).isNull()
        assertThat(draftRepo.recentStops.first().map { it.name }).containsExactly("Thumsee Trailhead")

        // Pick My Location for Destination.
        vm.onAction(TrailMakerAction.OpenStopSearch(TrailStopSearchTarget.Open(TrailStopRole.End)))
        assertThat(vm.uiState.value.stopSearch?.myLocationAvailable).isTrue()
        vm.onAction(TrailMakerAction.PickMyLocation)
        advanceUntilIdle()

        assertThat(vm.uiState.value.canBeSaved).isTrue()
        assertThat(vm.uiState.value.rows.count { !it.isOpen }).isEqualTo(2)
    }

    @Test
    fun droppedPinCardSupportsAddRemoveAndPinDragUpdatesCoordinates() = runTest(testDispatcher) {
        val searchSource = FakeStopPlaceSearchSource(
            reverseAddressProvider = { "Thumsee 1, 83435 Bad Reichenhall" }
        )
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(searchSource = searchSource, overlayState = overlayState)
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.interactionHandler)
        handler.onDropPin(startCoord, legEnds = null)
        advanceUntilIdle()
        assertThat(vm.uiState.value.placeCard?.isDroppedPin).isTrue()
        assertThat(vm.uiState.value.placeCard?.address).isEqualTo("Thumsee 1, 83435 Bad Reichenhall")

        // Closing card keeps dropped pin on map; Remove Pin removes it.
        vm.onAction(TrailMakerAction.ClosePlaceCard)
        assertThat(vm.uiState.value.placeCard).isNull()
        assertThat(overlayState.overlay.value?.droppedPin).isNotNull()

        handler.onTapDroppedPin()
        assertThat(vm.uiState.value.placeCard?.isDroppedPin).isTrue()
        vm.onAction(TrailMakerAction.RemoveDroppedPin)
        assertThat(overlayState.overlay.value?.droppedPin).isNull()

        // Add two stops, then drag the second pin.
        handler.onDropPin(startCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        handler.onDropPin(midCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        advanceUntilIdle()

        assertThat(handler.onBeginPinDrag(1)).isTrue()
        handler.onMovePinDrag(endCoord)
        handler.onEndPinDrag()
        advanceUntilIdle()

        assertThat(overlayState.overlay.value?.pins?.get(1)?.coordinate).isEqualTo(endCoord)
    }

    @Test
    fun saveSuccessClearsDraftAndEmitsHikeIdWhileFailureKeepsDraftIntact() = runTest(testDispatcher) {
        val draftRepo = FakeTrailDraftRepository()
        val hikeRepo = FakeHikeRepository()
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(
            draftRepository = draftRepo,
            hikeRepository = hikeRepo,
            overlayState = overlayState
        )
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.interactionHandler)
        handler.onDropPin(startCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        handler.onDropPin(endCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        advanceUntilIdle()

        // Simulate save failure first.
        hikeRepo.nextDrawnTrailSaveFailure = IOException("Disk full")
        vm.onAction(TrailMakerAction.OpenSaveDialog)
        vm.onAction(TrailMakerAction.UpdateNameDraft("Saturday Ridge"))
        var savedId: HikeId? = null
        vm.onConfirmSave { savedId = it }
        advanceUntilIdle()

        assertThat(savedId).isNull()
        assertThat(vm.uiState.value.refusal).isEqualTo(app.openhikes.core.model.TrailDraftRefusal.NOT_SAVED)
        assertThat(draftRepo.currentStoredDraft.isEmpty).isFalse()

        // Clear failure and save again.
        hikeRepo.nextDrawnTrailSaveFailure = null
        vm.onAction(TrailMakerAction.DismissRefusal)
        vm.onAction(TrailMakerAction.OpenSaveDialog)
        vm.onAction(TrailMakerAction.UpdateNameDraft("Saturday Ridge"))
        vm.onConfirmSave { savedId = it }
        advanceUntilIdle()

        val id = requireNotNull(savedId)
        assertThat(draftRepo.currentStoredDraft.isEmpty).isTrue()
        val savedHike = requireNotNull(hikeRepo.observeHike(id).first())
        assertThat(savedHike.title).isEqualTo("Saturday Ridge")
        assertThat(savedHike.durationSeconds).isNull()
    }

    @Test
    fun closeEmptyClosesImmediatelyWhileNonEmptyShowsDialogAndDiscardClearsDraft() = runTest(testDispatcher) {
        val draftRepo = FakeTrailDraftRepository()
        val overlayState = TrailDraftOverlayState()
        val vm = createViewModel(draftRepository = draftRepo, overlayState = overlayState)
        advanceUntilIdle()

        var closedCount = 0
        vm.onCloseRequested { closedCount += 1 }
        advanceUntilIdle()
        assertThat(closedCount).isEqualTo(1)
        assertThat(vm.uiState.value.showCloseDialog).isFalse()

        val handler = requireNotNull(overlayState.interactionHandler)
        handler.onDropPin(startCoord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
        advanceUntilIdle()

        vm.onCloseRequested { closedCount += 1 }
        assertThat(vm.uiState.value.showCloseDialog).isTrue()
        assertThat(closedCount).isEqualTo(1)

        vm.onDiscardTrail { closedCount += 1 }
        advanceUntilIdle()
        assertThat(closedCount).isEqualTo(2)
        assertThat(draftRepo.currentStoredDraft.isEmpty).isTrue()
    }
}
