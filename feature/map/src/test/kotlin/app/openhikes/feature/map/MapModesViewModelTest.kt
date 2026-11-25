package app.openhikes.feature.map

import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryManifestParser
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.TilePreviewFrame
import app.openhikes.core.model.UserSettings
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeLocationFeed
import app.openhikes.core.testing.android.FakeMapStylePreviewProvider
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapModesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val seededCountries = OfflineCountryManifestParser
        .parseCatalog(TestFixtures.offlineCountriesSeedJson())
        .readableCountries

    @Test
    fun selectsUsableStylesTogglesHikingRoutesAndIgnoresKeylessCommercialStyles() = runTest {
        val settingsRepo = FakeUserSettingsRepository()
        val previewProvider = FakeMapStylePreviewProvider()
        val viewModel = MapModesViewModel(
            userSettingsRepository = settingsRepo,
            offlineCountryRepository = FakeOfflineCountryRepository(),
            mapProviderKeys = MapProviderKeys.Empty,
            previewProvider = previewProvider,
            locationFeed = FakeLocationFeed(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertThat(initial.selectedStyle).isEqualTo(MapStyleCatalog.openHikes)
            assertThat(initial.showsHikingRoutes).isTrue()
            assertThat(initial.hasMissingApiKeyStyles).isTrue()
            assertThat(initial.styles.first { it.style.id == MapStyleId.STADIA_OUTDOORS }.isUsable).isFalse()
            assertThat(previewProvider.requests.map { it.first }).containsExactly(MapStyleId.OSM_RASTER)
            assertThat(viewModel.previewFrame).isEqualTo(TilePreviewFrame.around(TilePreviewFrame.FALLBACK_COORDINATE))

            // Attempting to select an unusable key-gated style is ignored.
            viewModel.onSelectStyle(MapStyleId.STADIA_OUTDOORS)
            advanceUntilIdle()
            assertThat(settingsRepo.currentSettings.mapStyleId).isEqualTo(MapStyleId.OPENHIKES)

            // Selecting OpenFreeMap updates repository and UI state.
            viewModel.onSelectStyle(MapStyleId.OPENFREEMAP)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().selectedStyle).isEqualTo(MapStyleCatalog.openFreeMap)
            assertThat(settingsRepo.currentSettings.mapStyleId).isEqualTo(MapStyleId.OPENFREEMAP)

            // Turning off Hiking Routes persists cleanly.
            viewModel.onSetShowsHikingRoutes(false)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showsHikingRoutes).isFalse()
            assertThat(settingsRepo.currentSettings.showsHikingRoutes).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fallsBackToOpenHikesForUnusableStoredStyleAndFormatsInstalledCountries() = runTest {
        val austria = seededCountries.first { it.id == "AT" }
        val germany = seededCountries.first { it.id == "DE" }
        val installedMap = mapOf(
            "DE" to InstalledCountryRecord(country = germany, installedAtEpochMillis = 1L, hasDetail = false),
            "AT" to InstalledCountryRecord(country = austria, installedAtEpochMillis = 2L, hasDetail = true)
        )
        val settingsRepo = FakeUserSettingsRepository(
            initialSettings = UserSettings(mapStyleId = MapStyleId.STADIA_OUTDOORS)
        )
        val locationFeed = FakeLocationFeed().apply {
            lastKnownCoordinateOverride = GeoCoordinate(latitude = 47.71842, longitude = 12.83177)
        }
        val viewModel = MapModesViewModel(
            userSettingsRepository = settingsRepo,
            offlineCountryRepository = FakeOfflineCountryRepository(
                OfflineCountriesState(isPrepared = true, installed = installedMap)
            ),
            mapProviderKeys = MapProviderKeys.Empty,
            previewProvider = FakeMapStylePreviewProvider(),
            locationFeed = locationFeed,
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.selectedStyle).isEqualTo(MapStyleCatalog.openHikes)
            // Stored preference is never silently overwritten when a key is absent.
            assertThat(settingsRepo.currentSettings.mapStyleId).isEqualTo(MapStyleId.STADIA_OUTDOORS)
            assertThat(state.installedCountryNames).containsExactly("Austria", "Germany").inOrder()
            assertThat(state.installedByteCount).isEqualTo(
                installedMap.values.sumOf { it.byteCount }
            )
            assertThat(viewModel.previewFrame).isEqualTo(
                TilePreviewFrame.around(GeoCoordinate(latitude = 47.71842, longitude = 12.83177))
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun keysUnlockCommercialStylesAndWriteFailureSurfacesDismissibleBanner() = runTest {
        val settingsRepo = FakeUserSettingsRepository()
        val previewProvider = FakeMapStylePreviewProvider()
        val keys = MapProviderKeys(stadiaApiKey = "s-key", thunderforestApiKey = "t-key")
        val viewModel = MapModesViewModel(
            userSettingsRepository = settingsRepo,
            offlineCountryRepository = FakeOfflineCountryRepository(),
            mapProviderKeys = keys,
            previewProvider = previewProvider,
            locationFeed = FakeLocationFeed(),
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            defaultDispatcher = mainDispatcherRule.testDispatcher
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertThat(initial.hasMissingApiKeyStyles).isFalse()
            assertThat(initial.styles.all { it.isUsable }).isTrue()
            assertThat(previewProvider.requests.map { it.first }).containsExactly(
                MapStyleId.OSM_RASTER,
                MapStyleId.STADIA_OUTDOORS,
                MapStyleId.STAMEN_TERRAIN,
                MapStyleId.THUNDERFOREST_OUTDOORS
            )

            viewModel.onSelectStyle(MapStyleId.STADIA_OUTDOORS)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().selectedStyle).isEqualTo(MapStyleCatalog.stadiaOutdoors)

            settingsRepo.nextWriteFailure = IOException("Disk full")
            viewModel.onSetShowsHikingRoutes(false)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().saveError).isTrue()

            viewModel.onDismissSaveError()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().saveError).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
