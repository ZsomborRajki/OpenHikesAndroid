package app.openhikes.feature.places

import androidx.lifecycle.ViewModelStore
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import com.google.common.truth.Truth.assertThat
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
            mapClaimCoordinator = coordinator,
            mapCameraTracker = cameraTracker,
            trailPlacesOverlayState = overlayState
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
}
