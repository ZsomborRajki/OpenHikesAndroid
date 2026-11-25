package app.openhikes.shell

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.openhikes.core.common.DefaultOpenHikesDispatchers
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailMapRenderer
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.SettingsRoute
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.feature.hikes.HikeLibraryScreen
import app.openhikes.feature.hikes.HikeLibraryUiState
import app.openhikes.feature.settings.SettingsScreen
import app.openhikes.feature.settings.SettingsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenHikesLiveResizeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liveWindowResizePreservesBackStackAndViewModelStateWithoutRecreation() {
        val fakeRenderer = FakeTrailMapRenderer()
        val settingsRepo = FakeUserSettingsRepository()
        val launchConfig = LaunchConfig(isUiTesting = true, simulatesOffline = true)
        val shellViewModel = createLiveResizeShellViewModel(settingsRepo, launchConfig)
        val settingsInstaller = createSettingsInstaller(settingsRepo)
        var windowSizeClass by mutableStateOf(
            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(411f, 891f)
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                OpenHikesApp(
                    trailMapRenderer = fakeRenderer,
                    entryProviderInstallers = setOf(settingsInstaller),
                    launchConfig = launchConfig,
                    windowSizeClass = windowSizeClass,
                    shellViewModel = shellViewModel,
                    homeContent = { scope ->
                        HikeLibraryScreen(
                            uiState = HikeLibraryUiState(),
                            searchQuery = scope.searchQueryState.value,
                            onSearchQueryChange = scope.onSearchQueryChange,
                            onClearSearch = scope.onClearSearch,
                            onSearchFocusChanged = scope.onSearchFocusChanged,
                            onOpenSettings = scope.onOpenSettings,
                            onImportGpxClick = {},
                            onOpenHike = scope.onOpenHike,
                            onRequestDelete = {},
                            onConfirmDelete = {},
                            onCancelDelete = {}
                        )
                    }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertIsDisplayed()
        assertThat(fakeRenderer.lastViewportPadding.value?.calculateBottomPadding()).isEqualTo(112.dp)

        toggleSavePhotosSettingInSheet()

        windowSizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(800f, 400f)
        assertSidePanelPreservesSettingsAndFitsViewport(fakeRenderer)

        // Compact-width + compact-height window (320x400dp): side panel edge must fit inside half the width.
        windowSizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(320f, 400f)
        assertSidePanelPreservesSettingsAndFitsViewport(fakeRenderer)

        windowSizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(411f, 891f)
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SHEET))
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE).performScrollTo().assertIsOn()
    }

    private fun toggleSavePhotosSettingInSheet() {
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_BUTTON).performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SETTINGS_SCREEN))
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE).performScrollTo().performClick()
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE) and isOn())
    }

    private fun createLiveResizeShellViewModel(
        settingsRepo: FakeUserSettingsRepository,
        launchConfig: LaunchConfig
    ): MapShellViewModel = MapShellViewModel(
        userSettingsRepository = settingsRepo,
        hikeRepository = FakeHikeRepository(),
        offlineCountryRepository = FakeOfflineCountryRepository(),
        mapClaimCoordinator = MapClaimCoordinator(),
        recordingController = FakeRecordingController(),
        trailWalkController = FakeTrailWalkController(),
        routeHighlightState = RouteHighlightState(),
        walkOverlayState = WalkOverlayState(),
        mapProviderKeys = MapProviderKeys.Empty,
        launchConfigHolder = LaunchConfigHolder(launchConfig),
        defaultDispatcher = DefaultOpenHikesDispatchers().default
    )

    private fun assertSidePanelPreservesSettingsAndFitsViewport(fakeRenderer: FakeTrailMapRenderer) {
        composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.MAP_SIDE_PANEL))
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SHEET).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.SETTINGS_SCREEN).performScrollToKey("offline_photos")
        composeRule.onNodeWithTag(OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE).performScrollTo().assertIsOn()

        val rootBounds = composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).getUnclippedBoundsInRoot()
        val panelBounds = composeRule.onNodeWithTag(OpenHikesTestTags.MAP_SIDE_PANEL).getUnclippedBoundsInRoot()
        val startPadding = fakeRenderer.lastViewportPadding.value!!.calculateLeftPadding(LayoutDirection.Ltr)
        assertThat(panelBounds.right.value).isLessThan((rootBounds.right.value - rootBounds.left.value) * 0.5f)
        assertThat(startPadding.value).isWithin(1f).of(panelBounds.right.value)
    }

    private fun createSettingsInstaller(settingsRepo: FakeUserSettingsRepository): EntryProviderInstaller {
        val settingsViewModel = SettingsViewModel(
            userSettingsRepository = settingsRepo,
            offlineCountryRepository = FakeOfflineCountryRepository(),
            appBuildInfo = AppBuildInfo(versionName = "0.1.0", versionCode = 1)
        )
        return { navigator ->
            entry<SettingsRoute> {
                val state by settingsViewModel.uiState.collectAsState()
                SettingsScreen(
                    uiState = state,
                    onClose = { navigator.pop() },
                    onDistanceUnitChange = settingsViewModel::onDistanceUnitChange,
                    onAutoPauseChange = settingsViewModel::onAutoPauseChange,
                    onHikeRemindersChange = settingsViewModel::onHikeRemindersChange,
                    onLiveUpdatesChange = settingsViewModel::onLiveUpdatesChange,
                    onKeepScreenAwakeChange = settingsViewModel::onKeepScreenAwakeChange,
                    onAutoSaveTilesChange = settingsViewModel::onAutoSaveTilesChange,
                    onSavePhotosToLibraryChange = settingsViewModel::onSavePhotosToLibraryChange,
                    onOpenUrl = {}
                )
            }
        }
    }
}
