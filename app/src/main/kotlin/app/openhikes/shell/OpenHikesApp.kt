package app.openhikes.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.TrailMapHost
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.OpenHikesNavigator
import app.openhikes.core.ui.navigation.SettingsRoute
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val SidePanelViewportInset = 360.dp
private val SheetPeekViewportInset = 112.dp

/**
 * Stateful adaptive map-first shell hosting the permanent map, floating controls,
 * and a single shared Navigation 3 back stack rendered inside either [MapBottomSheet]
 * or [MapSidePanel] based on [windowSizeClass].
 */
@Composable
fun OpenHikesApp(
    trailMapRenderer: TrailMapRenderer,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    launchConfig: LaunchConfig,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    shellViewModel: MapShellViewModel = hiltViewModel()
) {
    val chromeUiState by shellViewModel.chromeUiState.collectAsStateWithLifecycle()
    val searchQueryState = shellViewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingCameraCommand by shellViewModel.pendingCameraCommand.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    OpenHikesShellContent(
        trailMapRenderer = trailMapRenderer,
        entryProviderInstallers = entryProviderInstallers,
        launchConfig = launchConfig,
        windowSizeClass = windowSizeClass,
        chromeUiState = chromeUiState,
        searchQueryState = searchQueryState,
        initialCameraState = shellViewModel.initialCameraState,
        pendingCameraCommand = pendingCameraCommand,
        onSyncBackStack = shellViewModel::syncBackStack,
        onSearchQueryChange = shellViewModel::onSearchQueryChange,
        onClearSearch = shellViewModel::onClearSearch,
        onToggleHikingRoutes = shellViewModel::onToggleHikingRoutes,
        onZoomIn = shellViewModel::onZoomIn,
        onZoomOut = shellViewModel::onZoomOut,
        onResetNorth = shellViewModel::onResetNorth,
        onCameraChanged = shellViewModel::onCameraChanged,
        onCameraCommandHandled = shellViewModel::onCameraCommandHandled,
        onOpenAttributionDialog = shellViewModel::onOpenAttributionDialog,
        onDismissAttributionDialog = shellViewModel::onDismissAttributionDialog,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        modifier = modifier
    )
}

@Composable
internal fun OpenHikesShellContent(
    trailMapRenderer: TrailMapRenderer,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    launchConfig: LaunchConfig,
    windowSizeClass: WindowSizeClass,
    chromeUiState: MapChromeUiState,
    searchQueryState: State<String>,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    onSyncBackStack: (List<Any>) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleHikingRoutes: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    onCameraChanged: (MapCameraState) -> Unit,
    onCameraCommandHandled: (Long) -> Unit,
    onOpenAttributionDialog: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shellLayout = resolveShellLayout(windowSizeClass)
    val initialDetent = if (launchConfig.startsWithExpandedSheet) SheetDetent.HALF else SheetDetent.PEEK
    val sheetDetentState = rememberSaveable { mutableStateOf(initialDetent) }
    val detentBeforeSettingsState = rememberSaveable { mutableStateOf<SheetDetent?>(null) }
    var sheetTopOffsetProvider by remember { mutableStateOf<() -> Float>({ 0f }) }
    val backStack = rememberNavBackStack(MapHomeRoute)
    val navigator = remember(backStack) { OpenHikesNavigator(backStack) }

    val navigationContent = rememberShellNavigation(
        backStack = backStack,
        navigator = navigator,
        entryProviderInstallers = entryProviderInstallers,
        shellLayout = shellLayout,
        sheetDetent = sheetDetentState.value,
        detentBeforeSettings = detentBeforeSettingsState.value,
        showAttributionDialog = chromeUiState.showAttributionDialog,
        searchQueryState = searchQueryState,
        onSyncBackStack = onSyncBackStack,
        onDetentChange = { sheetDetentState.value = it },
        onDetentBeforeSettingsChange = { detentBeforeSettingsState.value = it },
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onDismissAttributionDialog = onDismissAttributionDialog
    )

    OpenHikesShellStage(
        trailMapRenderer = trailMapRenderer,
        shellLayout = shellLayout,
        sheetDetent = sheetDetentState.value,
        sheetTopOffsetProvider = sheetTopOffsetProvider,
        chromeUiState = chromeUiState,
        initialCameraState = initialCameraState,
        pendingCameraCommand = pendingCameraCommand,
        onDetentChange = { sheetDetentState.value = it },
        onSheetTopOffsetChange = { sheetTopOffsetProvider = it },
        onToggleHikingRoutes = onToggleHikingRoutes,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onResetNorth = onResetNorth,
        onCameraChanged = onCameraChanged,
        onCameraCommandHandled = onCameraCommandHandled,
        onOpenAttributionDialog = onOpenAttributionDialog,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onOpenUrl = onOpenUrl,
        navigationContent = navigationContent,
        modifier = modifier
    )
}

