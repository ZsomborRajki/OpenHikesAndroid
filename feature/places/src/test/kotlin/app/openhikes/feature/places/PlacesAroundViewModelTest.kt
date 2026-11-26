package app.openhikes.feature.places

import app.cash.turbine.test
import app.openhikes.core.data.WikidataPlaceLookup
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceSymbol
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

            // Adding from card keeps card open and transitions it to "On this hike".
            viewModel.onAddPlace(summit.id)
            advanceUntilIdle()
            val afterAdd = expectMostRecentItem()
            assertThat(afterAdd.selectedCard?.isOnHike).isTrue()
            assertThat(afterAdd.listing.nearby.first { it.id == summit.id }.isAdded).isTrue()
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

    private fun createViewModel(pointSource: FakeTrailPointSource): PlacesAroundViewModel {
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
            )
        )
        return PlacesAroundViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = FakeHikeRepository(listOf(hike)),
            userSettingsRepository = FakeUserSettingsRepository(),
            trailPointSource = pointSource,
            wikidataPlaceLookup = WikidataPlaceLookup(FakeWikidataPlaceSource()),
            mapClaimCoordinator = MapClaimCoordinator(),
            mapCameraTracker = MapCameraTracker(),
            trailPlacesOverlayState = TrailPlacesOverlayState(),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
    }
}
