package app.openhikes.feature.walks

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkSummaryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loadsWalkShowsCoverageOverlayAndClearsOnClear() = runTest {
        val route = sampleRoute()
        val distance = RouteGeometry.routeLengthMeters(route)
        val revision = TrailWalkRecord.routeRevision(route)
        val hike = Hike(
            id = HikeId("h1"),
            title = "Thumsee Loop",
            distanceMeters = distance,
            dateEpochMillis = 1_767_225_600_000L,
            route = route
        )
        val walk = HikeWalk(
            id = "w1",
            hikeId = hike.id,
            startedAtEpochMillis = 1_767_225_600_000L,
            endedAtEpochMillis = 1_767_228_600_000L,
            distanceMeters = distance / 2.0,
            durationSeconds = 3_000.0,
            coveredIntervals = listOf(0.0, distance / 2.0),
            furthestDistanceMeters = distance / 2.0,
            routeDistanceMeters = distance,
            endReason = TrailWalkEndReason.ENDED,
            routeRevision = revision
        )
        val repo = FakeHikeRepository(listOf(hike)).apply { seedWalkForTest(walk) }
        val coordinator = MapClaimCoordinator()
        val overlayState = WalkOverlayState()
        val viewModel = WalkSummaryViewModel(
            rawHikeId = hike.id.value,
            walkId = walk.id,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = coordinator,
            walkOverlayState = overlayState,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
        val store = ViewModelStore().apply { put("summary", viewModel) }

        var collapsed = false
        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isLoaded).isTrue()
            assertThat(loaded.walk?.id).isEqualTo("w1")
            assertThat(loaded.isRouteChanged).isFalse()
            assertThat(loaded.canShowOnMap).isTrue()
            assertThat(loaded.trailAscentMeters).isWithin(1e-6).of(80.0)

            viewModel.onShowOnMap { collapsed = true }
            advanceUntilIdle()
            assertThat(collapsed).isTrue()
            assertThat(overlayState.coverageOverlay.value?.hikeId).isEqualTo(hike.id)
            assertThat(overlayState.coverageOverlay.value?.segments).isNotEmpty()
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(overlayState.coverageOverlay.value).isNull()
        assertThat(coordinator.activeClaim.value).isNull()
    }

    @Test
    fun marksRouteChangedWhenRevisionMismatches() = runTest {
        val route = sampleRoute()
        val distance = RouteGeometry.routeLengthMeters(route)
        val hike = Hike(
            id = HikeId("h1"),
            title = "Thumsee Loop",
            distanceMeters = distance,
            dateEpochMillis = 1_767_225_600_000L,
            route = route
        )
        val walk = HikeWalk(
            id = "w-old",
            hikeId = hike.id,
            startedAtEpochMillis = 1_767_225_600_000L,
            endedAtEpochMillis = 1_767_228_600_000L,
            distanceMeters = distance / 2.0,
            durationSeconds = 3_000.0,
            coveredIntervals = listOf(0.0, distance / 2.0),
            furthestDistanceMeters = distance / 2.0,
            routeDistanceMeters = distance,
            endReason = TrailWalkEndReason.ROUTE_CHANGED,
            routeRevision = "stale-revision"
        )
        val repo = FakeHikeRepository(listOf(hike)).apply { seedWalkForTest(walk) }
        val viewModel = WalkSummaryViewModel(
            rawHikeId = hike.id.value,
            walkId = walk.id,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            walkOverlayState = WalkOverlayState(),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isRouteChanged).isTrue()
            assertThat(loaded.canShowOnMap).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleRoute(): List<RouteCoordinate> = listOf(
        RouteCoordinate(47.7180, 12.8300, elevationMeters = 520.0, timestampEpochMillis = 0L),
        RouteCoordinate(47.7210, 12.8340, elevationMeters = 560.0, timestampEpochMillis = 600_000L),
        RouteCoordinate(47.7240, 12.8380, elevationMeters = 600.0, timestampEpochMillis = 1_200_000L)
    )
}