@Composable
private fun rememberShellNavigation(
    backStack: NavBackStack<NavKey>,
    navigator: OpenHikesNavigator,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    detentBeforeSettings: SheetDetent?,
    showAttributionDialog: Boolean,
    searchQueryState: State<String>,
    onSyncBackStack: (List<Any>) -> Unit,
    onDetentChange: (SheetDetent) -> Unit,
    onDetentBeforeSettingsChange: (SheetDetent?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onDismissAttributionDialog: () -> Unit
): @Composable () -> Unit {
    CoordinateShellNavigation(
        backStack = backStack,
        canPop = navigator.canPop,
        sheetDetent = sheetDetent,
        detentBeforeSettings = detentBeforeSettings,
        showAttributionDialog = showAttributionDialog,
        searchQuery = searchQueryState.value,
        shellLayout = shellLayout,
        onSyncBackStack = onSyncBackStack,
        onDetentChange = onDetentChange,
        onClearDetentBeforeSettings = { onDetentBeforeSettingsChange(null) },
        onDismissAttributionDialog = onDismissAttributionDialog,
        onClearSearch = onClearSearch
    )
    return rememberNavigationContent(
        backStack = backStack,
        navigator = navigator,
        entryProviderInstallers = entryProviderInstallers,
        searchQueryState = searchQueryState,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onSearchFocused = { onDetentChange(SheetDetent.EXPANDED) },
        onOpenSettings = {
            if (detentBeforeSettings == null) onDetentBeforeSettingsChange(sheetDetent)
            onDetentChange(SheetDetent.EXPANDED)
            navigator.navigate(SettingsRoute)
        }
    )
}

@Composable
private fun CoordinateShellNavigation(
    backStack: NavBackStack<NavKey>,
    canPop: Boolean,
    sheetDetent: SheetDetent,
    detentBeforeSettings: SheetDetent?,
    showAttributionDialog: Boolean,
    searchQuery: String,
    shellLayout: ShellLayout,
    onSyncBackStack: (List<Any>) -> Unit,
    onDetentChange: (SheetDetent) -> Unit,
    onClearDetentBeforeSettings: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onClearSearch: () -> Unit
) {
    LaunchedEffect(backStack.toList()) {
        onSyncBackStack(backStack.toList())
        if (backStack.lastOrNull() == MapHomeRoute) {
            detentBeforeSettings?.let { previous ->
                onDetentChange(previous)
                onClearDetentBeforeSettings()
            }
        }
    }
    ShellBackHandlers(
        canPop = canPop,
        showAttributionDialog = showAttributionDialog,
        searchQuery = searchQuery,
        shellLayout = shellLayout,
        sheetDetent = sheetDetent,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onClearSearch = onClearSearch,
        onCollapseSheet = { onDetentChange(SheetDetent.PEEK) }
    )
}

@Composable
private fun OpenHikesShellStage(
    trailMapRenderer: TrailMapRenderer,
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    sheetTopOffsetProvider: () -> Float,
    chromeUiState: MapChromeUiState,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    onDetentChange: (SheetDetent) -> Unit,
    onSheetTopOffsetChange: (() -> Float) -> Unit,
    onToggleHikingRoutes: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    onCameraChanged: (MapCameraState) -> Unit,
    onCameraCommandHandled: (Long) -> Unit,
    onOpenAttributionDialog: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    navigationContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(OpenHikesTestTags.APP_ROOT)
    ) {
        TrailMapHost(
            renderer = trailMapRenderer,
            cameraState = initialCameraState,
            cameraCommand = pendingCameraCommand,
            overlayState = chromeUiState.overlayState,
            viewportPadding = if (shellLayout == ShellLayout.SIDE_PANEL) {
                PaddingValues(start = SidePanelViewportInset)
            } else {
                PaddingValues(bottom = SheetPeekViewportInset)
            },
            interactions = MapInteractionEvents(onCameraChanged = onCameraChanged),
            onCameraCommandHandled = onCameraCommandHandled
        )
        MapFloatingControls(
            shellLayout = shellLayout,
            sheetDetent = sheetDetent,
            sheetTopOffsetProvider = sheetTopOffsetProvider,
            overlayState = chromeUiState.overlayState,
            showAttributionDialog = chromeUiState.showAttributionDialog,
            onToggleHikingRoutes = onToggleHikingRoutes,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onResetNorth = onResetNorth,
            onOpenAttributionDialog = onOpenAttributionDialog,
            onDismissAttributionDialog = onDismissAttributionDialog,
            onOpenUrl = onOpenUrl
        )
        AdaptiveChromeHost(
            shellLayout = shellLayout,
            sheetDetent = sheetDetent,
            onDetentChange = onDetentChange,
            onSheetTopOffsetChange = onSheetTopOffsetChange,
            navigationContent = navigationContent
        )
    }
}

