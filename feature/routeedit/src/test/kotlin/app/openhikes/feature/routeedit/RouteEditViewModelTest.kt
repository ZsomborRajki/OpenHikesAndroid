package app.openhikes.feature.routeedit

import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteTrimHandle
import app.openhikes.core.model.RoutedStretchWay
import app.openhikes.core.testing.FakeRouteStretchRouter
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun walkThenDriveHike(id: HikeId = HikeId("walk-drive")): Hike {
        val parsed = GpxParser.parseAll(TestFixtures.thumseeWalkThenDriveGpxBytes()) as GpxParseResult.Success
        val track = parsed.value.first()
        return Hike(
            id = id,
            title = track.name.orEmpty(),
            distanceMeters = track.distanceMeters,
            dateEpochMillis = track.startTimeEpochMillis ?: 0L,
            route = track.route
        )
    }

    private fun loopHike(id: HikeId = HikeId("loop-hike")): Hike {
        val parsed = GpxParser.parseAll(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val track = parsed.value.first()
        return Hike(
            id = id,
            title = track.name.orEmpty(),
            distanceMeters = track.distanceMeters,
            dateEpochMillis = track.startTimeEpochMillis ?: 0L,
            route = track.route
        )
    }

    @Test
    fun oneTapDriveRemovalSavesAndRevertRestoresOriginalAndNotifiesWalkController() = runTest(testDispatcher) {
        val hike = walkThenDriveHike()
        val hikeRepository = FakeHikeRepository(listOf(hike))
        val walkController = FakeTrailWalkController()
        val overlayState = RouteEditOverlayState()
        val viewModel = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = hikeRepository,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = walkController,
            routeStretchRouter = FakeRouteStretchRouter(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = overlayState,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.trailingCarriedOfferMeters).isNotNull()
        assertThat(viewModel.uiState.value.hasChanges).isFalse()
        assertThat(viewModel.uiState.value.hasOriginal).isFalse()
        assertThat(overlayState.overlay.value).isNotNull()

        viewModel.onAction(RouteEditAction.RemoveCarriedEnd)
        assertThat(viewModel.uiState.value.trailingCarriedOfferMeters).isNull()
        assertThat(viewModel.uiState.value.hasChanges).isTrue()

        var savedDone = false
        viewModel.onSave { savedDone = true }
        advanceUntilIdle()

        assertThat(savedDone).isTrue()
        assertThat(walkController.routeChangedHikeIds).containsExactly(hike.id)
        val savedHike = hikeRepository.observeHike(hike.id).first()!!
        assertThat(savedHike.distanceMeters).isLessThan(hike.distanceMeters / 2.0)

        // Reopening shows Revert to Original and no drive offer.
        val secondVisit = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = hikeRepository,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = walkController,
            routeStretchRouter = FakeRouteStretchRouter(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = overlayState,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertThat(secondVisit.uiState.value.hasOriginal).isTrue()
        assertThat(secondVisit.uiState.value.trailingCarriedOfferMeters).isNull()

        var revertedDone = false
        secondVisit.onConfirmRevert { revertedDone = true }
        advanceUntilIdle()

        assertThat(revertedDone).isTrue()
        val restored = hikeRepository.observeHike(hike.id).first()!!
        assertThat(restored.route).isEqualTo(hike.route)
        assertThat(restored.distanceMeters).isWithin(0.1).of(hike.distanceMeters)
    }

    @Test
    fun stretchFollowTrailsHandlesUnmappedRefusalAndMappedSuccessWithUndo() = runTest(testDispatcher) {
        val hike = walkThenDriveHike()
        val hikeRepository = FakeHikeRepository(listOf(hike))
        val router = FakeRouteStretchRouter()
        val viewModel = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = hikeRepository,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            routeStretchRouter = router,
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = RouteEditOverlayState(),
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.onAction(RouteEditAction.BeginStretch)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isSelectingStretch).isTrue()
        assertThat(viewModel.uiState.value.canSave).isFalse()

        // Default unmapped router refuses with NO_MAPPED_WAY.
        viewModel.onAction(RouteEditAction.FollowTrails)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.refusal).isEqualTo(RouteEditRefusal.NO_MAPPED_WAY)

        viewModel.onAction(RouteEditAction.DismissRefusal)
        router.handler = { from, to, _ ->
            val mid = GeoCoordinate((from.latitude + to.latitude) / 2.0 + 0.002, (from.longitude + to.longitude) / 2.0)
            RoutedStretchWay(coordinates = listOf(from, mid, to), isMapped = true)
        }
        viewModel.onAction(RouteEditAction.FollowTrails)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isSelectingStretch).isFalse()
        assertThat(viewModel.uiState.value.hasSteps).isTrue()
        assertThat(viewModel.uiState.value.canUndo).isTrue()
        assertThat(viewModel.uiState.value.canSave).isTrue()

        viewModel.onAction(RouteEditAction.UndoStep)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasSteps).isFalse()
        assertThat(viewModel.uiState.value.hasChanges).isFalse()
    }

    @Test
    fun loopSupportsStartLoopHereOnlyWhenStartIsCutAndEndIsUncut() = runTest(testDispatcher) {
        val hike = loopHike()
        val viewModel = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = FakeHikeRepository(listOf(hike)),
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            routeStretchRouter = FakeRouteStretchRouter(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = RouteEditOverlayState(),
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoop).isTrue()
        assertThat(viewModel.uiState.value.canStartLoopAtCut).isFalse()

        val total = viewModel.rangeState.value.totalMeters
        viewModel.onAction(RouteEditAction.MoveHandle(isStart = true, meters = total * 0.25))
        assertThat(viewModel.uiState.value.canStartLoopAtCut).isTrue()

        viewModel.onAction(RouteEditAction.StartLoopHere)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.hasSteps).isTrue()
        assertThat(viewModel.rangeState.value.startMeters).isEqualTo(0.0)
        assertThat(viewModel.rangeState.value.endMeters).isWithin(5.0).of(total)
    }

    @Test
    fun mapHandleDragAndLinePullUpdateSession() = runTest(testDispatcher) {
        val hike = walkThenDriveHike()
        val overlayState = RouteEditOverlayState()
        val viewModel = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = FakeHikeRepository(listOf(hike)),
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            routeStretchRouter = FakeRouteStretchRouter(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = overlayState,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.interactionHandler)
        val midDrive = hike.route[hike.route.size / 2].coordinate
        val initialEnd = viewModel.rangeState.value.endMeters
        handler.onDragHandle(RouteTrimHandle.END, midDrive)
        assertThat(viewModel.rangeState.value.endMeters).isLessThan(initialEnd)

        // Reset handle and perform a map pull off the drive leg.
        viewModel.onAction(RouteEditAction.MoveHandle(isStart = false, meters = initialEnd))
        val pullStart = hike.route[15].coordinate
        val pullTarget = GeoCoordinate(pullStart.latitude + 0.004, pullStart.longitude)
        val pullTargetTwo = GeoCoordinate(pullStart.latitude + 0.006, pullStart.longitude + 0.001)
        assertThat(handler.onBeginPull(pullStart, reachMeters = 400.0)).isTrue()
        val overlayBeforeMove = requireNotNull(overlayState.overlay.value)
        handler.onMovePull(pullTarget)
        val overlayAfterMoveOne = requireNotNull(overlayState.overlay.value)
        handler.onMovePull(pullTargetTwo)
        val overlayAfterMoveTwo = requireNotNull(overlayState.overlay.value)
        assertThat(overlayAfterMoveTwo.pullBandCoordinates).hasSize(3)
        assertThat(overlayAfterMoveTwo.pullBandCoordinates[1]).isEqualTo(pullTargetTwo)
        assertThat(overlayAfterMoveTwo.keptWalkedSegments).isSameInstanceAs(overlayBeforeMove.keptWalkedSegments)
        assertThat(overlayAfterMoveTwo.cutLeadingSegments).isSameInstanceAs(overlayBeforeMove.cutLeadingSegments)
        assertThat(overlayAfterMoveTwo.cutTrailingSegments).isSameInstanceAs(overlayBeforeMove.cutTrailingSegments)
        assertThat(overlayAfterMoveTwo.pullCoverSegments).isSameInstanceAs(overlayAfterMoveOne.pullCoverSegments)
        handler.onEndPull(pullTargetTwo, movedEnough = true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasSteps).isTrue()
        assertThat(viewModel.uiState.value.canSave).isTrue()
        assertThat(RouteGeometry.routeLengthMeters(hike.route)).isGreaterThan(0.0)
    }

    @Test
    fun followTrailsIsDisabledAndIgnoredWhenRouterDoesNotSupportMappedRouting() = runTest(testDispatcher) {
        val hike = walkThenDriveHike()
        val router = FakeRouteStretchRouter(supportsMappedRouting = false)
        val viewModel = RouteEditViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = FakeHikeRepository(listOf(hike)),
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            routeStretchRouter = router,
            mapClaimCoordinator = MapClaimCoordinator(),
            routeEditOverlayState = RouteEditOverlayState(),
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.onAction(RouteEditAction.BeginStretch)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canFollowTrails).isFalse()

        viewModel.onAction(RouteEditAction.FollowTrails)
        advanceUntilIdle()
        assertThat(router.requests).isEmpty()
        assertThat(viewModel.uiState.value.refusal).isNull()
    }
}
