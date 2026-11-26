package app.openhikes.feature.places

import androidx.lifecycle.ViewModelStore
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddHikePlaceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun tracksKindLabelUntilTypedUpdatesCoordinateFromMapPanAndPersistsPlace() = runTest {
        val hikeId = HikeId("h1")
        val initial = GeoCoordinate(47.7185, 12.8318)
        val panned = GeoCoordinate(47.7212, 12.8295)
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Thumsee Loop",
                    distanceMeters = 1_000.0,
                    dateEpochMillis = 10_000L,
                    route = listOf(RouteCoordinate(47.7184, 12.8317), RouteCoordinate(47.7220, 12.8290))
                )
            )
        )
        val cameraTracker = MapCameraTracker().apply { update(MapCameraState(center = initial, zoom = 15.0)) }
        val overlayState = TrailPlacesOverlayState()
        val coordinator = MapClaimCoordinator()
        val viewModel = AddHikePlaceViewModel(
            rawHikeId = hikeId.value,
            initialLatitude = initial.latitude,
            initialLongitude = initial.longitude,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = coordinator,
            mapCameraTracker = cameraTracker,
            trailPlacesOverlayState = overlayState,
            photoCaptureController = PhotoCaptureController()
        )
        val store = ViewModelStore().apply { put("add", viewModel) }
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.symbol).isEqualTo(TrailPlaceSymbol.VIEWPOINT)
        assertThat(overlayState.claims.value.values.single().overlay.showCenterPlacementPin).isTrue()

        // Changing kind while name still matches previous kind carries name along.
        viewModel.onSymbolChange(TrailPlaceSymbol.WATER, previousKindLabel = "Viewpoint", nextKindLabel = "Water")
        assertThat(viewModel.uiState.value.name).isEqualTo("Water")
        assertThat(viewModel.uiState.value.symbol).isEqualTo(TrailPlaceSymbol.WATER)

        // Once user types a custom name, changing kind preserves it.
        viewModel.onNameChange("Cold Spring")
        viewModel.onSymbolChange(TrailPlaceSymbol.SHELTER, previousKindLabel = "Water", nextKindLabel = "Shelter")
        assertThat(viewModel.uiState.value.name).isEqualTo("Cold Spring")

        // Panning the map updates the draft coordinate live.
        cameraTracker.update(MapCameraState(center = panned, zoom = 16.0))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.coordinate).isEqualTo(panned)

        viewModel.onNoteChange("Fresh water near bench")
        viewModel.onConfirmAdd(currentKindLabel = "Shelter")
        advanceUntilIdle()

        val addedId = viewModel.uiState.value.addedPlaceId
        assertThat(addedId).isNotNull()
        val persisted = repo.observeHike(hikeId).first()!!.places.single()
        assertThat(persisted.id).isEqualTo(addedId)
        assertThat(persisted.name).isEqualTo("Cold Spring")
        assertThat(persisted.symbolKind).isEqualTo(TrailPlaceSymbol.SHELTER)
        assertThat(persisted.coordinate).isEqualTo(panned)
        assertThat(persisted.note).isEqualTo("Fresh water near bench")

        store.clear()
        assertThat(overlayState.claims.value).isEmpty()
        assertThat(coordinator.activeClaim.value).isNull()
    }

    @Test
    fun usesProjectedReticleCoordinateOverRawCameraCenterWhenSaving() = runTest {
        val hikeId = HikeId("h2")
        val initial = GeoCoordinate(47.7185, 12.8318)
        val reticlePoint = GeoCoordinate(47.7242, 12.8351)
        val shiftedCameraCenter = GeoCoordinate(47.7000, 12.8000)
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Ridge Loop",
                    distanceMeters = 1_000.0,
                    dateEpochMillis = 10_000L,
                    route = listOf(RouteCoordinate(47.7184, 12.8317), RouteCoordinate(47.7220, 12.8290))
                )
            )
        )
        val cameraTracker = MapCameraTracker().apply { update(MapCameraState(center = initial, zoom = 15.0)) }
        val overlayState = TrailPlacesOverlayState()
        val viewModel = AddHikePlaceViewModel(
            rawHikeId = hikeId.value,
            initialLatitude = initial.latitude,
            initialLongitude = initial.longitude,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = cameraTracker,
            trailPlacesOverlayState = overlayState,
            photoCaptureController = PhotoCaptureController()
        )
        advanceUntilIdle()

        val handler = requireNotNull(overlayState.claims.value.values.single().handler)
        handler.onPlacementCoordinateChanged(reticlePoint)
        cameraTracker.update(MapCameraState(center = shiftedCameraCenter, zoom = 16.0))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.coordinate).isEqualTo(reticlePoint)
        viewModel.onNameChange("Reticle Spot")
        viewModel.onConfirmAdd(currentKindLabel = "Viewpoint")
        advanceUntilIdle()

        assertThat(repo.observeHike(hikeId).first()!!.places.single().coordinate).isEqualTo(reticlePoint)
    }

    @Test
    fun retainsFailedStagedPhotosForRetryWithoutDuplicatingPlace() = runTest {
        val hikeId = HikeId("h-staged")
        val initial = GeoCoordinate(47.7185, 12.8318)
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Ridge Loop",
                    distanceMeters = 1_000.0,
                    dateEpochMillis = 10_000L,
                    route = listOf(RouteCoordinate(47.7184, 12.8317), RouteCoordinate(47.7220, 12.8290))
                )
            )
        )
        val viewModel = AddHikePlaceViewModel(
            rawHikeId = hikeId.value,
            initialLatitude = initial.latitude,
            initialLongitude = initial.longitude,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = MapCameraTracker().apply { update(MapCameraState(center = initial, zoom = 15.0)) },
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            photoCaptureController = PhotoCaptureController()
        )
        advanceUntilIdle()

        val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01)
        val jpegB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x02)
        viewModel.onNameChange("Summit Bench")
        viewModel.onStageCameraPhoto(jpegA, capturedAtEpochMillis = 12_000L)
        viewModel.onStageLibraryPhotos(listOf(jpegB))
        assertThat(viewModel.uiState.value.stagedPhotos).hasSize(2)

        // First staged photo fails while the second succeeds; place is saved once and only the failed photo remains.
        repo.nextPhotoSaveFailure = IOException("Disk temporarily full")
        viewModel.onConfirmAdd(currentKindLabel = "Viewpoint")
        advanceUntilIdle()

        val afterFirstAttempt = viewModel.uiState.value
        assertThat(afterFirstAttempt.addedPlaceId).isNull()
        assertThat(afterFirstAttempt.photoAttachFailureCount).isEqualTo(1)
        assertThat(afterFirstAttempt.stagedPhotos).hasSize(1)
        assertThat(afterFirstAttempt.stagedPhotos.single().fromCamera).isTrue()
        val savedAfterFirst = repo.observeHike(hikeId).first()!!
        assertThat(savedAfterFirst.places).hasSize(1)
        assertThat(savedAfterFirst.photos).hasSize(1)

        // Retrying attaches only the remaining failed photo without creating a second place.
        viewModel.onConfirmAdd(currentKindLabel = "Viewpoint")
        advanceUntilIdle()

        val afterRetry = viewModel.uiState.value
        assertThat(afterRetry.photoAttachFailureCount).isEqualTo(0)
        assertThat(afterRetry.stagedPhotos).isEmpty()
        assertThat(afterRetry.addedPlaceId).isEqualTo(savedAfterFirst.places.single().id)
        val savedAfterRetry = repo.observeHike(hikeId).first()!!
        assertThat(savedAfterRetry.places).hasSize(1)
        assertThat(savedAfterRetry.photos).hasSize(2)
    }

    @Test
    fun continueWithoutFailedPhotosNavigatesAndKeepsSavedPlace() = runTest {
        val hikeId = HikeId("h-continue")
        val initial = GeoCoordinate(47.7185, 12.8318)
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Lake Loop",
                    distanceMeters = 1_000.0,
                    dateEpochMillis = 10_000L,
                    route = listOf(RouteCoordinate(47.7184, 12.8317), RouteCoordinate(47.7220, 12.8290))
                )
            )
        )
        val viewModel = AddHikePlaceViewModel(
            rawHikeId = hikeId.value,
            initialLatitude = initial.latitude,
            initialLongitude = initial.longitude,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = MapCameraTracker().apply { update(MapCameraState(center = initial, zoom = 15.0)) },
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            photoCaptureController = PhotoCaptureController()
        )
        advanceUntilIdle()

        viewModel.onNameChange("Lake Pier")
        viewModel.onStageCameraPhoto(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()), 15_000L)
        repo.nextPhotoSaveFailure = IOException("Disk full")
        viewModel.onConfirmAdd(currentKindLabel = "Viewpoint")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.photoAttachFailureCount).isEqualTo(1)

        viewModel.onContinueWithoutFailedPhotos()
        val finalState = viewModel.uiState.value
        val persistedPlace = repo.observeHike(hikeId).first()!!.places.single()
        assertThat(finalState.addedPlaceId).isEqualTo(persistedPlace.id)
        assertThat(finalState.stagedPhotos).isEmpty()
        assertThat(finalState.photoAttachFailureCount).isEqualTo(0)
    }
}
