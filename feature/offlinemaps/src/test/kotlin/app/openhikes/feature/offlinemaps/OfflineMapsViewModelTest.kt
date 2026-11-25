package app.openhikes.feature.offlinemaps

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryManifestParser
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val seededCountries = OfflineCountryManifestParser
        .parseCatalog(TestFixtures.offlineCountriesSeedJson())
        .readableCountries

    @Test
    fun loadsSeededCatalogClaimsMapOutlinesFramesSelectionAndHandlesMapTap() = runTest {
        val repo = FakeOfflineCountryRepository(
            OfflineCountriesState(
                isPrepared = true,
                listPhase = OfflineListPhase.LOADED,
                offered = seededCountries
            )
        )
        val settingsRepo = FakeUserSettingsRepository()
        val coordinator = MapClaimCoordinator()
        val locationFeed = FakeLocationFeed().apply {
            lastKnownCoordinateOverride = TestFixtures.TRAILHEAD
        }
        val viewModel = OfflineMapsViewModel(
            overMap = true,
            offlineCountryRepository = repo,
            userSettingsRepository = settingsRepo,
            mapClaimCoordinator = coordinator,
            mapProviderKeys = MapProviderKeys.Empty,
            locationFeed = locationFeed,
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )
        val store = ViewModelStore().apply { put("offline", viewModel) }

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.sort).isEqualTo(OfflineCountrySort.NEAREST)
            assertThat(loaded.availableSorts).containsExactly(
                OfflineCountrySort.NEAREST,
                OfflineCountrySort.NAME,
                OfflineCountrySort.LARGEST
            ).inOrder()
            assertThat(coordinator.activeClaim.value?.countryOutlines).hasSize(10)

            // Selecting Austria frames the map camera to Austria's outline.
            viewModel.onSelectCountry("AT")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().selectedCountryId).isEqualTo("AT")
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            // Active search hiding Colorado is cleared and US group expands when tapping Colorado on the map.
            viewModel.onSearchQueryChange("Austria")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().searchResults.map { it.country.id }).containsExactly("AT")

            coordinator.dispatchMapTap(GeoCoordinate(latitude = 39.0, longitude = -105.5))
            advanceUntilIdle()
            val afterTap = expectMostRecentItem()
            assertThat(afterTap.selectedCountryId).isEqualTo("US-CO")
            assertThat(afterTap.searchQuery).isEmpty()
            assertThat(afterTap.expandedGroups).contains("US")

            // Switching to non-OpenHikes map reveals banner, and clicking banner switches back.
            settingsRepo.setMapStyleId(MapStyleId.OPENFREEMAP)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showUseOpenHikesBanner).isTrue()

            viewModel.onUseOpenHikesMap()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showUseOpenHikesBanner).isFalse()

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
    }
}
