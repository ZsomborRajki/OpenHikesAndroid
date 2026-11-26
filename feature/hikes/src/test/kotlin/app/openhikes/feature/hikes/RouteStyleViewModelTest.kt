package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteStyleViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun updatesTintBorderWidthAndPatternAndReleasesClaimOnClear() = runTest {
        val hike = sampleHike()
        val repo = FakeHikeRepository(listOf(hike))
        val coordinator = MapClaimCoordinator()
        val viewModel = RouteStyleViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = repo,
            mapClaimCoordinator = coordinator
        )
        val store = ViewModelStore().apply { put("style", viewModel) }

        viewModel.uiState.test {
            awaitItem() // initial
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isLoaded).isTrue()
            assertThat(coordinator.activeClaim.value?.selectedHikeId).isEqualTo(hike.id)

            viewModel.onTintSelected("#D85A17")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().tintHex).isEqualTo("#D85A17")

            viewModel.onBorderSelected(RouteBorder.WHITE_HEX)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().borderHex).isEqualTo(RouteBorder.WHITE_HEX)

            viewModel.onWidthChanged(7.0)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().widthDp).isEqualTo(7.0)

            viewModel.onPatternSelected(RouteLinePattern.DASHED)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().linePattern).isEqualTo(RouteLinePattern.DASHED)

            repo.nextStyleFailure = IOException("Read-only")
            viewModel.onPatternSelected(RouteLinePattern.DOTTED)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hasSaveError).isTrue()
            viewModel.onDismissError()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hasSaveError).isFalse()

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
    }

    @Test
    fun retainsNewerQueuedEditWhenInFlightWriteFails() = runTest {
        val repo = ControllableStyleRepository(sampleHike())
        val viewModel = RouteStyleViewModel("h1", repo, MapClaimCoordinator())

        viewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().widthDp).isEqualTo(HikeSummary.DEFAULT_ROUTE_WIDTH_DP)

            val firstWriteGate = CompletableDeferred<Result<Unit>>()
            val secondWriteGate = CompletableDeferred<Result<Unit>>()
            repo.enqueueGate(firstWriteGate)
            repo.enqueueGate(secondWriteGate)

            viewModel.onWidthChanged(7.0)
            advanceUntilIdle()
            viewModel.onPatternSelected(RouteLinePattern.DASHED)
            advanceUntilIdle()

            // First write fails while pattern edit is queued behind it; 7dp + DASHED must remain intact.
            firstWriteGate.complete(Result.failure(IOException("Transient disk error")))
            advanceUntilIdle()
            val afterFirstFailure = expectMostRecentItem()
            assertThat(afterFirstFailure.widthDp).isEqualTo(7.0)
            assertThat(afterFirstFailure.linePattern).isEqualTo(RouteLinePattern.DASHED)
            assertThat(afterFirstFailure.hasSaveError).isTrue()

            secondWriteGate.complete(Result.success(Unit))
            advanceUntilIdle()
            val settled = expectMostRecentItem()
            assertThat(settled.widthDp).isEqualTo(7.0)
            assertThat(settled.linePattern).isEqualTo(RouteLinePattern.DASHED)
            assertThat(settled.hasSaveError).isFalse()
            assertThat(repo.recordedStyles.last()).isEqualTo(
                RouteStyleUpdate(sampleHike().tintHex, sampleHike().routeBorderHex, 7.0, RouteLinePattern.DASHED)
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun preservesLastSavedStyleBeforeDbObservationCatchesUpAndRevertsOnlyFailedEdit() = runTest {
        val repo = ControllableStyleRepository(sampleHike())
        val viewModel = RouteStyleViewModel("h1", repo, MapClaimCoordinator())

        viewModel.uiState.test {
            advanceUntilIdle()

            val firstGate = CompletableDeferred<Result<Unit>>()
            val secondGate = CompletableDeferred<Result<Unit>>()
            val thirdGate = CompletableDeferred<Result<Unit>>()
            repo.enqueueGate(firstGate)
            repo.enqueueGate(secondGate)
            repo.enqueueGate(thirdGate)

            viewModel.onWidthChanged(7.0)
            advanceUntilIdle()
            firstGate.complete(Result.success(Unit))
            advanceUntilIdle()

            // DB still observes 3dp, yet the next edit must layer on top of saved 7dp.
            viewModel.onTintSelected("#D85A17")
            advanceUntilIdle()
            secondGate.complete(Result.success(Unit))
            advanceUntilIdle()
            assertThat(repo.recordedStyles.last().routeWidthDp).isEqualTo(7.0)
            assertThat(repo.recordedStyles.last().tintHex).isEqualTo("#D85A17")

            // If a subsequent edit fails before DB catches up, revert only to 7dp / #D85A17.
            viewModel.onPatternSelected(RouteLinePattern.DOTTED)
            advanceUntilIdle()
            thirdGate.complete(Result.failure(IOException("Write failed")))
            advanceUntilIdle()

            val afterFailure = expectMostRecentItem()
            assertThat(afterFailure.hasSaveError).isTrue()
            assertThat(afterFailure.widthDp).isEqualTo(7.0)
            assertThat(afterFailure.tintHex).isEqualTo("#D85A17")
            assertThat(afterFailure.linePattern).isEqualTo(RouteLinePattern.Default)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleHike(): Hike = Hike(
        id = HikeId("h1"),
        title = "Style Loop",
        distanceMeters = 500.0,
        dateEpochMillis = 10_000L,
        route = listOf(RouteCoordinate(47.7, 12.8), RouteCoordinate(47.71, 12.81))
    )

    private class ControllableStyleRepository(initialHike: Hike) : HikeRepository {
        private val hikeFlow = MutableStateFlow<Hike?>(initialHike)
        private val writeGates = ArrayDeque<CompletableDeferred<Result<Unit>>>()
        val recordedStyles = mutableListOf<RouteStyleUpdate>()

        fun enqueueGate(gate: CompletableDeferred<Result<Unit>>) {
            writeGates.addLast(gate)
        }

        override val hikes: Flow<List<HikeSummary>> = MutableStateFlow(emptyList())
        override val mapRoutes: Flow<List<HikeMapRoute>> = MutableStateFlow(emptyList())

        override fun observeHike(hikeId: HikeId): Flow<Hike?> = hikeFlow.map { it?.takeIf { h -> h.id == hikeId } }
        override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> = MutableStateFlow(null)
        override fun observeWalks(hikeId: HikeId): Flow<List<app.openhikes.core.model.HikeWalk>> =
            MutableStateFlow(emptyList())
        override fun observeWalk(walkId: String): Flow<app.openhikes.core.model.HikeWalk?> = MutableStateFlow(null)
        override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult = error("Unused")
        override suspend fun importGpxUri(uriString: String): GpxImportResult = error("Unused")
        override suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx> = error("Unused")
        override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit> = error("Unused")
        override suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit> = error("Unused")
        override suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun saveWalkInProgress(
            hikeId: HikeId,
            record: app.openhikes.core.model.TrailWalkRecord?
        ): Result<Unit> = Result.success(Unit)
        override suspend fun getOpenWalksInProgress(): Result<List<app.openhikes.core.model.TrailWalkRecord>> =
            Result.success(emptyList())
        override suspend fun finishWalk(
            record: app.openhikes.core.model.TrailWalkRecord,
            endedAtEpochMillis: Long,
            reason: app.openhikes.core.model.TrailWalkEndReason
        ): Result<app.openhikes.core.model.HikeWalk?> = Result.success(null)
        override suspend fun deleteHike(hikeId: HikeId): Result<Unit> = error("Unused")
        override suspend fun saveDrawnTrail(
            title: String,
            distanceMeters: Double,
            route: List<RouteCoordinate>,
            madeOnEpochMillis: Long
        ): Result<Hike> = error("Unused")
        override suspend fun createOrClaimRecordingDraft(
            hikeId: HikeId,
            startedAtEpochMillis: Long,
            title: String
        ): Result<Hike> = error("Unused")

        override suspend fun finalizeRecordingDraft(
            hikeId: HikeId,
            title: String,
            customName: String?,
            startedAtEpochMillis: Long,
            endedAtEpochMillis: Long,
            prepared: app.openhikes.core.model.PreparedRecording
        ): Result<Hike> = error("Unused")

        override suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit> = error("Unused")
        override suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?): Result<Int> = error("Unused")
        override suspend fun loadRouteEditSources(hikeId: HikeId) = error("Unused")
        override suspend fun applyRouteEdit(
            hikeId: HikeId,
            expectedCurrentRoute: List<RouteCoordinate>,
            edited: app.openhikes.core.model.EditedLine
        ) = error("Unused")
        override suspend fun revertRouteEdit(hikeId: HikeId, expectedCurrentRoute: List<RouteCoordinate>) =
            error("Unused")
        override suspend fun seedFixtureIfConfigured(): HikeId? = null

        override suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit> {
            recordedStyles.add(style)
            val gate = writeGates.removeFirst()
            return gate.await()
        }
    }
}
