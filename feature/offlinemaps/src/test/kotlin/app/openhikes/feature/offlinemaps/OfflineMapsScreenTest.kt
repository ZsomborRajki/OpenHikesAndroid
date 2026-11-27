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
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDirectoryBuilder
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryExtra
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryOffer
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.format.StorageSizeFormatter
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
    fun rendersBannerSwitchesUnfinishedAndDownloadedActions() {
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
                    onSetIncludesElevation = { clickedActions.add("elevation:$it") },
                    onUseOpenHikesMap = { clickedActions.add("use-openhikes") },
                    onDownload = {},
                    onRetry = { clickedActions.add("retry:$it") },
                    onRepair = {},
                    onCancel = {},
                    onDelete = { clickedActions.add("delete:$it") },
                    onExtend = { clickedActions.add("extend:$it") },
                    onTrim = { clickedActions.add("trim:$it") },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_USE_OPENHIKES_MAP).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertIsOn().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ELEVATION_DATA).assertIsOn().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("unfinished-${france.id}")
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryAction(france.id)).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("downloaded-${germany.id}")
        composeRule.onNodeWithText("Downloaded without elevation", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryDelete(germany.id)).performClick()

        assertThat(clickedActions).containsAtLeast(
            "use-openhikes",
            "detail:false",
            "elevation:false",
            "retry:FR",
            "delete:DE"
        )
    }

    @Test
    fun rendersDownloadedElevationBadgeAndPerCountryAddRemoveOffers() {
        val uiState = buildOffersUiState()
        val clicks = mutableListOf<String>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = uiState,
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onSetIncludesElevation = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onExtend = { clicks.add("extend:$it") },
                    onTrim = { clicks.add("trim:$it") },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("downloaded-DE")
        composeRule.onNodeWithText("Downloaded with elevation", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN).performScrollToKey("downloaded-SI")
        composeRule.onNodeWithText("Downloaded without elevation", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Add Elevation Data", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Remove Extra Detail", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryAddOffer("SI")).performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.offlineCountryRemoveOffer("SI")).performClick()

        assertThat(clicks).containsExactly("extend:SI", "trim:SI").inOrder()
    }

    private fun buildOffersUiState(): OfflineMapsUiState {
        val slovenia = withElevation(seededCountries.first { it.id == "SI" }, bytes = 40_000_000L)
        val germany = withElevation(seededCountries.first { it.id == "DE" }, bytes = 120_000_000L)
        val repoState = OfflineCountriesState(
            isPrepared = true,
            listPhase = OfflineListPhase.LOADED,
            offered = listOf(slovenia, germany),
            installed = mapOf(
                "SI" to InstalledCountryRecord(slovenia, 1_000L, hasDetail = true, hasElevation = false),
                "DE" to InstalledCountryRecord(germany, 1_000L, hasDetail = false, hasElevation = true)
            ),
            includesDetail = false,
            includesElevation = true
        )
        val directory = OfflineCountryDirectoryBuilder.build(repoState, OfflineCountrySort.NAME, locale = Locale.US)
        return OfflineMapsUiState(
            isAvailable = true,
            isPrepared = true,
            listPhase = OfflineListPhase.LOADED,
            includesDetail = false,
            includesElevation = true,
            downloaded = directory.downloaded,
            sections = directory.sections,
            installed = repoState.installed,
            additions = mapOf("SI" to requireNotNull(repoState.addition("SI"))),
            removals = mapOf("SI" to requireNotNull(repoState.removal("SI")))
        )
    }

    @Test
    fun offerTitlesMatchAllSixWholeSentenceCombinations() {
        val sampleFile = OfflineCountryFile(
            role = OfflineFileRole.DETAIL,
            path = "countries/SI-detail-20261117.pmtiles",
            byteCount = 2_000_000L,
            sha256 = "0".repeat(64),
            minimumZoom = 15,
            maximumZoom = 15
        )
        val size = StorageSizeFormatter.formatBytes(2_000_000L)
        val resolved = mutableMapOf<String, String>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                resolved["addDetail"] = additionOfferTitle(
                    OfflineCountryOffer(setOf(OfflineCountryExtra.DETAIL), listOf(sampleFile))
                )
                resolved["addElevation"] = additionOfferTitle(
                    OfflineCountryOffer(setOf(OfflineCountryExtra.ELEVATION), listOf(sampleFile))
                )
                resolved["addBoth"] = additionOfferTitle(
                    OfflineCountryOffer(
                        setOf(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION),
                        listOf(sampleFile)
                    )
                )
                resolved["removeDetail"] = removalOfferTitle(
                    OfflineCountryOffer(setOf(OfflineCountryExtra.DETAIL), listOf(sampleFile))
                )
                resolved["removeElevation"] = removalOfferTitle(
                    OfflineCountryOffer(setOf(OfflineCountryExtra.ELEVATION), listOf(sampleFile))
                )
                resolved["removeBoth"] = removalOfferTitle(
                    OfflineCountryOffer(
                        setOf(OfflineCountryExtra.DETAIL, OfflineCountryExtra.ELEVATION),
                        listOf(sampleFile)
                    )
                )
            }
        }

        assertThat(resolved["addDetail"]).isEqualTo("Add Extra Detail · $size")
        assertThat(resolved["addElevation"]).isEqualTo("Add Elevation Data · $size")
        assertThat(resolved["addBoth"]).isEqualTo("Add Extra Detail and Elevation Data · $size")
        assertThat(resolved["removeDetail"]).isEqualTo("Remove Extra Detail · $size")
        assertThat(resolved["removeElevation"]).isEqualTo("Remove Elevation Data · $size")
        assertThat(resolved["removeBoth"]).isEqualTo("Remove Extra Detail and Elevation Data · $size")
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
                    onSetIncludesElevation = {},
                    onUseOpenHikesMap = {},
                    onDownload = { clickedActions.add("download:$it") },
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onExtend = {},
                    onTrim = {},
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

    private fun buildPopulatedUiState(germany: OfflineCountry, france: OfflineCountry): OfflineMapsUiState {
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
            includesElevation = true,
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
                    onSetIncludesElevation = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onExtend = {},
                    onTrim = {},
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
    fun rendersEmptySearchCardAndHidesSwitchesWhileSearching() {
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = OfflineMapsUiState(
                        isAvailable = true,
                        isPrepared = true,
                        listPhase = OfflineListPhase.LOADED,
                        searchQuery = "Atlantis",
                        includesDetail = false,
                        includesElevation = false,
                        searchResults = emptyList()
                    ),
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onSetIncludesElevation = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onExtend = {},
                    onTrim = {},
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EMPTY_SEARCH).assertIsDisplayed()
        composeRule.onNodeWithText("No Results for “Atlantis”").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ELEVATION_DATA).assertDoesNotExist()
    }

    @Test
    fun extraDetailAndElevationSwitchesReflectOffState() {
        val state = OfflineCountriesState(
            isPrepared = true,
            offered = seededCountries,
            includesDetail = false,
            includesElevation = false
        )
        val directory = OfflineCountryDirectoryBuilder.build(state, OfflineCountrySort.NAME, locale = Locale.US)
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OfflineMapsScreen(
                    uiState = OfflineMapsUiState(
                        isAvailable = true,
                        isPrepared = true,
                        listPhase = OfflineListPhase.LOADED,
                        includesDetail = false,
                        includesElevation = false,
                        sections = directory.sections
                    ),
                    onBack = {},
                    onSearchQueryChange = {},
                    onClearSearch = {},
                    onSortChange = {},
                    onToggleGroup = {},
                    onSelectCountry = {},
                    onSetIncludesDetail = {},
                    onSetIncludesElevation = {},
                    onUseOpenHikesMap = {},
                    onDownload = {},
                    onRetry = {},
                    onRepair = {},
                    onCancel = {},
                    onDelete = {},
                    onExtend = {},
                    onTrim = {},
                    onRefresh = {}
                )
            }
        }
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL).assertIsOff()
        composeRule.onNodeWithTag(OpenHikesTestTags.OFFLINE_MAPS_ELEVATION_DATA).assertIsOff()
    }

    private fun withElevation(country: OfflineCountry, bytes: Long): OfflineCountry = country.copy(
        files = country.files + OfflineCountryFile(
            role = OfflineFileRole.ELEVATION,
            path = "countries/${country.id}-elevation-${country.build}.pmtiles",
            byteCount = bytes,
            sha256 = "0".repeat(64),
            minimumZoom = 12,
            maximumZoom = 12
        )
    )
}
