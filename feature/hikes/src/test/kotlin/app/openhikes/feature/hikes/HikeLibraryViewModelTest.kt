package app.openhikes.feature.hikes

import app.cash.turbine.test
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeHikeRepository
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
            recordingController = FakeRecordingController(),
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
            recordingController = FakeRecordingController(),
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

    @Test
    fun switchesSortsAndValidatesElevationForHilliest() = runTest {
        val (alpha, beta, gamma) = sampleSortTestHikes()
        val repo = FakeHikeRepository(listOf(alpha, beta, gamma))
        val viewModel = createSortViewModel(repo, FakeRecordingController())

        viewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hikes.map { it.id })
                .containsExactly(gamma.id, beta.id, alpha.id)
                .inOrder()
            assertThat(repo.validateElevationCalls).isEqualTo(0)

            viewModel.onSelectSort(HikeListSort.LONGEST)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hikes.map { it.id })
                .containsExactly(alpha.id, gamma.id, beta.id)
                .inOrder()

            viewModel.onSelectSort(HikeListSort.HILLIEST)
            advanceUntilIdle()
            assertThat(repo.validateElevationCalls).isGreaterThan(0)
            assertThat(expectMostRecentItem().hikes.map { it.id })
                .containsExactly(beta.id, gamma.id, alpha.id)
                .inOrder()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun supportsCustomReorderingWithActivePinningAndResetOnSort() = runTest {
        val (alpha, beta, gamma) = sampleSortTestHikes()
        val repo = FakeHikeRepository(listOf(alpha, beta, gamma))
        val recordingController = FakeRecordingController()
        val viewModel = createSortViewModel(repo, recordingController)

        viewModel.uiState.test {
            advanceUntilIdle()
            recordingController.setSessionState(
                RecordingSessionState(sessionId = alpha.id.value, phase = RecordingPhase.RECORDING)
            )
            advanceUntilIdle()
            val pinned = expectMostRecentItem()
            assertThat(pinned.activeHikeId).isEqualTo(alpha.id)
            assertThat(pinned.hikes.map { it.id }).containsExactly(alpha.id, gamma.id, beta.id).inOrder()

            viewModel.onEnterReorderMode()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().isReordering).isTrue()

            viewModel.onMoveHike(0, 1)
            advanceUntilIdle()
            expectNoEvents()
            assertThat(viewModel.uiState.value.isCustomOrder).isFalse()

            viewModel.onMoveHike(2, 1)
            advanceUntilIdle()
            val reordered = expectMostRecentItem()
            assertThat(reordered.isCustomOrder).isTrue()
            assertThat(reordered.hikes.map { it.id }).containsExactly(alpha.id, beta.id, gamma.id).inOrder()

            viewModel.onExitReorderMode()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().isReordering).isFalse()

            viewModel.onSelectSort(HikeListSort.ALPHABETICAL)
            advanceUntilIdle()
            val sortedAlpha = expectMostRecentItem()
            assertThat(sortedAlpha.isCustomOrder).isFalse()
            assertThat(sortedAlpha.hikes.map { it.id }).containsExactly(alpha.id, beta.id, gamma.id).inOrder()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectingSortSerializesBehindInFlightMoveAndAbortsWhenResetFails() = runTest {
        val (alpha, beta, gamma) = sampleSortTestHikes()
        val repo = FakeHikeRepository(listOf(alpha, beta, gamma))
        val settingsRepo = FakeUserSettingsRepository()
        val viewModel = HikeLibraryViewModel(
            hikeRepository = repo,
            userSettingsRepository = settingsRepo,
            recordingController = FakeRecordingController(),
            trailWalkController = FakeTrailWalkController(),
            mapClaimCoordinator = MapClaimCoordinator(),
            routeHighlightState = RouteHighlightState()
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            expectMostRecentItem()

            // Move while save is suspended, then exit reorder and choose Longest immediately.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            repo.saveOrderGate = gate
            viewModel.onEnterReorderMode()
            viewModel.onMoveHike(2, 0)
            viewModel.onExitReorderMode()
            viewModel.onSelectSort(HikeListSort.LONGEST)
            advanceUntilIdle()

            gate.complete(Unit)
            repo.saveOrderGate = null
            advanceUntilIdle()
            val afterRace = expectMostRecentItem()
            assertThat(afterRace.isCustomOrder).isFalse()
            assertThat(afterRace.sort).isEqualTo(HikeListSort.LONGEST)
            assertThat(afterRace.hikes.map { it.id }).containsExactly(alpha.id, gamma.id, beta.id).inOrder()

            // Re-enter custom order, fail clearCustomHikeOrder, and verify preference is unchanged.
            viewModel.onMoveHike(2, 0)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().isCustomOrder).isTrue()

            repo.nextOrderFailure = IOException("Disk locked")
            viewModel.onSelectSort(HikeListSort.ALPHABETICAL)
            advanceUntilIdle()
            val failedReset = expectMostRecentItem()
            assertThat(failedReset.actionError).isEqualTo(HikeLibraryViewModel.SORT_ERROR_KEY)
            assertThat(failedReset.isCustomOrder).isTrue()
            assertThat(failedReset.sort).isEqualTo(HikeListSort.LONGEST)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createSortViewModel(
        repo: FakeHikeRepository,
        recordingController: FakeRecordingController
    ): HikeLibraryViewModel = HikeLibraryViewModel(
        hikeRepository = repo,
        userSettingsRepository = FakeUserSettingsRepository(),
        recordingController = recordingController,
        trailWalkController = FakeTrailWalkController(),
        mapClaimCoordinator = MapClaimCoordinator(),
        routeHighlightState = RouteHighlightState()
    )

    private fun sampleSortTestHikes(): Triple<Hike, Hike, Hike> = Triple(
        Hike(
            id = HikeId("alpha"),
            title = "Alpha Flat",
            distanceMeters = 8_000.0,
            dateEpochMillis = 10_000L,
            route = listOf(
                RouteCoordinate(47.70, 12.80, elevationMeters = 500.0),
                RouteCoordinate(47.71, 12.81, elevationMeters = 510.0)
            )
        ),
        Hike(
            id = HikeId("beta"),
            title = "Beta Steep",
            distanceMeters = 3_000.0,
            dateEpochMillis = 20_000L,
            route = listOf(
                RouteCoordinate(47.72, 12.82, elevationMeters = 500.0),
                RouteCoordinate(47.73, 12.83, elevationMeters = 1_100.0)
            )
        ),
        Hike(
            id = HikeId("gamma"),
            title = "Gamma Newest",
            distanceMeters = 5_000.0,
            dateEpochMillis = 30_000L,
            route = listOf(
                RouteCoordinate(47.74, 12.84, elevationMeters = 600.0),
                RouteCoordinate(47.75, 12.85, elevationMeters = 750.0)
            )
        )
    )
}
