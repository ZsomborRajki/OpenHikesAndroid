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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
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
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.TrailMapHost
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.WalkTimeLeftMark
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.OfflineMapsRoute
import app.openhikes.core.ui.navigation.OpenHikesNavigator
import app.openhikes.core.ui.navigation.OpenHikesRoute
import app.openhikes.core.ui.navigation.RecordingRoute
import app.openhikes.core.ui.navigation.SettingsRoute
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.feature.hikes.HikeLibraryRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val SheetPeekViewportInset = 112.dp
private val EmptyLiveOverlayFlow: StateFlow<MapRouteOverlay?> = MutableStateFlow(null)
private val EmptyWalkCoverageFlow: StateFlow<MapWalkCoverageOverlay?> = MutableStateFlow(null)

@Immutable
private data class ViewportChromeMetrics(val sidePanelEdge: Dp, val viewportPadding: PaddingValues)

@Immutable
private data class ShellNavigationHostState(
    val sheetDetent: SheetDetent,
    val onDetentChange: (SheetDetent) -> Unit,
    val onOpenHike: (HikeId) -> Unit,
    val onOpenRecording: () -> Unit,
    val onProceedToRecording: () -> Unit,
    val onOpenOfflineMaps: () -> Unit,
    val navigationContent: @Composable () -> Unit
)

@Immutable
internal data class ShellWalkDialogCallbacks(
    val showEndWalkForRecordingDialog: Boolean = false,
    val onRequestEndWalkForRecording: () -> Unit = {},
    val onConfirmEndWalkForRecording: (() -> Unit) -> Unit = {},
    val onCancelEndWalkForRecording: () -> Unit = {},
    val onKeepFollowingOffTrail: () -> Unit = {},
    val onRecordInsteadFromOffTrail: (() -> Unit) -> Unit = {}
)

/**
 * Callbacks and reactive search state provided to the root [MapHomeRoute] inside the shell.
 */
