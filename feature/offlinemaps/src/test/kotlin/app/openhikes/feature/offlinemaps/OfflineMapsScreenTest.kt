package app.openhikes.feature.offlinemaps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.common.offline.OfflineCountryManifestParser
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountryDirectoryBuilder
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineMapsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val seededCountries = OfflineCountryManifestParser
        .parseCatalog(TestFixtures.offlineCountriesSeedJson())
        .readableCountries

    @Test
    fun rendersBannerExtraDetailUnfinishedAndDownloadedActions() {
        val germany = seededCountries.first { it.id == "DE" }
        val france = seededCountries.first { it.id == "FR" }
        val uiState = buildPopulatedUiState(germany = germany, france = france)
        val clickedActions = mutableListOf<String>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = uiState,
                    onBack = { clickedActions.add("back") },
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = { clickedActions.add("detail:$it") },
                    onUseOpenHikesMap = { clickedActions.add("use-openhikes") },
                    onDownload = {},
                    onRetry = { clickedActions.add("retry:$it") },
                    onRepair = {},
                    onCancel = {},
                    onDelete = { clickedActions.add("delete:$it") },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_USE_OPENHIKES_MAP).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertIsOn().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("unfinished-${france.id}")
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryAction(france.id)).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("downloaded-${germany.id}")
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryDelete(germany.id)).performClick()

        assertThat(clickedActions).containsAtLeast("use-openhikes", "detail:false", "retry:FR", "delete:DE")
    }

    @Test
    fun rendersCatalogCountriesAndExpandedSplitCountryGroup() {
        val austria = seededCountries.first { it.id == "AT" }
        val germany = seededCountries.first { it.id == "DE" }
        val france = seededCountries.first { it.id == "FR" }
        val uiState = buildPopulatedUiState(germany = germany, france = france)
        val clickedActions = mutableListOf<String>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = uiState,
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = { clickedActions.add("group:$it") },
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onUseOpenHikesMap = {},
                    onDownload = { clickedActions.add("download:$it") },
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("country-${austria.id}")
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryAction(austria.id)).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("group-US")
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryGroup("US")).assertIsDisplayed()
        composeRule.onNodeWithText("3 regions").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("group-US-US-CO")
        composeRule.onNodeWithText("Colorado").assertIsDisplayed()

        assertThat(clickedActions).contains("download:AT")
    }

    private fun buildPopulatedUiState(
        germany: app.openhikes.core.model.OfflineCountry,
        france: app.openhikes.core.model.OfflineCountry
    ): OfflineMapsUiState {
        val repoState = OfflineCountriesState(
            isPrepared = true,
            listPhase = OfflineListPhase.LOADED,
            offered = seededCountries,
            installed = mapOf(
                "DE" to InstalledCountryRecord(germany, installedAtEpochMillis = 1_000L, hasDetail = true)
            ),
            downloads = mapOf(
                "FR" to OfflineCountryDownload(
                    country = france,
                    roles = listOf(OfflineFileRole.BASEMAP),
                    phase = OfflineDownloadPhase.FAILED,
                    failure = OfflineDownloadFailure.CONNECTION
                )
            )
        )
        val directory = OfflineCountryDirectoryBuilder.build(
            state = repoState,
            sort = OfflineCountrySort.NAME,
            locale = Locale.US
        )
        return OfflineMapsUiState(
            isAvailable = true,
            isPrepared = true,
            listPhase = OfflineListPhase.LOADED,
            sort = OfflineCountrySort.NAME,
            availableSorts = listOf(OfflineCountrySort.NAME, OfflineCountrySort.LARGEST),
            includesDetail = true,
            showUseOpenHikesBanner = true,
            activeMapStyleId = MapStyleId.OPENFREEMAP,
            expandedGroups = setOf("US"),
            unfinished = directory.unfinished,
            downloaded = directory.downloaded,
            sections = directory.sections,
            installed = repoState.installed,
            downloads = repoState.downloads
        )
    }

    @Test
    fun rendersLoadFailureTryAgainAndUnavailableStates() {
        var retried = false
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = OfflineMapsUiState(
                        isAvailable = true,
                        isPrepared = true,
                        listPhase = OfflineListPhase.FAILED
                    ),
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onRefresh = { retried = true }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_LOAD_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn’t Load Countries").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_TRY_AGAIN).performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun rendersEmptySearchCardAndOffDetailSwitch() {
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = OfflineMapsUiState(
                        isAvailable = true,
                        isPrepared = true,
                        listPhase = OfflineListPhase.LOADED,
                        searchQuery = "Atlantis",
                        includesDetail = false,
                        searchResults = emptyList()
                    ),
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EMPTY_SEARCH).assertIsDisplayed()
        composeRule.onNodeWithText("No Results for “Atlantis”").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertDoesNotExist()
    }

    @Test
    fun extraDetailSwitchReflectsOffState() {
        val state = OfflineCountriesState(isPrepared = true, offered = seededCountries, includesDetail = false)
        val directory = OfflineCountryDirectoryBuilder.build(state, OfflineCountrySort.NAME, locale = Locale.US)
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = OfflineMapsUiState(
                        isAvailable = true,
                        isPrepared = true,
                        listPhase = OfflineListPhase.LOADED,
                        includesDetail = false,
                        sections = directory.sections
                    ),
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onRefresh = {}
                )
            }
        }
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertIsOff()
    }
}
