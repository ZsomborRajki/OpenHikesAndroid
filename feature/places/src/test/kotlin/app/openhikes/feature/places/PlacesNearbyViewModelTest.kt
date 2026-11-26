package app.openhikes.feature.places

import app.cash.turbine.test
import app.openhikes.core.data.WikidataPlaceLookup
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.testing.FakeTrailPointSource
import app.openhikes.core.testing.FakeWikidataPlaceSource
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesNearbyViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun searchesAroundInitialPositionAndAddsSelectedCandidate() = runTest {
        val hikeId = HikeId("rec-1")
        val here = GeoCoordinate(47.7188, 12.8311)
        val cameraTracker = MapCameraTracker().apply { update(MapCameraState(center = here, zoom = 15.0)) }
        val viewModel = PlacesNearbyViewModel(
            rawHikeId = hikeId.value,
            initialLatitude = here.latitude,
            initialLongitude = here.longitude,
            hikeRepository = FakeHikeRepository(
                listOf(Hike(id = hikeId, title = "Morning Walk", distanceMeters = 0.0, dateEpochMillis = 10_000L))
            ),
            userSettingsRepository = FakeUserSettingsRepository(),
            recordingController = FakeRecordingController(),
            trailPointSource = FakeTrailPointSource(),
            wikidataPlaceLookup = WikidataPlaceLookup(FakeWikidataPlaceSource()),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = cameraTracker,
            trailPlacesOverlayState = TrailPlacesOverlayState()
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.entries).isNotEmpty()
            val carPark = loaded.entries.first { it.entry.row.place.name == "Thumsee Car Park" }
            assertThat(carPark.entry.isAdded).isFalse()

            viewModel.onSelectPlace(carPark.id)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().selectedCard?.isOnHike).isFalse()

            viewModel.onAddPlace(carPark.id)
            advanceUntilIdle()
            val afterAdd = expectMostRecentItem()
            assertThat(afterAdd.selectedCard?.isOnHike).isTrue()
            assertThat(afterAdd.entries.first { it.id == carPark.id }.entry.isAdded).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
