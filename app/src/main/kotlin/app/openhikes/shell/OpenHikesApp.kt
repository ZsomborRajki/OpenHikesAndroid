package app.openhikes.shell

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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteEditOverlay
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapTrailDraftOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.TrailDraftMapInteractionHandler
import app.openhikes.core.map.TrailMapHost
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.WalkTimeLeftMark
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.feature.hikes.HikeLibraryRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val SheetPeekViewportInset = 112.dp
private val EmptyLiveOverlayFlow: StateFlow<MapRouteOverlay?> = MutableStateFlow(null)
private val EmptyWalkCoverageFlow: StateFlow<MapWalkCoverageOverlay?> = MutableStateFlow(null)
private val EmptyRouteEditOverlayFlow: StateFlow<MapRouteEditOverlay?> = MutableStateFlow(null)
private val EmptyTrailDraftOverlayFlow: StateFlow<MapTrailDraftOverlay?> = MutableStateFlow(null)

@Immutable
private data class ViewportChromeMetrics(val sidePanelEdge: Dp, val viewportPadding: PaddingValues)

@Immutable
private data class ShellMapOverlays(
    val routeHighlightState: StateFlow<MapRouteHighlight?>,
    val liveRecordingOverlayState: StateFlow<MapRouteOverlay?>,
    val walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?>,
    val routeEditOverlayState: StateFlow<MapRouteEditOverlay?>,
    val routeEditHandlerProvider: () -> RouteEditMapInteractionHandler?,
    val trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?>,
    val trailDraftHandlerProvider: () -> TrailDraftMapInteractionHandler?
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
 * Callbacks and reactive search state provided to the root `MapHomeRoute` inside the shell.
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
        routeEditOverlayState = shellViewModel.routeEditOverlay,
        routeEditHandlerProvider = shellViewModel::routeEditHandler,
        trailDraftOverlayState = shellViewModel.trailDraftOverlay,
        trailDraftHandlerProvider = shellViewModel::trailDraftHandler,
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
    routeEditOverlayState: StateFlow<MapRouteEditOverlay?> = EmptyRouteEditOverlayFlow,
    routeEditHandlerProvider: () -> RouteEditMapInteractionHandler? = { null },
    trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?> = EmptyTrailDraftOverlayFlow,
    trailDraftHandlerProvider: () -> TrailDraftMapInteractionHandler? = { null },
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
        navHostState = navHostState,
        chromeUiState = chromeUiState,
        initialCameraState = initialCameraState,
        pendingCameraCommand = pendingCameraCommand,
        mapOverlays = ShellMapOverlays(
            routeHighlightState = routeHighlightState,
            liveRecordingOverlayState = liveRecordingOverlayState,
            walkCoverageOverlayState = walkCoverageOverlayState,
            routeEditOverlayState = routeEditOverlayState,
            routeEditHandlerProvider = routeEditHandlerProvider,
            trailDraftOverlayState = trailDraftOverlayState,
            trailDraftHandlerProvider = trailDraftHandlerProvider
        ),
        isRecordingActive = isRecordingActive,
        walkChrome = MapWalkChromeState(
            session = walkSession,
            timeLeftMarkFlow = walkTimeLeftMarkFlow,
            onToggleJunctionWarnings = onToggleJunctionWarnings,
            onOpenWalkedHike = navHostState.onOpenHike,
            onDismissStartNotice = onDismissWalkStartNotice
        ),
        onZoomIn = onZoomIn,
        onZoomOut = onZoomOut,
        onResetNorth = onResetNorth,
        onCameraChanged = onCameraChanged,
        onMapTap = onMapTap,
        onCameraCommandHandled = onCameraCommandHandled,
        onOpenAttributionDialog = onOpenAttributionDialog,
        onDismissAttributionDialog = onDismissAttributionDialog,
        onOpenUrl = onOpenUrl,
        modifier = modifier
    )
    ShellWalkDialogs(walkSession, walkDialogs, navHostState.onProceedToRecording)
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

@Composable
private fun OpenHikesShellStage(
    trailMapRenderer: TrailMapRenderer,
    shellLayout: ShellLayout,
    navHostState: ShellNavigationHostState,
    chromeUiState: MapChromeUiState,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    mapOverlays: ShellMapOverlays,
    isRecordingActive: Boolean,
    walkChrome: MapWalkChromeState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    onCameraChanged: (MapCameraState) -> Unit,
    onMapTap: (GeoCoordinate) -> Unit,
    onCameraCommandHandled: (Long) -> Unit,
    onOpenAttributionDialog: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
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

        ShellTrailMap(
            trailMapRenderer = trailMapRenderer,
            initialCameraState = initialCameraState,
            pendingCameraCommand = pendingCameraCommand,
            overlayState = chromeUiState.overlayState,
            viewportPadding = metrics.viewportPadding,
            interactions = MapInteractionEvents(onCameraChanged = onCameraChanged, onMapTap = onMapTap),
            onCameraCommandHandled = onCameraCommandHandled,
            mapOverlays = mapOverlays
        )
        MapFloatingControls(
            shellLayout = shellLayout,
            sheetDetent = navHostState.sheetDetent,
            sheetTopOffsetProvider = sheetTopOffsetProvider,
            sidePanelEdge = metrics.sidePanelEdge,
            overlayState = chromeUiState.overlayState,
            showAttributionDialog = chromeUiState.showAttributionDialog,
            isMapModesOpen = navHostState.isMapModesOpen,
            isRecordingActive = isRecordingActive,
            showTrailMakerButton = navHostState.showTrailMakerButton,
            walkChrome = walkChrome,
            onOpenRecording = navHostState.onOpenRecording,
            onOpenTrailMaker = navHostState.onOpenTrailMaker,
            onOpenMapModes = navHostState.onOpenMapModes,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onResetNorth = onResetNorth,
            onOpenAttributionDialog = onOpenAttributionDialog,
            onDismissAttributionDialog = onDismissAttributionDialog,
            onOpenUrl = onOpenUrl
        )
        AdaptiveChromeHost(
            shellLayout = shellLayout,
            sheetDetent = navHostState.sheetDetent,
            onDetentChange = navHostState.onDetentChange,
            onSheetTopOffsetChange = { sheetTopOffsetProvider = it },
            onPanelEdgeChanged = { measuredPanelEdge = it },
            navigationContent = navHostState.navigationContent
        )
    }
}

@Composable
private fun ShellTrailMap(
    trailMapRenderer: TrailMapRenderer,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    overlayState: MapOverlayState,
    viewportPadding: PaddingValues,
    interactions: MapInteractionEvents,
    onCameraCommandHandled: (Long) -> Unit,
    mapOverlays: ShellMapOverlays
) {
    TrailMapHost(
        renderer = trailMapRenderer,
        cameraState = initialCameraState,
        cameraCommand = pendingCameraCommand,
        overlayState = overlayState,
        viewportPadding = viewportPadding,
        interactions = interactions,
        onCameraCommandHandled = onCameraCommandHandled,
        highlightState = mapOverlays.routeHighlightState,
        liveRecordingOverlayState = mapOverlays.liveRecordingOverlayState,
        walkCoverageOverlayState = mapOverlays.walkCoverageOverlayState,
        routeEditOverlayState = mapOverlays.routeEditOverlayState,
        routeEditHandlerProvider = mapOverlays.routeEditHandlerProvider,
        trailDraftOverlayState = mapOverlays.trailDraftOverlayState,
        trailDraftHandlerProvider = mapOverlays.trailDraftHandlerProvider
    )
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
