package app.openhikes.feature.hikes

import app.cash.turbine.test
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeHikeRepository
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
class HikeLibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun importsValidAndFailingGpxAndReflectsSelectedHikeClaim() = runTest {
        val repo = FakeHikeRepository()
        repo.registerUriPayload(
            uriString = "content://docs/thumsee.gpx",
            bytes = TestFixtures.thumseeLoopFastGpxBytes(),
            fileName = "thumsee.gpx"
        )
        val coordinator = MapClaimCoordinator()
        val viewModel = HikeLibraryViewModel(
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            mapClaimCoordinator = coordinator,
            routeHighlightState = RouteHighlightState()
        )

        viewModel.uiState.test {
            assertThat(awaitItem().hikes).isEmpty()

            viewModel.onImportGpxUri("content://docs/missing.gpx")
            val importingOrError = awaitItem()
            val failed = if (importingOrError.importError != null) {
                importingOrError
            } else {
                awaitItem()
            }
            assertThat(failed.importError).isEqualTo(GpxImportFailure.UNREADABLE)

            viewModel.onDismissImportError()
            assertThat(awaitItem().importError).isNull()

            viewModel.onImportGpxUri("content://docs/thumsee.gpx")
            advanceUntilIdle()
            val latest = expectMostRecentItem()
            assertThat(latest.hikes).hasSize(1)
            val hikeId = latest.hikes.first().id

            coordinator.registerClaim(MapClaim(routeKey = "detail", depth = 1, selectedHikeId = hikeId))
            assertThat(awaitItem().selectedHikeId).isEqualTo(hikeId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteConfirmationClearsHighlightAndInvokesCallbackOnlyOnSuccess() = runTest {
        val sampleHike = Hike(
            id = HikeId("h1"),
            title = "Morning Ridge",
            distanceMeters = 1200.0,
            dateEpochMillis = 10_000L,
            route = listOf(RouteCoordinate(47.7, 12.8), RouteCoordinate(47.71, 12.81))
        )
        val repo = FakeHikeRepository(listOf(sampleHike))
        val highlightState = RouteHighlightState().apply {
            move(MapRouteHighlight(sampleHike.id, GeoCoordinate(47.7, 12.8), "#1B7F3B"))
        }
        val viewModel = HikeLibraryViewModel(
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailWalkController = FakeTrailWalkController(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeHighlightState = highlightState
        )

        val deletedIds = mutableListOf<HikeId>()
        viewModel.uiState.test {
            val loaded = awaitItem().let { if (it.hikes.isEmpty()) awaitItem() else it }
            val target = loaded.hikes.single()

            viewModel.onRequestDelete(target)
            assertThat(awaitItem().pendingDeleteHike).isEqualTo(target)
            viewModel.onCancelDelete()
            assertThat(awaitItem().pendingDeleteHike).isNull()

            repo.nextDeleteFailure = IOException("Locked")
            viewModel.onRequestDelete(target)
            awaitItem()
            viewModel.onConfirmDelete { deletedIds.add(it) }
            advanceUntilIdle()
            val errorState = expectMostRecentItem()
            assertThat(errorState.actionError).isEqualTo(HikeLibraryViewModel.DELETE_ERROR_KEY)
            assertThat(deletedIds).isEmpty()

            viewModel.onDismissActionError()
            awaitItem()
            viewModel.onRequestDelete(target)
            awaitItem()
            viewModel.onConfirmDelete { deletedIds.add(it) }
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem()
            assertThat(afterDelete.hikes).isEmpty()
            assertThat(deletedIds).containsExactly(sampleHike.id)
            assertThat(highlightState.highlight.value).isNull()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