@Composable
private fun rememberNavigationContent(
    backStack: NavBackStack<NavKey>,
    navigator: OpenHikesNavigator,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    searchQueryState: State<String>,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocused: () -> Unit,
    onOpenSettings: () -> Unit
): @Composable () -> Unit {
    val currentOnSearchQueryChange by rememberUpdatedState(onSearchQueryChange)
    val currentOnClearSearch by rememberUpdatedState(onClearSearch)
    val currentOnSearchFocused by rememberUpdatedState(onSearchFocused)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)

    return remember(backStack, navigator, entryProviderInstallers) {
        movableContentOf {
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.pop() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider<NavKey> {
                    entry<MapHomeRoute> {
                        MapHomeScreen(
                            searchQuery = searchQueryState.value,
                            onSearchQueryChange = currentOnSearchQueryChange,
                            onClearSearch = currentOnClearSearch,
                            onSearchFocused = currentOnSearchFocused,
                            onOpenSettings = currentOnOpenSettings
                        )
                    }
                    entryProviderInstallers.forEach { install -> install(navigator) }
                }
            )
        }
    }
}

@Composable
private fun AdaptiveChromeHost(
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    onDetentChange: (SheetDetent) -> Unit,
    onSheetTopOffsetChange: (() -> Float) -> Unit,
    navigationContent: @Composable () -> Unit
) {
    if (shellLayout == ShellLayout.SIDE_PANEL) {
        MapSidePanel { navigationContent() }
    } else {
        MapBottomSheet(
            detent = sheetDetent,
            onDetentChange = onDetentChange,
            onSheetTopOffsetChange = onSheetTopOffsetChange
        ) {
            navigationContent()
        }
    }
}

@Composable
private fun ShellBackHandlers(
    canPop: Boolean,
    showAttributionDialog: Boolean,
    searchQuery: String,
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    onDismissAttributionDialog: () -> Unit,
    onClearSearch: () -> Unit,
    onCollapseSheet: () -> Unit
) {
    BackHandler(enabled = !canPop && showAttributionDialog, onBack = onDismissAttributionDialog)
    BackHandler(
        enabled = !canPop && !showAttributionDialog && searchQuery.isNotBlank(),
        onBack = onClearSearch
    )
    BackHandler(
        enabled = !canPop &&
            !showAttributionDialog &&
            searchQuery.isBlank() &&
            shellLayout == ShellLayout.BOTTOM_SHEET &&
            sheetDetent != SheetDetent.PEEK,
        onBack = onCollapseSheet
    )
}
