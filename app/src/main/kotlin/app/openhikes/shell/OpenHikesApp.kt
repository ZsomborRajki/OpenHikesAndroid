package app.openhikes.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
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

private val SheetPeekViewportInset = 112.dp

@Immutable
private data class ViewportChromeMetrics(val sidePanelEdge: Dp, val viewportPadding: PaddingValues)

@Immutable
private data class ShellNavigationHostState(
    val sheetDetent: SheetDetent,
    val onDetentChange: (SheetDetent) -> Unit,
    val navigationContent: @Composable () -> Unit
)

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
    val navHostState = rememberShellNavigationHost(
        launchConfig = launchConfig,
        shellLayout = shellLayout,
        entryProviderInstallers = entryProviderInstallers,
        showAttributionDialog = chromeUiState.showAttributionDialog,
        searchQueryState = searchQueryState,
        onSyncBackStack = onSyncBackStack,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onDismissAttributionDialog = onDismissAttributionDialog
    )
    OpenHikesShellStage(
        trailMapRenderer = trailMapRenderer,
        shellLayout = shellLayout,
        sheetDetent = navHostState.sheetDetent,
        chromeUiState = chromeUiState,
        initialCameraState = initialCameraState,
        pendingCameraCommand = pendingCameraCommand,
        onDetentChange = navHostState.onDetentChange,
        onToggleHikingRoutes = onToggleHikingRoutes,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onResetNorth = onResetNorth,
        onCameraChanged = onCameraChanged,
        onCameraCommandHandled = onCameraCommandHandled,
        onOpenAttributionDialog = onOpenAttributionDialog,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onOpenUrl = onOpenUrl,
        navigationContent = navHostState.navigationContent,
        modifier = modifier
    )
}

@Composable
private fun rememberShellNavigationHost(
    launchConfig: LaunchConfig,
    shellLayout: ShellLayout,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    showAttributionDialog: Boolean,
    searchQueryState: State<String>,
    onSyncBackStack: (List<Any>) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onDismissAttributionDialog: () -> Unit
): ShellNavigationHostState {
    val sheetDetentState = rememberSaveable {
        mutableStateOf(if (launchConfig.startsWithExpandedSheet) SheetDetent.HALF else SheetDetent.PEEK)
    }
    val detentBeforeSettingsState = rememberSaveable { mutableStateOf<SheetDetent?>(null) }
    val searchActiveState = rememberSaveable { mutableStateOf(false) }
    val backStack = rememberNavBackStack(MapHomeRoute)
    val navigator = remember(backStack) { OpenHikesNavigator(backStack) }
    val focusManager = LocalFocusManager.current
    val exitSearch = {
        onClearSearch()
        searchActiveState.value = false
        focusManager.clearFocus(force = true)
    }
    CoordinateShellNavigation(
        backStack = backStack,
        canPop = navigator.canPop,
        sheetDetent = sheetDetentState.value,
        detentBeforeSettings = detentBeforeSettingsState.value,
        showAttributionDialog = showAttributionDialog,
        searchQuery = searchQueryState.value,
        isSearchActive = searchActiveState.value,
        shellLayout = shellLayout,
        onSyncBackStack = onSyncBackStack,
        onDetentChange = { sheetDetentState.value = it },
        onClearDetentBeforeSettings = { detentBeforeSettingsState.value = null },
        onDismissAttributionDialog = onDismissAttributionDialog,
        onExitSearch = exitSearch
    )
    val navigationContent = rememberNavigationContent(
        backStack = backStack,
        navigator = navigator,
        entryProviderInstallers = entryProviderInstallers,
        searchQueryState = searchQueryState,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onSearchFocusChanged = { focused ->
            if (focused) {
                searchActiveState.value = true
                sheetDetentState.value = SheetDetent.EXPANDED
            } else if (searchQueryState.value.isBlank()) {
                searchActiveState.value = false
            }
        },
        onOpenSettings = {
            if (searchActiveState.value) exitSearch()
            if (detentBeforeSettingsState.value == null) detentBeforeSettingsState.value = sheetDetentState.value
            sheetDetentState.value = SheetDetent.EXPANDED
            navigator.navigate(SettingsRoute)
        }
    )
    return ShellNavigationHostState(
        sheetDetent = sheetDetentState.value,
        onDetentChange = { next ->
            sheetDetentState.value = next
            if (next == SheetDetent.PEEK && searchActiveState.value) exitSearch()
        },
        navigationContent = navigationContent
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
    isSearchActive: Boolean,
    shellLayout: ShellLayout,
    onSyncBackStack: (List<Any>) -> Unit,
    onDetentChange: (SheetDetent) -> Unit,
    onClearDetentBeforeSettings: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onExitSearch: () -> Unit
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
        isSearchActive = isSearchActive,
        shellLayout = shellLayout,
        sheetDetent = sheetDetent,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onExitSearch = onExitSearch,
        onCollapseSheet = { onDetentChange(SheetDetent.PEEK) }
    )
}

