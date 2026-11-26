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
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
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
class PlacesAroundViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun searchesCorridorNarrowsWithoutRequeryAndOpensWikidataCard() = runTest {
        val pointSource = FakeTrailPointSource()
        val viewModel = createViewModel(pointSource)

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.phase).isEqualTo(PlacesAroundPhase.FOUND)
            assertThat(loaded.listing.onTrail.map { it.row.place.name })
                .containsAtLeast("Thumsee Car Park", "Lakeshore Hut")
            assertThat(loaded.listing.nearby.map { it.row.place.name }).contains("Thumsee Kopf")
            val searchesAfterLoad = pointSource.searchCount
            assertThat(searchesAfterLoad).isGreaterThan(0)

            // Narrowing reach from AROUND (1 km) -> ON_TRAIL filters locally with zero new queries.
            viewModel.onSelectReach(TrailPlaceReach.ON_TRAIL)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().listing.nearby).isEmpty()
            assertThat(pointSource.searchCount).isEqualTo(searchesAfterLoad)

            // Widening back to NEARBY brings Thumsee Kopf back without re-asking.
            viewModel.onSelectReach(TrailPlaceReach.NEARBY)
            advanceUntilIdle()
            val summit = expectMostRecentItem().listing.nearby.first { it.row.place.name == "Thumsee Kopf" }
            assertThat(pointSource.searchCount).isEqualTo(searchesAfterLoad)

            // Selecting Thumsee Kopf opens card with Wikipedia summary + photo.
            viewModel.onSelectPlace(summit.id)
            advanceUntilIdle()
            val withCard = expectMostRecentItem()
            assertThat(withCard.selectedCard?.isOnHike).isFalse()
            assertThat(withCard.selectedCard?.wikidataPlace?.article?.title).isEqualTo("Thumsee Kopf")

            // Adding from card keeps card open and transitions it to the saved hike-scoped row.
            viewModel.onAddPlace(summit.id)
            advanceUntilIdle()
            val afterAdd = expectMostRecentItem()
            assertThat(afterAdd.selectedCard?.isOnHike).isTrue()
            assertThat(afterAdd.selectedCard?.row?.id).isNotEqualTo(summit.id)
            val savedSummit = afterAdd.listing.nearby.first { it.row.place.name == "Thumsee Kopf" }
            assertThat(savedSummit.isAdded).isTrue()
            assertThat(afterAdd.selectedCard?.row?.id).isEqualTo(savedSummit.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun settlesToIdleAndKeepsSavedPlacesWhenAllKindsHidden() = runTest {
        val pointSource = FakeTrailPointSource()
        val settingsRepo = FakeUserSettingsRepository()
        TrailPointQuery.searchableSymbols.forEach { symbol ->
            settingsRepo.setPlaceSymbolVisible(symbol, visible = false)
        }
        val savedOwn = TrailPoint(
            id = "own-1",
            hikeId = HikeId("h-thumsee"),
            coordinate = GeoCoordinate(47.7185, 12.8318),
            name = "Boathouse",
            symbol = TrailPlaceSymbol.SHELTER.rawValue
        )
        val viewModel = createViewModel(
            pointSource = pointSource,
            settingsRepository = settingsRepo,
            initialPlaces = listOf(savedOwn)
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val settled = expectMostRecentItem()
            assertThat(settled.phase).isEqualTo(PlacesAroundPhase.FOUND)
            assertThat(pointSource.searchCount).isEqualTo(0)
            assertThat(settled.listing.onTrail.map { it.row.place.name }).containsExactly("Boathouse")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshesSearchThisAreaPillWhenZoomChanges() = runTest {
        val thumsee = GeoCoordinate(47.72, 12.83)
        val cameraTracker = MapCameraTracker().apply { update(MapCameraState(center = thumsee, zoom = 10.0)) }
        val overlayState = TrailPlacesOverlayState()
        val viewModel = createViewModel(
            pointSource = FakeTrailPointSource(),
            cameraTracker = cameraTracker,
            overlayState = overlayState
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            expectMostRecentItem()
            assertThat(overlayState.claims.value.values.single().searchAreaPill?.isEnabled).isFalse()

            cameraTracker.update(MapCameraState(center = thumsee, zoom = 16.0))
            advanceUntilIdle()
            assertThat(overlayState.claims.value.values.single().searchAreaPill?.isEnabled).isTrue()

            cameraTracker.update(MapCameraState(center = thumsee, zoom = 8.0))
            advanceUntilIdle()
            assertThat(overlayState.claims.value.values.single().searchAreaPill?.isEnabled).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun filtersCandidateSymbolsWhileKeepingAddedHikePlaces() = runTest {
        val viewModel = createViewModel(FakeTrailPointSource())

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            val summit = loaded.listing.nearby.first { it.row.place.name == "Thumsee Kopf" }

            // Hiding SUMMIT removes unheld candidate Thumsee Kopf.
            viewModel.onToggleSymbol(TrailPlaceSymbol.SUMMIT, visible = false)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().listing.nearby.map { it.row.place.name })
                .doesNotContain("Thumsee Kopf")

            // Re-enabling SUMMIT restores it; once added to the hike, hiding SUMMIT keeps held places.
            viewModel.onToggleSymbol(TrailPlaceSymbol.SUMMIT, visible = true)
            advanceUntilIdle()
            expectMostRecentItem()
            viewModel.onAddPlace(summit.id)
            advanceUntilIdle()
            expectMostRecentItem()

            viewModel.onToggleSymbol(TrailPlaceSymbol.SUMMIT, visible = false)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().listing.nearby.map { it.row.place.name }).contains("Thumsee Kopf")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(
        pointSource: FakeTrailPointSource,
        settingsRepository: FakeUserSettingsRepository = FakeUserSettingsRepository(),
        cameraTracker: MapCameraTracker = MapCameraTracker(),
        overlayState: TrailPlacesOverlayState = TrailPlacesOverlayState(),
        initialPlaces: List<TrailPoint> = emptyList()
    ): PlacesAroundViewModel {
        val hike = Hike(
            id = HikeId("h-thumsee"),
            title = "Thumsee Loop",
            distanceMeters = 2_000.0,
            dateEpochMillis = 10_000L,
            route = listOf(
                RouteCoordinate(47.71842, 12.831774),
                RouteCoordinate(47.719219, 12.830877),
                RouteCoordinate(47.724008, 12.829098),
                RouteCoordinate(47.721500, 12.835700),
                RouteCoordinate(47.71842, 12.831774)
            ),
            places = initialPlaces
        )
        return PlacesAroundViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = FakeHikeRepository(listOf(hike)),
            userSettingsRepository = settingsRepository,
            trailPointSource = pointSource,
            wikidataPlaceLookup = WikidataPlaceLookup(FakeWikidataPlaceSource()),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = cameraTracker,
            trailPlacesOverlayState = overlayState,
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
    }
}
