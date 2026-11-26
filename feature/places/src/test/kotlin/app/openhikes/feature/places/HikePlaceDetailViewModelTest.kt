package app.openhikes.feature.places

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.data.WikidataPlaceLookup
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.testing.FakeTrailPointSource
import app.openhikes.core.testing.FakeWikidataPlaceSource
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
class HikePlaceDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun editsAndRemovesOwnPlace() = runTest {
        val hikeId = HikeId("h1")
        val ownPlace = sampleOwnPlace(hikeId)
        val viewModel = createViewModel(hikeId = hikeId, placeId = ownPlace.id, places = listOf(ownPlace))

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().row?.place?.name).isEqualTo("Boathouse")

            viewModel.onStartEdit()
            viewModel.onEditNameChange("North Boathouse")
            viewModel.onEditSymbolChange(TrailPlaceSymbol.VIEWPOINT)
            viewModel.onEditNoteChange("Open in summer")
            viewModel.onSaveEdit()
            advanceUntilIdle()
            val edited = expectMostRecentItem()
            assertThat(edited.isEditing).isFalse()
            assertThat(edited.row?.place?.name).isEqualTo("North Boathouse")
            assertThat(edited.row?.place?.symbolKind).isEqualTo(TrailPlaceSymbol.VIEWPOINT)

            viewModel.onRequestRemove()
            viewModel.onConfirmRemove()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().row).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun dispatchesShowOnMapPinTapAndClearsClaimsOnDispose() = runTest {
        val hikeId = HikeId("h1")
        val ownPlace = sampleOwnPlace(hikeId)
        val otherPlace = TrailPoint(
            id = "p-osm",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7210, 12.8300),
            name = "Thumsee Spring",
            symbol = TrailPlaceSymbol.WATER.rawValue,
            osm = TrailPlaceOsm("node", 123456L)
        )
        val coordinator = MapClaimCoordinator()
        val overlayState = TrailPlacesOverlayState()
        val viewModel = createViewModel(
            hikeId = hikeId,
            placeId = ownPlace.id,
            places = listOf(ownPlace, otherPlace),
            coordinator = coordinator,
            overlayState = overlayState
        )
        val store = ViewModelStore().apply { put("place", viewModel) }

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem()

            viewModel.onShowOnMap()
            val cmd = coordinator.pendingCameraCommand.value as? MapCameraCommand.MoveTo
            assertThat(cmd?.center).isEqualTo(ownPlace.coordinate)

            val claim = overlayState.claims.value.values.single()
            claim.handler?.onTapPlacePin(otherPlace.id)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().pendingOpenPlaceId).isEqualTo(otherPlace.id)
            viewModel.consumeOpenPlace()
            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(overlayState.claims.value).isEmpty()
        assertThat(coordinator.activeClaim.value).isNull()
    }

    @Test
    fun loadsWikidataAndRefusesEditForOsmPlace() = runTest {
        val hikeId = HikeId("h1")
        val summit = TrailPoint(
            id = "p-summit",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7215, 12.841363),
            name = "Thumsee Kopf",
            symbol = TrailPlaceSymbol.SUMMIT.rawValue,
            osm = TrailPlaceOsm("node", 9_100_004L, wikidata = FakeTrailPointSource.SEEDED_WIKIDATA_ITEM)
        )
        val viewModel = createViewModel(hikeId = hikeId, placeId = summit.id, places = listOf(summit))

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.wikidataPlace?.article?.title).isEqualTo("Thumsee Kopf")
            assertThat(loaded.wikidataPlace?.photo?.credit?.author).isEqualTo(FakeWikidataPlaceSource.SEEDED_AUTHOR)

            // Starting edit on an OSM place is a no-op and emits no editing transition.
            viewModel.onStartEdit()
            advanceUntilIdle()
            expectNoEvents()
            assertThat(viewModel.uiState.value.isEditing).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(
        hikeId: HikeId,
        placeId: String,
        places: List<TrailPoint>,
        coordinator: MapClaimCoordinator = MapClaimCoordinator(),
        overlayState: TrailPlacesOverlayState = TrailPlacesOverlayState()
    ): HikePlaceDetailViewModel = HikePlaceDetailViewModel(
        rawHikeId = hikeId.value,
        placeId = placeId,
        hikeRepository = FakeHikeRepository(listOf(sampleHike(hikeId, places))),
        userSettingsRepository = FakeUserSettingsRepository(),
        wikidataPlaceLookup = WikidataPlaceLookup(FakeWikidataPlaceSource()),
        mapClaimCoordinator = coordinator,
        trailPlacesOverlayState = overlayState,
        trailPhotosOverlayState = TrailPhotosOverlayState(),
        photoCaptureController = PhotoCaptureController(),
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )

    private fun sampleOwnPlace(hikeId: HikeId): TrailPoint = TrailPoint(
        id = "p-own",
        hikeId = hikeId,
        coordinate = GeoCoordinate(47.7185, 12.8318),
        name = "Boathouse",
        symbol = TrailPlaceSymbol.SHELTER.rawValue,
        note = "Rowing boats in summer"
    )

    private fun sampleHike(hikeId: HikeId, places: List<TrailPoint>): Hike = Hike(
        id = hikeId,
        title = "Thumsee Loop",
        distanceMeters = 1_000.0,
        dateEpochMillis = 10_000L,
        route = listOf(RouteCoordinate(47.7184, 12.8317), RouteCoordinate(47.7220, 12.8290)),
        places = places
    )
}