@Composable
private fun OpenHikesShellStage(
    trailMapRenderer: TrailMapRenderer,
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    chromeUiState: MapChromeUiState,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    onDetentChange: (SheetDetent) -> Unit,
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
    var sheetTopOffsetProvider by remember { mutableStateOf<() -> Float>({ 0f }) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(OpenHikesTestTags.APP_ROOT)
    ) {
        var measuredPanelEdge by remember(maxWidth, shellLayout) { mutableStateOf<Dp?>(null) }
        val metrics = rememberViewportChromeMetrics(
            availableWidth = maxWidth,
            shellLayout = shellLayout,
            measuredPanelEdge = measuredPanelEdge
        )

        TrailMapHost(
            renderer = trailMapRenderer,
            cameraState = initialCameraState,
            cameraCommand = pendingCameraCommand,
            overlayState = chromeUiState.overlayState,
            viewportPadding = metrics.viewportPadding,
            interactions = MapInteractionEvents(onCameraChanged = onCameraChanged),
            onCameraCommandHandled = onCameraCommandHandled
        )
        MapFloatingControls(
            shellLayout = shellLayout,
            sheetDetent = sheetDetent,
            sheetTopOffsetProvider = sheetTopOffsetProvider,
            sidePanelEdge = metrics.sidePanelEdge,
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
            onSheetTopOffsetChange = { sheetTopOffsetProvider = it },
            onPanelEdgeChanged = { measuredPanelEdge = it },
            navigationContent = navigationContent
        )
    }
}

@Composable
private fun rememberViewportChromeMetrics(
    availableWidth: Dp,
    shellLayout: ShellLayout,
    measuredPanelEdge: Dp?
): ViewportChromeMetrics {
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val safeStart = safePadding.calculateStartPadding(layoutDirection)
    val safeTop = safePadding.calculateTopPadding()
    val safeEnd = safePadding.calculateEndPadding(layoutDirection)
    val safeBottom = safePadding.calculateBottomPadding()

    val panelAvailableWidth = (availableWidth - safeStart - safeEnd - SidePanelOuterPadding * 2).coerceAtLeast(0.dp)
    val calculatedPanelEdge = safeStart + SidePanelOuterPadding + calculateSidePanelWidth(panelAvailableWidth)
    val effectivePanelEdge = (measuredPanelEdge ?: calculatedPanelEdge).coerceAtMost(availableWidth)

    val viewportPadding = if (shellLayout == ShellLayout.SIDE_PANEL) {
        PaddingValues(start = effectivePanelEdge, top = safeTop, end = safeEnd, bottom = safeBottom)
    } else {
        PaddingValues(start = safeStart, top = safeTop, end = safeEnd, bottom = SheetPeekViewportInset + safeBottom)
    }
    return ViewportChromeMetrics(sidePanelEdge = effectivePanelEdge, viewportPadding = viewportPadding)
}

@Composable
private fun rememberNavigationContent(
    backStack: NavBackStack<NavKey>,
    navigator: OpenHikesNavigator,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    searchQueryState: State<String>,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
): @Composable () -> Unit {
    val currentOnSearchQueryChange by rememberUpdatedState(onSearchQueryChange)
    val currentOnClearSearch by rememberUpdatedState(onClearSearch)
    val currentOnSearchFocusChanged by rememberUpdatedState(onSearchFocusChanged)
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
                            onSearchFocusChanged = currentOnSearchFocusChanged,
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
    onPanelEdgeChanged: (Dp) -> Unit,
    navigationContent: @Composable () -> Unit
) {
    if (shellLayout == ShellLayout.SIDE_PANEL) {
        MapSidePanel(onPanelEdgeChanged = onPanelEdgeChanged) { navigationContent() }
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
    isSearchActive: Boolean,
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    onDismissAttributionDialog: () -> Unit,
    onExitSearch: () -> Unit,
    onCollapseSheet: () -> Unit
) {
    BackHandler(enabled = !canPop && showAttributionDialog, onBack = onDismissAttributionDialog)
    BackHandler(
        enabled = !canPop && !showAttributionDialog && (isSearchActive || searchQuery.isNotBlank()),
        onBack = onExitSearch
    )
    BackHandler(
        enabled = !canPop &&
            !showAttributionDialog &&
            !isSearchActive &&
            searchQuery.isBlank() &&
            shellLayout == ShellLayout.BOTTOM_SHEET &&
            sheetDetent != SheetDetent.PEEK,
        onBack = onCollapseSheet
    )
}
