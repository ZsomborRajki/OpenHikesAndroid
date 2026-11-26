package app.openhikes.feature.photos

import androidx.lifecycle.ViewModelStore
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoLicense
import app.openhikes.core.model.PhotoUnavailableReason
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HikePhotoViewerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun ordersChronologicallyAndTracksShareability() = runTest {
        val harness = createViewerHarness()
        advanceUntilIdle()

        val initial = harness.viewModel.uiState.value
        assertThat(initial.photos.map { it.id }).containsExactly("p1", "p2", "p3").inOrder()
        assertThat(initial.currentPhotoId).isEqualTo("p1")
        assertThat(initial.canShareCurrent).isTrue()
        assertThat(initial.canStepPrevious).isFalse()
        assertThat(initial.canStepNext).isTrue()

        harness.viewModel.onAction(HikePhotoViewerAction.StepNext)
        harness.viewModel.onAction(HikePhotoViewerAction.StepNext)
        advanceUntilIdle()
        assertThat(harness.viewModel.uiState.value.currentPhotoId).isEqualTo("p3")
        assertThat(harness.viewModel.uiState.value.canShareCurrent).isFalse()
        assertThat(harness.viewModel.uiState.value.canStepNext).isFalse()
    }

    @Test
    fun pagesWithMapFollowReExpandsOnTapOrCalloutAndCleansUpOnClear() = runTest {
        val harness = createViewerHarness()
        val store = ViewModelStore().apply { put("viewer", harness.viewModel) }
        advanceUntilIdle()

        // Show on map selects pin, emits FitBounds, sets route highlight, and requests reveal token.
        harness.viewModel.onAction(HikePhotoViewerAction.ShowOnMap)
        advanceUntilIdle()
        assertThat(harness.viewModel.uiState.value.isMapRevealed).isTrue()
        assertThat(harness.viewModel.uiState.value.pendingRevealMapToken).isGreaterThan(0L)
        assertThat(harness.coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)
        assertThat(harness.highlight.highlight.value?.coordinate).isEqualTo(harness.p1.coordinate)
        assertThat(harness.overlayState.claims.value.values.single().overlay.selectedPinId).isEqualTo("p1")

        // Stepping to p2 while revealed moves camera & highlight; unanchored p3 clears them.
        harness.viewModel.onAction(HikePhotoViewerAction.StepNext)
        advanceUntilIdle()
        val moveCmd = harness.coordinator.pendingCameraCommand.value as? MapCameraCommand.MoveTo
        assertThat(moveCmd?.center).isEqualTo(harness.p2.coordinate)
        assertThat(harness.overlayState.claims.value.values.single().overlay.selectedPinId).isEqualTo("p2")

        harness.viewModel.onAction(HikePhotoViewerAction.StepNext)
        advanceUntilIdle()
        assertThat(harness.highlight.highlight.value).isNull()
        assertThat(harness.overlayState.claims.value.values.single().overlay.selectedPinId).isNull()

        // Tapping photo surface or map callout re-expands full height.
        harness.viewModel.onAction(HikePhotoViewerAction.TapPhotoSurface)
        advanceUntilIdle()
        assertThat(harness.viewModel.uiState.value.isMapRevealed).isFalse()
        val prevExpandToken = harness.viewModel.uiState.value.pendingExpandFullHeightToken
        assertThat(prevExpandToken).isGreaterThan(0L)

        harness.overlayState.claims.value.values.single().handler?.onTapPhotoCallout("p1")
        advanceUntilIdle()
        assertThat(harness.viewModel.uiState.value.currentPhotoId).isEqualTo("p1")
        assertThat(harness.viewModel.uiState.value.pendingExpandFullHeightToken).isGreaterThan(prevExpandToken)

        store.clear()
        assertThat(harness.overlayState.claims.value).isEmpty()
        assertThat(harness.highlight.highlight.value).isNull()
        assertThat(harness.coordinator.activeClaim.value).isNull()
    }

    private class ViewerHarness(
        val p1: HikePhoto,
        val p2: HikePhoto,
        val coordinator: MapClaimCoordinator,
        val highlight: RouteHighlightState,
        val overlayState: TrailPhotosOverlayState,
        val viewModel: HikePhotoViewerViewModel
    )

    private fun createViewerHarness(): ViewerHarness {
        val hikeId = HikeId("h1")
        val p2 = HikePhoto(
            id = "p2",
            hikeId = hikeId,
            capturedAtEpochMillis = 2_000L,
            coordinate = GeoCoordinate(47.7200, 12.8320)
        )
        val p1 = HikePhoto(
            id = "p1",
            hikeId = hikeId,
            capturedAtEpochMillis = 1_000L,
            coordinate = GeoCoordinate(47.7190, 12.8310)
        )
        val p3Unanchored = HikePhoto(
            id = "p3",
            hikeId = hikeId,
            capturedAtEpochMillis = 3_000L,
            coordinate = null
        )
        val repo = FakeHikeRepository(listOf(sampleHike(hikeId, listOf(p2, p3Unanchored, p1)))).apply {
            putPhotoBytesForTest("p1", SAMPLE_JPEG_BYTES)
            putPhotoBytesForTest("p2", SAMPLE_JPEG_BYTES)
        }
        val coordinator = MapClaimCoordinator()
        val highlight = RouteHighlightState()
        val overlayState = TrailPhotosOverlayState()
        val viewModel = HikePhotoViewerViewModel(
            rawHikeId = hikeId.value,
            startPhotoId = "p1",
            hikeRepository = repo,
            mapClaimCoordinator = coordinator,
            routeHighlightState = highlight,
            trailPhotosOverlayState = overlayState,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
        return ViewerHarness(p1, p2, coordinator, highlight, overlayState, viewModel)
    }

    @Test
    fun confirmsDeleteAdvancesToNeighborAndClosesOnLastPhoto() = runTest {
        val hikeId = HikeId("h1")
        val p1 = HikePhoto(id = "p1", hikeId = hikeId, capturedAtEpochMillis = 1_000L)
        val p2 = HikePhoto(id = "p2", hikeId = hikeId, capturedAtEpochMillis = 2_000L)
        val repo = FakeHikeRepository(listOf(sampleHike(hikeId, listOf(p1, p2)))).apply {
            putPhotoBytesForTest("p1", SAMPLE_JPEG_BYTES)
            putPhotoBytesForTest("p2", SAMPLE_JPEG_BYTES)
        }
        val viewModel = HikePhotoViewerViewModel(
            rawHikeId = hikeId.value,
            startPhotoId = "p1",
            hikeRepository = repo,
            mapClaimCoordinator = MapClaimCoordinator(),
            routeHighlightState = RouteHighlightState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        viewModel.onAction(HikePhotoViewerAction.RequestDelete)
        assertThat(viewModel.uiState.value.showDeleteDialog).isTrue()

        viewModel.onAction(HikePhotoViewerAction.ConfirmDelete)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.showDeleteDialog).isFalse()
        assertThat(viewModel.uiState.value.photos.map { it.id }).containsExactly("p2")
        assertThat(viewModel.uiState.value.currentPhotoId).isEqualTo("p2")
        assertThat(viewModel.uiState.value.shouldClose).isFalse()

        viewModel.onAction(HikePhotoViewerAction.RequestDelete)
        viewModel.onAction(HikePhotoViewerAction.ConfirmDelete)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.photos).isEmpty()
        assertThat(viewModel.uiState.value.shouldClose).isTrue()
    }

    @Test
    fun classifiesUnavailableReasonsForPlaceOnlyMissingAndLicensedPhotos() = runTest {
        val hikeId = HikeId("h1")
        val placeOnly = HikePhoto(
            id = "place-only",
            hikeId = hikeId,
            capturedAtEpochMillis = 1_000L,
            isPlaceOnly = true
        )
        val missingLocal = HikePhoto(
            id = "missing-local",
            hikeId = hikeId,
            capturedAtEpochMillis = 2_000L
        )
        val missingLicensed = HikePhoto(
            id = "missing-licensed",
            hikeId = hikeId,
            capturedAtEpochMillis = 3_000L,
            importedAuthorName = "Author",
            license = HikePhotoLicense(
                name = "CC BY-SA 4.0",
                url = "https://creativecommons.org/licenses/by-sa/4.0/",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:Test.jpg"
            )
        )
        val repo = FakeHikeRepository(listOf(sampleHike(hikeId, listOf(placeOnly, missingLocal, missingLicensed))))
        val viewModel = HikePhotoViewerViewModel(
            rawHikeId = hikeId.value,
            startPhotoId = "place-only",
            hikeRepository = repo,
            mapClaimCoordinator = MapClaimCoordinator(),
            routeHighlightState = RouteHighlightState(),
            trailPhotosOverlayState = TrailPhotosOverlayState(),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        advanceUntilIdle()
        assertThat(viewModel.loadPageContent(placeOnly))
            .isEqualTo(PhotoPageContent.Unavailable(PhotoUnavailableReason.PLACE_ONLY))
        assertThat(viewModel.loadPageContent(missingLocal))
            .isEqualTo(PhotoPageContent.Unavailable(PhotoUnavailableReason.NOT_ON_THIS_DEVICE))
        assertThat(viewModel.loadPageContent(missingLicensed))
            .isEqualTo(PhotoPageContent.Unavailable(PhotoUnavailableReason.NOT_DOWNLOADED))
    }

    @Test
    fun shareActionPreparesUniqueShareFileWithReadableDisplayName() = runTest {
        val harness = createViewerHarness()
        advanceUntilIdle()

        harness.viewModel.onAction(HikePhotoViewerAction.ShareCurrent)
        advanceUntilIdle()

        val prepared = harness.viewModel.uiState.value.pendingSharePhoto
        assertThat(prepared).isNotNull()
        assertThat(prepared?.displayName).isEqualTo("Thumsee Loop-1.jpeg")
        assertThat(prepared?.file?.exists()).isTrue()

        harness.viewModel.onAction(HikePhotoViewerAction.ConsumeShareFile)
        assertThat(harness.viewModel.uiState.value.pendingSharePhoto).isNull()
        prepared?.file?.delete()
    }

    private fun sampleHike(hikeId: HikeId, photos: List<HikePhoto>): Hike = Hike(
        id = hikeId,
        title = "Thumsee Loop",
        distanceMeters = 1_200.0,
        dateEpochMillis = 1_000L,
        route = listOf(
            RouteCoordinate(47.7184, 12.8317, timestampEpochMillis = 1_000L),
            RouteCoordinate(47.7210, 12.8330, timestampEpochMillis = 5_000L)
        ),
        photos = photos
    )

    private companion object {
        val SAMPLE_JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