@Immutable
data class ShellHomeScope(
    val searchQueryState: State<String>,
    val onSearchQueryChange: (String) -> Unit,
    val onClearSearch: () -> Unit,
    val onSearchFocusChanged: (Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHike: (HikeId) -> Unit,
    val onHikeDeleted: (HikeId) -> Unit
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
    openRecordingRequestCount: Int = 0,
    openWalkHikeRequest: Pair<Int, HikeId>? = null,
    onRequestAutoStartRecording: () -> Unit = {},
    windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass,
    shellViewModel: MapShellViewModel = hiltViewModel(),
    homeContent: @Composable (ShellHomeScope) -> Unit = { scope ->
        HikeLibraryRoute(
            searchQuery = scope.searchQueryState.value,
            onSearchQueryChange = scope.onSearchQueryChange,
            onClearSearch = scope.onClearSearch,
            onSearchFocusChanged = scope.onSearchFocusChanged,
            onOpenSettings = scope.onOpenSettings,
            onOpenHike = scope.onOpenHike,
            onHikeDeleted = scope.onHikeDeleted
        )
    }
) {
    val chromeUiState by shellViewModel.chromeUiState.collectAsStateWithLifecycle()
    val searchQueryState = shellViewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingCameraCommand by shellViewModel.pendingCameraCommand.collectAsStateWithLifecycle()
    val isRecordingActive by shellViewModel.isRecordingActive.collectAsStateWithLifecycle()
    val walkSession by shellViewModel.walkSession.collectAsStateWithLifecycle()
    val showEndWalkForRecording by shellViewModel.showEndWalkForRecordingDialog.collectAsStateWithLifecycle()
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
        routeHighlightState = shellViewModel.routeHighlight,
        isRecordingActive = isRecordingActive,
        liveRecordingOverlayState = shellViewModel.liveRecordingOverlay,
        walkCoverageOverlayState = shellViewModel.walkCoverageOverlay,
        walkSession = walkSession,
        walkTimeLeftMarkFlow = shellViewModel.walkTimeLeftMark,
        walkDialogs = ShellWalkDialogCallbacks(
            showEndWalkForRecordingDialog = showEndWalkForRecording,
            onRequestEndWalkForRecording = shellViewModel::onRequestEndWalkForRecording,
            onConfirmEndWalkForRecording = shellViewModel::onConfirmEndWalkForRecording,
            onCancelEndWalkForRecording = shellViewModel::onCancelEndWalkForRecording,
            onKeepFollowingOffTrail = shellViewModel::onKeepFollowingOffTrail,
            onRecordInsteadFromOffTrail = shellViewModel::onRecordInsteadFromOffTrail
        ),
        onToggleJunctionWarnings = shellViewModel::onToggleJunctionWarnings,
        onDismissWalkStartNotice = shellViewModel::onDismissWalkStartNotice,
        openRecordingRequestCount = openRecordingRequestCount,
        openWalkHikeRequest = openWalkHikeRequest,
        onRequestAutoStartRecording = onRequestAutoStartRecording,
        onSyncBackStack = shellViewModel::syncBackStack,
        onSearchQueryChange = shellViewModel::onSearchQueryChange,
        onClearSearch = shellViewModel::onClearSearch,
        onToggleHikingRoutes = shellViewModel::onToggleHikingRoutes,
        onZoomIn = shellViewModel::onZoomIn,
        onZoomOut = shellViewModel::onZoomOut,
        onResetNorth = shellViewModel::onResetNorth,
        onCameraChanged = shellViewModel::onCameraChanged,
        onMapTap = shellViewModel::onMapTap,
        onCameraCommandHandled = shellViewModel::onCameraCommandHandled,
        onOpenAttributionDialog = shellViewModel::onOpenAttributionDialog,
        onDismissAttributionDialog = shellViewModel::onDismissAttributionDialog,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        modifier = modifier,
        homeContent = homeContent
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
    routeHighlightState: StateFlow<MapRouteHighlight?>,
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
    modifier: Modifier = Modifier,
    onMapTap: (GeoCoordinate) -> Unit = {},
    isRecordingActive: Boolean = false,
    liveRecordingOverlayState: StateFlow<MapRouteOverlay?> = EmptyLiveOverlayFlow,
    walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?> = EmptyWalkCoverageFlow,
    walkSession: TrailWalkSessionState = TrailWalkSessionState(),
    walkTimeLeftMarkFlow: StateFlow<WalkTimeLeftMark?> = EmptyWalkTimeLeftFlow,
    walkDialogs: ShellWalkDialogCallbacks = ShellWalkDialogCallbacks(),
    onToggleJunctionWarnings: () -> Unit = {},
    onDismissWalkStartNotice: () -> Unit = {},
    openRecordingRequestCount: Int = 0,
    openWalkHikeRequest: Pair<Int, HikeId>? = null,
    onRequestAutoStartRecording: () -> Unit = {},
    homeContent: @Composable (ShellHomeScope) -> Unit
) {
    val shellLayout = resolveShellLayout(windowSizeClass)
    val navHostState = rememberShellNavigationHost(
        launchConfig = launchConfig,
        shellLayout = shellLayout,
        entryProviderInstallers = entryProviderInstallers,
        showAttributionDialog = chromeUiState.showAttributionDialog,
        searchQueryState = searchQueryState,
        isRecordingActive = isRecordingActive,
        isWalkActive = walkSession.isWalkActive,
        openRecordingRequestCount = openRecordingRequestCount,
        openWalkHikeRequest = openWalkHikeRequest,
        onRequestAutoStartRecording = onRequestAutoStartRecording,
        onRequestEndWalkForRecording = walkDialogs.onRequestEndWalkForRecording,
        onSyncBackStack = onSyncBackStack,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onDismissAttributionDialog = onDismissAttributionDialog,
        homeContent = homeContent
    )
    OpenHikesShellStage(
        trailMapRenderer = trailMapRenderer,
        shellLayout = shellLayout,
        sheetDetent = navHostState.sheetDetent,
        chromeUiState = chromeUiState,
        initialCameraState = initialCameraState,
        pendingCameraCommand = pendingCameraCommand,
        routeHighlightState = routeHighlightState,
        isRecordingActive = isRecordingActive,
        liveRecordingOverlayState = liveRecordingOverlayState,
        walkCoverageOverlayState = walkCoverageOverlayState,
        walkChrome = MapWalkChromeState(
            session = walkSession,
            timeLeftMarkFlow = walkTimeLeftMarkFlow,
            onToggleJunctionWarnings = onToggleJunctionWarnings,
            onOpenWalkedHike = navHostState.onOpenHike,
            onDismissStartNotice = onDismissWalkStartNotice
        ),
        onOpenRecording = navHostState.onOpenRecording,
        onOpenOfflineMaps = navHostState.onOpenOfflineMaps,
        onDetentChange = navHostState.onDetentChange,
        onToggleHikingRoutes = onToggleHikingRoutes,
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onResetNorth = onResetNorth,
        onCameraChanged = onCameraChanged,
        onMapTap = onMapTap,
        onCameraCommandHandled = onCameraCommandHandled,
        onOpenAttributionDialog = onOpenAttributionDialog,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onOpenUrl = onOpenUrl,
        navigationContent = navHostState.navigationContent,
        modifier = modifier
    )
    ShellWalkDialogs(
        walkSession = walkSession,
        walkDialogs = walkDialogs,
        onProceedToRecording = navHostState.onProceedToRecording
    )
}

@Composable
private fun ShellWalkDialogs(
    walkSession: TrailWalkSessionState,
    walkDialogs: ShellWalkDialogCallbacks,
    onProceedToRecording: () -> Unit
) {
    if (walkDialogs.showEndWalkForRecordingDialog && walkSession.isWalkActive) {
        EndWalkForRecordingDialog(
            trailTitle = walkSession.walkedHikeTitle,
            onConfirm = { walkDialogs.onConfirmEndWalkForRecording(onProceedToRecording) },
            onDismiss = walkDialogs.onCancelEndWalkForRecording
        )
    }
    walkSession.offTrailQuestion?.let { question ->
        OffTrailQuestionDialog(
            question = question,
            onKeepFollowing = walkDialogs.onKeepFollowingOffTrail,
            onRecordInstead = { walkDialogs.onRecordInsteadFromOffTrail(onProceedToRecording) }
        )
    }
}

@Immutable
private data class ShellNavActions(
    val exitSearch: () -> Unit,
    val onSearchFocusChanged: (Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHike: (HikeId) -> Unit,
    val onOpenRecording: () -> Unit,
    val onProceedToRecording: () -> Unit,
    val onOpenOfflineMaps: () -> Unit,
    val onHikeDeleted: (HikeId) -> Unit
)

@Stable
private class ShellNavControllers(
    val sheetDetentState: MutableState<SheetDetent>,
    val detentBeforeSettingsState: MutableState<SheetDetent?>,
    val isSearchActiveState: MutableState<Boolean>,
    val backStack: NavBackStack<NavKey>,
    val navigator: OpenHikesNavigator
) {
    var sheetDetent: SheetDetent
        get() = sheetDetentState.value
        set(value) {
            sheetDetentState.value = value
        }

    var detentBeforeSettings: SheetDetent?
        get() = detentBeforeSettingsState.value
        set(value) {
            detentBeforeSettingsState.value = value
        }

    var isSearchActive: Boolean
        get() = isSearchActiveState.value
        set(value) {
            isSearchActiveState.value = value
        }
}

@Composable
private fun rememberShellNavControllers(startsWithExpandedSheet: Boolean): ShellNavControllers {
    val sheetDetentState = rememberSaveable {
        mutableStateOf(if (startsWithExpandedSheet) SheetDetent.HALF else SheetDetent.PEEK)
    }
    val detentBeforeSettingsState = rememberSaveable { mutableStateOf<SheetDetent?>(null) }
    val isSearchActiveState = rememberSaveable { mutableStateOf(false) }
    val backStack = rememberNavBackStack(MapHomeRoute)
    val navigator = remember(backStack) {
        OpenHikesNavigator(backStack, onCollapseSheet = { sheetDetentState.value = SheetDetent.PEEK })
    }
    return remember(sheetDetentState, detentBeforeSettingsState, isSearchActiveState, backStack, navigator) {
        ShellNavControllers(
            sheetDetentState = sheetDetentState,
            detentBeforeSettingsState = detentBeforeSettingsState,
            isSearchActiveState = isSearchActiveState,
            backStack = backStack,
            navigator = navigator
        )
    }
}

@Composable
private fun rememberShellNavigationHost(
    launchConfig: LaunchConfig,
    shellLayout: ShellLayout,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    showAttributionDialog: Boolean,
    searchQueryState: State<String>,
    isRecordingActive: Boolean,
    isWalkActive: Boolean,
    openRecordingRequestCount: Int,
    openWalkHikeRequest: Pair<Int, HikeId>?,
    onRequestAutoStartRecording: () -> Unit,
    onRequestEndWalkForRecording: () -> Unit,
    onSyncBackStack: (List<Any>) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    homeContent: @Composable (ShellHomeScope) -> Unit
): ShellNavigationHostState {
    val controllers = rememberShellNavControllers(launchConfig.startsWithExpandedSheet)
    val actions = rememberShellNavActions(
        controllers = controllers,
        searchQuery = searchQueryState.value,
        isRecordingActive = isRecordingActive,
        isWalkActive = isWalkActive,
        onRequestAutoStartRecording = onRequestAutoStartRecording,
        onRequestEndWalkForRecording = onRequestEndWalkForRecording,
        onClearSearch = onClearSearch
    )
    CoordinateShellNavigation(
        controllers = controllers,
        showAttributionDialog = showAttributionDialog,
        searchQuery = searchQueryState.value,
        shellLayout = shellLayout,
        openRecordingRequestCount = openRecordingRequestCount,
        openWalkHikeRequest = openWalkHikeRequest,
        onSyncBackStack = onSyncBackStack,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onExitSearch = actions.exitSearch
    )
    val navigationContent = rememberNavigationContent(
        backStack = controllers.backStack,
        navigator = controllers.navigator,
        entryProviderInstallers = entryProviderInstallers,
        searchQueryState = searchQueryState,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        actions = actions,
        homeContent = homeContent
    )
    return ShellNavigationHostState(
        sheetDetent = controllers.sheetDetent,
        onDetentChange = { next ->
            controllers.sheetDetent = next
            if (next == SheetDetent.PEEK && controllers.isSearchActive) actions.exitSearch()
        },
        onOpenHike = actions.onOpenHike,
        onOpenRecording = actions.onOpenRecording,
        onProceedToRecording = actions.onProceedToRecording,
        onOpenOfflineMaps = actions.onOpenOfflineMaps,
        navigationContent = navigationContent
    )
}

@Composable
private fun rememberShellNavActions(
    controllers: ShellNavControllers,
    searchQuery: String,
    isRecordingActive: Boolean,
    isWalkActive: Boolean,
    onRequestAutoStartRecording: () -> Unit,
    onRequestEndWalkForRecording: () -> Unit,
    onClearSearch: () -> Unit
): ShellNavActions {
    val focusManager = LocalFocusManager.current
    val currentSearchQuery by rememberUpdatedState(searchQuery)
    val currentIsRecordingActive by rememberUpdatedState(isRecordingActive)
    val currentIsWalkActive by rememberUpdatedState(isWalkActive)
    val exitSearch = {
        onClearSearch()
        controllers.isSearchActive = false
        focusManager.clearFocus(force = true)
    }
    val proceedToRecording = {
        controllers.proceedToRecordingRoute(
            isRecordingActive = currentIsRecordingActive,
            exitSearch = exitSearch,
            clearFocus = { focusManager.clearFocus(force = true) },
            onRequestAutoStartRecording = onRequestAutoStartRecording
        )
    }
    return ShellNavActions(
        exitSearch = exitSearch,
        onSearchFocusChanged = { focused ->
            if (focused) {
                controllers.isSearchActive = true
                controllers.sheetDetent = SheetDetent.EXPANDED
            } else if (currentSearchQuery.isBlank()) {
                controllers.isSearchActive = false
            }
        },
        onOpenSettings = {
            if (controllers.isSearchActive) exitSearch()
            if (controllers.detentBeforeSettings == null) controllers.detentBeforeSettings = controllers.sheetDetent
            controllers.sheetDetent = SheetDetent.EXPANDED
            controllers.navigator.navigate(SettingsRoute)
        },
        onOpenHike = { hikeId ->
            focusManager.clearFocus(force = true)
            controllers.navigateHalfSheetIfPeek(HikeDetailRoute(hikeId))
        },
        onOpenRecording = {
            if (!currentIsRecordingActive && currentIsWalkActive) {
                onRequestEndWalkForRecording()
            } else {
                proceedToRecording()
            }
        },
        onProceedToRecording = proceedToRecording,
        onOpenOfflineMaps = {
            if (controllers.isSearchActive) exitSearch()
            focusManager.clearFocus(force = true)
            controllers.sheetDetent = SheetDetent.HALF
            controllers.navigator.navigate(OfflineMapsRoute(overMap = true))
        },
        onHikeDeleted = { hikeId -> controllers.navigator.removeRoutesForHike(hikeId) }
    )
}

private fun ShellNavControllers.proceedToRecordingRoute(
    isRecordingActive: Boolean,
    exitSearch: () -> Unit,
    clearFocus: () -> Unit,
    onRequestAutoStartRecording: () -> Unit
) {
    if (isSearchActive) exitSearch()
    clearFocus()
    if (!isRecordingActive) onRequestAutoStartRecording()
    navigateHalfSheetIfPeek(RecordingRoute)
}

private fun ShellNavControllers.navigateHalfSheetIfPeek(route: OpenHikesRoute) {
    if (sheetDetent == SheetDetent.PEEK) sheetDetent = SheetDetent.HALF
    if (backStack.lastOrNull() != route) navigator.navigate(route)
}

@Composable
private fun CoordinateShellNavigation(
    controllers: ShellNavControllers,
    showAttributionDialog: Boolean,
    searchQuery: String,
    shellLayout: ShellLayout,
    openRecordingRequestCount: Int,
    openWalkHikeRequest: Pair<Int, HikeId>?,
    onSyncBackStack: (List<Any>) -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onExitSearch: () -> Unit
) {
    LaunchedEffect(openRecordingRequestCount) {
        if (openRecordingRequestCount > 0) {
            if (controllers.isSearchActive) onExitSearch()
            if (controllers.sheetDetent == SheetDetent.PEEK) controllers.sheetDetent = SheetDetent.HALF
            if (controllers.backStack.lastOrNull() != RecordingRoute) {
                controllers.navigator.navigate(RecordingRoute)
            }
        }
    }
    LaunchedEffect(openWalkHikeRequest) {
        val targetHikeId = openWalkHikeRequest?.second ?: return@LaunchedEffect
        if (controllers.isSearchActive) onExitSearch()
        if (controllers.sheetDetent == SheetDetent.PEEK) controllers.sheetDetent = SheetDetent.HALF
        val targetRoute = HikeDetailRoute(targetHikeId)
        if (controllers.backStack.lastOrNull() != targetRoute) {
            controllers.navigator.navigate(targetRoute)
        }
    }
    LaunchedEffect(controllers.backStack.toList()) {
        onSyncBackStack(controllers.backStack.toList())
        if (controllers.backStack.lastOrNull() == MapHomeRoute) {
            controllers.detentBeforeSettings?.let { previous ->
                controllers.sheetDetent = previous
                controllers.detentBeforeSettings = null
            }
        }
    }
    ShellBackHandlers(
        canPop = controllers.navigator.canPop,
        showAttributionDialog = showAttributionDialog,
        searchQuery = searchQuery,
        isSearchActive = controllers.isSearchActive,
        shellLayout = shellLayout,
        sheetDetent = controllers.sheetDetent,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onExitSearch = onExitSearch,
        onCollapseSheet = { controllers.sheetDetent = SheetDetent.PEEK }
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
    routeHighlightState: StateFlow<MapRouteHighlight?>,
    isRecordingActive: Boolean,
    liveRecordingOverlayState: StateFlow<MapRouteOverlay?>,
    walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?>,
    walkChrome: MapWalkChromeState,
    onOpenRecording: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onDetentChange: (SheetDetent) -> Unit,
    onToggleHikingRoutes: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    onCameraChanged: (MapCameraState) -> Unit,
    onMapTap: (GeoCoordinate) -> Unit,
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
            interactions = MapInteractionEvents(onCameraChanged = onCameraChanged, onMapTap = onMapTap),
            onCameraCommandHandled = onCameraCommandHandled,
            highlightState = routeHighlightState,
            liveRecordingOverlayState = liveRecordingOverlayState,
            walkCoverageOverlayState = walkCoverageOverlayState
        )
        MapFloatingControls(
            shellLayout = shellLayout,
            sheetDetent = sheetDetent,
            sheetTopOffsetProvider = sheetTopOffsetProvider,
            sidePanelEdge = metrics.sidePanelEdge,
            overlayState = chromeUiState.overlayState,
            showAttributionDialog = chromeUiState.showAttributionDialog,
            isRecordingActive = isRecordingActive,
            walkChrome = walkChrome,
            onOpenRecording = onOpenRecording,
            onOpenOfflineMaps = onOpenOfflineMaps,
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
    actions: ShellNavActions,
    homeContent: @Composable (ShellHomeScope) -> Unit
): @Composable () -> Unit {
    val currentOnSearchQueryChange by rememberUpdatedState(onSearchQueryChange)
    val currentOnClearSearch by rememberUpdatedState(onClearSearch)
    val currentActions by rememberUpdatedState(actions)
    val currentHomeContent by rememberUpdatedState(homeContent)

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
                        val navActions = currentActions
                        currentHomeContent(
                            ShellHomeScope(
                                searchQueryState = searchQueryState,
                                onSearchQueryChange = currentOnSearchQueryChange,
                                onClearSearch = currentOnClearSearch,
                                onSearchFocusChanged = navActions.onSearchFocusChanged,
                                onOpenSettings = navActions.onOpenSettings,
                                onOpenHike = navActions.onOpenHike,
                                onHikeDeleted = navActions.onHikeDeleted
                            )
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
