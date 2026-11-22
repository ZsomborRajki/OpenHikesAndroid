package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
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
        val viewModel = HikeDetailViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = coordinator,
            routeHighlightState = highlightState,
            defaultDispatcher = mainDispatcherRule.testDispatcher
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
