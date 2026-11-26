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
import app.openhikes.core.map.MapSearchAreaPillState
import app.openhikes.core.map.MapTrailDraftOverlay
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.RouteEditMapInteractionHandler
import app.openhikes.core.map.TrailDraftMapInteractionHandler
import app.openhikes.core.map.TrailMapHost
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
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
private val EmptyTrailPlacesOverlayFlow: StateFlow<MapTrailPlacesOverlay?> = MutableStateFlow(null)

@Immutable
private data class ViewportChromeMetrics(val sidePanelEdge: Dp, val viewportPadding: PaddingValues)

@Immutable
internal data class ShellMapOverlays(
    val liveRecordingOverlayState: StateFlow<MapRouteOverlay?> = EmptyLiveOverlayFlow,
    val walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?> = EmptyWalkCoverageFlow,
    val routeEditOverlayState: StateFlow<MapRouteEditOverlay?> = EmptyRouteEditOverlayFlow,
    val routeEditHandlerProvider: () -> RouteEditMapInteractionHandler? = { null },
    val trailDraftOverlayState: StateFlow<MapTrailDraftOverlay?> = EmptyTrailDraftOverlayFlow,
    val trailDraftHandlerProvider: () -> TrailDraftMapInteractionHandler? = { null },
    val trailPlacesOverlayState: StateFlow<MapTrailPlacesOverlay?> = EmptyTrailPlacesOverlayFlow,
    val trailPlacesHandlerProvider: () -> TrailPlacesMapInteractionHandler? = { null }
)

@Immutable
internal data class ShellPlacesRuntime(
    val searchAreaPill: MapSearchAreaPillState? = null,
    val activeRecordingHikeId: HikeId? = null,
    val defaultAddPlaceCoordinateProvider: (HikeId) -> GeoCoordinate = { MapCameraState.DEFAULT_CENTER },
    val latestRecordingCoordinateProvider: () -> GeoCoordinate? = { null }
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
    val activeRecordingHikeId by shellViewModel.activeRecordingHikeId.collectAsStateWithLifecycle()
    val searchAreaPill by shellViewModel.searchAreaPillState.collectAsStateWithLifecycle()
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
        mapOverlays = shellViewModel.mapOverlays,
        placesRuntime = shellViewModel.createPlacesRuntime(searchAreaPill, activeRecordingHikeId),
        walkSession = walkSession,
        walkTimeLeftMarkFlow = shellViewModel.walkTimeLeftMark,
        walkDialogs = shellViewModel.createWalkDialogs(showEndWalkForRecording),
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
    mapOverlays: ShellMapOverlays = ShellMapOverlays(),
    placesRuntime: ShellPlacesRuntime = ShellPlacesRuntime(),
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
        activeRecordingHikeId = placesRuntime.activeRecordingHikeId,
        isWalkActive = walkSession.isWalkActive,
        openRecordingRequestCount = openRecordingRequestCount,
        openWalkHikeRequest = openWalkHikeRequest,
        defaultAddPlaceCoordinateProvider = placesRuntime.defaultAddPlaceCoordinateProvider,
        latestRecordingCoordinateProvider = placesRuntime.latestRecordingCoordinateProvider,
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
        routeHighlightState = routeHighlightState,
        mapOverlays = mapOverlays,
        isRecordingActive = isRecordingActive,
        walkChrome = MapWalkChromeState(
            session = walkSession,
            timeLeftMarkFlow = walkTimeLeftMarkFlow,
            onToggleJunctionWarnings = onToggleJunctionWarnings,
            onOpenWalkedHike = navHostState.onOpenHike,
            onDismissStartNotice = onDismissWalkStartNotice
        ),
        searchAreaPill = placesRuntime.searchAreaPill,
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
    routeHighlightState: StateFlow<MapRouteHighlight?>,
    mapOverlays: ShellMapOverlays,
    isRecordingActive: Boolean,
    walkChrome: MapWalkChromeState,
    searchAreaPill: MapSearchAreaPillState?,
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
            routeHighlightState = routeHighlightState,
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
            placesChrome = shellPlacesChrome(navHostState, searchAreaPill),
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

private fun shellPlacesChrome(
    navHostState: ShellNavigationHostState,
    searchAreaPill: MapSearchAreaPillState?
): MapPlacesChromeState = MapPlacesChromeState(
    showAddPlaceButton = navHostState.showAddPlaceButton,
    onAddPlace = navHostState.onAddPlace,
    searchAreaPill = searchAreaPill
)

@Composable
private fun ShellTrailMap(
    trailMapRenderer: TrailMapRenderer,
    initialCameraState: MapCameraState,
    pendingCameraCommand: MapCameraCommand?,
    overlayState: MapOverlayState,
    viewportPadding: PaddingValues,
    interactions: MapInteractionEvents,
    onCameraCommandHandled: (Long) -> Unit,
    routeHighlightState: StateFlow<MapRouteHighlight?>,
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
        highlightState = routeHighlightState,
        liveRecordingOverlayState = mapOverlays.liveRecordingOverlayState,
        walkCoverageOverlayState = mapOverlays.walkCoverageOverlayState,
        routeEditOverlayState = mapOverlays.routeEditOverlayState,
        routeEditHandlerProvider = mapOverlays.routeEditHandlerProvider,
        trailDraftOverlayState = mapOverlays.trailDraftOverlayState,
        trailDraftHandlerProvider = mapOverlays.trailDraftHandlerProvider,
        trailPlacesOverlayState = mapOverlays.trailPlacesOverlayState,
        trailPlacesHandlerProvider = mapOverlays.trailPlacesHandlerProvider
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
