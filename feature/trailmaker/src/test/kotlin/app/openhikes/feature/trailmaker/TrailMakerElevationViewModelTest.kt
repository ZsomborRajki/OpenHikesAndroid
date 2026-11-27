package app.openhikes.feature.trailmaker

import app.openhikes.core.data.TrailDraftWriteCoordinator
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteElevationSource
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegPath
import app.openhikes.core.model.TrailLegRoute
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.testing.FakeRouteElevationSource
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.FakeTrailLegRouter
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeStopPlaceSearchSource
import app.openhikes.core.testing.android.FakeTrailDraftRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
class TrailMakerElevationViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val startCoord = GeoCoordinate(47.7200, 12.8200)
    private val midCoord = GeoCoordinate(47.7240, 12.8260)
    private val endCoord = GeoCoordinate(47.7280, 12.8320)

    @Test
    fun measuresDebouncedElevationMasksDegradedLegsAndSavesHeights() = runTest(testDispatcher) {
        val hikeRepo = FakeHikeRepository()
        val overlayState = TrailDraftOverlayState()
        val gate = CompletableDeferred<Unit>()
        val elevationSource = FakeRouteElevationSource(
            handler = { coords ->
                ElevationFetchResult.Heights(coords.mapIndexed { idx, _ -> 500.0 + idx * 180.0 })
            }
        ).apply { this.gate = gate }
        val vm = createViewModel(
            hikeRepository = hikeRepo,
            router = firstLegSnappedSecondRefusedRouter(),
            routeElevationSource = elevationSource,
            overlayState = overlayState
        )
        advanceUntilIdle()

        addDroppedStop(vm, overlayState, startCoord)
        addDroppedStop(vm, overlayState, midCoord)
        advanceTimeBy(1_000L)
        assertThat(elevationSource.requestCount).isEqualTo(0)
        assertThat(vm.uiState.value.isElevationColoringAvailable).isTrue()

        // Third stop before 2s expires coalesces rapid edits into a single debounced elevation request.
        addDroppedStop(vm, overlayState, endCoord)
        advanceTimeBy(2_100L)
        assertThat(elevationSource.requestCount).isEqualTo(1)
        assertThat(vm.uiState.value.isMeasuringElevation).isTrue()
        val flatTime = requireNotNull(vm.uiState.value.travelTimeSeconds)
        val flatBubble = overlayState.overlay.value?.routeTimeBubble?.label

        gate.complete(Unit)
        advanceUntilIdle()

        val measured = vm.uiState.value
        assertThat(measured.isMeasuringElevation).isFalse()
        assertThat(measured.elevationSummary?.gainMeters).isGreaterThan(100.0)
        assertThat(requireNotNull(measured.travelTimeSeconds)).isGreaterThan(flatTime)
        assertThat(overlayState.overlay.value?.routeTimeBubble?.label).isNotEqualTo(flatBubble)

        vm.onAction(TrailMakerAction.SelectRouteColoring(RouteColoring.ELEVATION))
        advanceUntilIdle()
        val stretches = overlayState.overlay.value?.shadedStretches.orEmpty()
        val degradedEnd = overlayState.overlay.value?.legs?.last()?.coordinates?.last()
        assertThat(stretches).isNotEmpty()
        assertThat(stretches.flatMap { it.coordinates }).contains(startCoord)
        assertThat(stretches.flatMap { it.coordinates }).doesNotContain(degradedEnd)

        val savedId = saveTrail(vm, "Alpine Climb")
        val savedHike = requireNotNull(hikeRepo.observeHike(requireNotNull(savedId)).first())
        val firstHeight = requireNotNull(savedHike.route.first().elevationMeters)
        val lastHeight = requireNotNull(savedHike.route.last().elevationMeters)
        assertThat(savedHike.route.all { it.elevationMeters?.isFinite() == true }).isTrue()
        assertThat(lastHeight).isGreaterThan(firstHeight)
    }

    @Test
    fun clearsElevationOnLineMoveScalesAlternativeTimeAndSkipsNonHikingSteepness() = runTest(testDispatcher) {
        val overlayState = TrailDraftOverlayState()
        val initialGate = CompletableDeferred<Unit>()
        val elevationSource = FakeRouteElevationSource(
            handler = { coords ->
                ElevationFetchResult.Heights(coords.mapIndexed { idx, _ -> 400.0 + idx * 360.0 })
            }
        ).apply { gate = initialGate }
        val altPath = TrailLegPath(
            coordinates = listOf(startCoord, GeoCoordinate(47.7210, 12.8290), midCoord),
            distanceMeters = 1_100.0
        )
        val router = FakeTrailLegRouter { ends, _ ->
            TrailLegRoute(
                coordinates = listOf(ends.start, ends.end),
                distanceMeters = 800.0,
                snap = TrailLegSnap.Snapped,
                alternatives = listOf(altPath)
            )
        }
        val vm = createViewModel(router = router, routeElevationSource = elevationSource, overlayState = overlayState)
        advanceUntilIdle()

        addDroppedStop(vm, overlayState, startCoord)
        addDroppedStop(vm, overlayState, midCoord)
        advanceTimeBy(1_000L)
        val flatAltLabel = overlayState.overlay.value?.legs?.first()?.alternatives?.first()?.formattedTime
        assertThat(flatAltLabel).isNotNull()

        initialGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.elevationSummary?.gainMeters).isWithin(1e-6).of(360.0)
        assertThat(overlayState.overlay.value?.shadedStretches).isNotEmpty()
        val climbAltLabel = overlayState.overlay.value?.legs?.first()?.alternatives?.first()?.formattedTime
        assertThat(climbAltLabel).isNotEqualTo(flatAltLabel)

        val secondGate = CompletableDeferred<Unit>()
        elevationSource.gate = secondGate
        addDroppedStop(vm, overlayState, endCoord)
        assertThat(vm.uiState.value.elevationSummary).isNull()
        assertThat(overlayState.overlay.value?.shadedStretches).isEmpty()

        secondGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.elevationSummary?.gainMeters).isWithin(1e-6).of(720.0)

        vm.onAction(TrailMakerAction.SelectTravelMode(TrailTravelMode.CYCLING))
        advanceUntilIdle()
        assertThat(vm.uiState.value.showsColoringSection).isFalse()
        assertThat(overlayState.overlay.value?.shadedStretches).isEmpty()
    }

    private fun firstLegSnappedSecondRefusedRouter(): FakeTrailLegRouter = FakeTrailLegRouter { ends, _ ->
        if (ends.start == startCoord) {
            val mid = GeoCoordinate(
                latitude = (ends.start.latitude + ends.end.latitude) / 2.0,
                longitude = (ends.start.longitude + ends.end.longitude) / 2.0
            )
            TrailLegRoute(
                coordinates = listOf(ends.start, mid, ends.end),
                distanceMeters = 600.0,
                snap = TrailLegSnap.Snapped
            )
        } else {
            TrailLegRoute.straight(ends, TrailLegSnap.Refused(TrailLegOutage.OFFLINE))
        }
    }

    private fun addDroppedStop(vm: TrailMakerViewModel, overlayState: TrailDraftOverlayState, coord: GeoCoordinate) {
        requireNotNull(overlayState.interactionHandler).onDropPin(coord, legEnds = null)
        vm.onAction(TrailMakerAction.AddStopFromPlaceCard)
    }

    private fun kotlinx.coroutines.test.TestScope.saveTrail(vm: TrailMakerViewModel, title: String): HikeId? {
        vm.onAction(TrailMakerAction.OpenSaveDialog)
        vm.onAction(TrailMakerAction.UpdateNameDraft(title))
        var savedId: HikeId? = null
        vm.onConfirmSave { savedId = it }
        advanceUntilIdle()
        return savedId
    }

    private fun createViewModel(
        hikeRepository: FakeHikeRepository = FakeHikeRepository(),
        router: FakeTrailLegRouter = FakeTrailLegRouter(),
        routeElevationSource: RouteElevationSource = FakeRouteElevationSource(),
        overlayState: TrailDraftOverlayState = TrailDraftOverlayState()
    ): TrailMakerViewModel {
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        return TrailMakerViewModel(
            trailDraftRepository = FakeTrailDraftRepository(),
            hikeRepository = hikeRepository,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailLegRouter = router,
            trailGraphProvider = FakeTrailGraphProvider(isAvailable = false),
            routeElevationSource = routeElevationSource,
            stopPlaceSearchSource = FakeStopPlaceSearchSource(),
            locationFeed = FakeLocationFeed(),
            clock = TestClock(1_780_000_000_000L),
            mapClaimCoordinator = MapClaimCoordinator(),
            trailDraftOverlayState = overlayState,
            draftWriteCoordinator = TrailDraftWriteCoordinator(appScope),
            defaultDispatcher = testDispatcher
        )
    }
}
