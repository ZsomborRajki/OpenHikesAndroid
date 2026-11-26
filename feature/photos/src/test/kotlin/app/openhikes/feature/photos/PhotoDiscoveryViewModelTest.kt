package app.openhikes.feature.photos

import app.openhikes.core.data.PhotoLibraryAccess
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PhotoLibraryAsset
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEdit
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakePhotoLibrarySource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoDiscoveryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun untimedRouteReportsUnsupportedWithoutPromptingPermissions() = runTest {
        val hikeId = HikeId("untimed")
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Drawn Trail",
                    distanceMeters = 800.0,
                    dateEpochMillis = BASE_TIME_MS,
                    route = listOf(
                        RouteCoordinate(47.7184, 12.8317),
                        RouteCoordinate(47.7210, 12.8330)
                    )
                )
            )
        )
        val library = FakePhotoLibrarySource(access = PhotoLibraryAccess.DENIED)
        val viewModel = PhotoDiscoveryViewModel(
            rawHikeId = hikeId.value,
            hikeRepository = repo,
            photoLibrarySource = library,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        assertThat(viewModel.uiState.value.phase).isEqualTo(PhotoDiscoveryPhase.Unsupported)
        assertThat(viewModel.uiState.value.shouldRequestPermissionsToken).isEqualTo(0L)
        assertThat(library.queryCount).isEqualTo(0)
    }

    @Test
    fun promptsOnceWhenDeniedThenTransitionsToAccessDeniedOrResults() = runTest {
        val hikeId = HikeId("h1")
        val repo = FakeHikeRepository(listOf(timedHike(hikeId)))
        val library = FakePhotoLibrarySource(
            access = PhotoLibraryAccess.DENIED,
            initialAssets = sampleMatchingAssets()
        )
        val viewModel = PhotoDiscoveryViewModel(
            rawHikeId = hikeId.value,
            hikeRepository = repo,
            photoLibrarySource = library,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        assertThat(viewModel.uiState.value.shouldRequestPermissionsToken).isGreaterThan(0L)
        viewModel.onAction(PhotoDiscoveryAction.ConsumePermissionRequest)

        // User denies permission -> AccessDenied.
        viewModel.onAction(PhotoDiscoveryAction.PermissionResultReceived)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.phase).isEqualTo(PhotoDiscoveryPhase.AccessDenied)

        // User grants permission and retries -> Results populated.
        library.access = PhotoLibraryAccess.GRANTED
        viewModel.onAction(PhotoDiscoveryAction.RetrySearch)
        advanceUntilIdle()
        val results = viewModel.uiState.value.phase as? PhotoDiscoveryPhase.Results
        assertThat(results).isNotNull()
        assertThat(results?.matches).hasSize(2)
        assertThat(results?.allSelected).isTrue()
    }

    @Test
    fun togglesSelectionImportsSequentiallyAndAutoClosesOnFullSuccess() = runTest {
        val hikeId = HikeId("h1")
        val repo = FakeHikeRepository(listOf(timedHike(hikeId)))
        val library = FakePhotoLibrarySource(
            access = PhotoLibraryAccess.GRANTED,
            initialAssets = sampleMatchingAssets()
        )
        val viewModel = PhotoDiscoveryViewModel(
            rawHikeId = hikeId.value,
            hikeRepository = repo,
            photoLibrarySource = library,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        val initial = viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results
        assertThat(initial.selectedCount).isEqualTo(2)

        // Deselect all -> Add disabled (0 selected), then select all again.
        viewModel.onAction(PhotoDiscoveryAction.ToggleSelectAll)
        assertThat((viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results).selectedCount).isEqualTo(0)
        viewModel.onAction(PhotoDiscoveryAction.ToggleSelectAll)
        assertThat((viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results).selectedCount).isEqualTo(2)

        // Import all -> auto-closes and saves both photos on the hike.
        viewModel.onAction(PhotoDiscoveryAction.AddSelected)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.shouldClose).isTrue()
        val savedHike = repo.observeHike(hikeId).first()
        assertThat(savedHike?.photos).hasSize(2)
    }

    @Test
    fun partialFailureKeepsFailedItemSelectedForRetry() = runTest {
        val hikeId = HikeId("h1")
        val repo = FakeHikeRepository(listOf(timedHike(hikeId)))
        val assets = sampleMatchingAssets()
        val library = FakePhotoLibrarySource(
            access = PhotoLibraryAccess.GRANTED,
            initialAssets = assets
        ).apply {
            failingReadIds.add("asset-2")
        }
        val viewModel = PhotoDiscoveryViewModel(
            rawHikeId = hikeId.value,
            hikeRepository = repo,
            photoLibrarySource = library,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        viewModel.onAction(PhotoDiscoveryAction.AddSelected)
        advanceUntilIdle()

        val results = viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results
        assertThat(viewModel.uiState.value.shouldClose).isFalse()
        assertThat(results.importFailed).isTrue()
        assertThat(results.matches.map { it.id }).containsExactly("asset-2")
        assertThat(results.selectedIds).containsExactly("asset-2")
        assertThat(repo.observeHike(hikeId).first()?.photos).hasSize(1)
    }

    @Test
    fun limitedEmptyExposesSelectMoreActionAndLiveRouteChangePreservesUntickedSelection() = runTest {
        val hikeId = HikeId("h1")
        val initialHike = timedHike(hikeId)
        val repo = FakeHikeRepository(listOf(initialHike))
        val library = FakePhotoLibrarySource(
            access = PhotoLibraryAccess.LIMITED,
            initialAssets = emptyList()
        )
        val viewModel = PhotoDiscoveryViewModel(
            rawHikeId = hikeId.value,
            hikeRepository = repo,
            photoLibrarySource = library,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        assertThat(viewModel.uiState.value.phase).isEqualTo(PhotoDiscoveryPhase.Empty(isLimitedAccess = true))
        viewModel.onAction(PhotoDiscoveryAction.SelectMorePhotos)
        assertThat(viewModel.uiState.value.shouldRequestLimitedMoreToken).isGreaterThan(0L)

        // Populate matches, untick asset-1, then edit the route to trigger a live fingerprint refresh.
        library.assets = sampleMatchingAssets()
        viewModel.onAction(PhotoDiscoveryAction.PermissionResultReceived)
        advanceUntilIdle()
        viewModel.onAction(PhotoDiscoveryAction.ToggleSelection("asset-1"))
        val beforeEdit = viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results
        assertThat(beforeEdit.selectedIds).containsExactly("asset-2")

        val editedRoute = initialHike.route + RouteCoordinate(
            latitude = 47.7225,
            longitude = 12.8345,
            timestampEpochMillis = BASE_TIME_MS + 180_000L
        )
        repo.applyRouteEdit(
            hikeId = hikeId,
            expectedCurrentRoute = initialHike.route,
            edited = EditedLine(
                route = editedRoute,
                lengthMeters = 1_500.0,
                removedMeters = 0.0,
                removedSeconds = 0.0,
                cuts = EditedLine.Cuts(range = null),
                steps = emptyList(),
                notOnFoot = RouteEdit.NotOnFoot(isChecked = true),
                start = EditedLine.ClockEnd.Unchanged,
                end = EditedLine.ClockEnd.Unchanged,
                revision = TrailWalkRecord.routeRevision(editedRoute)
            )
        )
        advanceUntilIdle()
        val afterEdit = viewModel.uiState.value.phase as PhotoDiscoveryPhase.Results
        assertThat(afterEdit.selectedIds).containsExactly("asset-2")
    }

    private fun timedHike(hikeId: HikeId): Hike = Hike(
        id = hikeId,
        title = "Thumsee Loop",
        distanceMeters = 1_200.0,
        dateEpochMillis = BASE_TIME_MS,
        route = listOf(
            RouteCoordinate(47.7184, 12.8317, timestampEpochMillis = BASE_TIME_MS),
            RouteCoordinate(47.7198, 12.8322, timestampEpochMillis = BASE_TIME_MS + 60_000L),
            RouteCoordinate(47.7212, 12.8330, timestampEpochMillis = BASE_TIME_MS + 120_000L)
        )
    )

    private fun sampleMatchingAssets(): List<PhotoLibraryAsset> = listOf(
        PhotoLibraryAsset(
            localIdentifier = "asset-1",
            createdAtEpochMillis = BASE_TIME_MS + 30_000L,
            coordinate = GeoCoordinate(47.7191, 12.8319)
        ),
        PhotoLibraryAsset(
            localIdentifier = "asset-2",
            createdAtEpochMillis = BASE_TIME_MS + 90_000L,
            coordinate = GeoCoordinate(47.7205, 12.8326)
        )
    )

    private companion object {
        const val BASE_TIME_MS = 1_750_000_000_000L
    }
}
