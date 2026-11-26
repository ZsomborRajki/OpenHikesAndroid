package app.openhikes.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.navigation.AddHikePlaceRoute
import app.openhikes.core.ui.navigation.EntryProviderInstaller
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.MapModesRoute
import app.openhikes.core.ui.navigation.OpenHikesNavigator
import app.openhikes.core.ui.navigation.OpenHikesRoute
import app.openhikes.core.ui.navigation.RecordingAddPlaceRoute
import app.openhikes.core.ui.navigation.RecordingRoute
import app.openhikes.core.ui.navigation.SettingsRoute
import app.openhikes.core.ui.navigation.TrailMakerRoute

@Immutable
internal data class ShellNavigationHostState(
    val sheetDetent: SheetDetent,
    val isMapModesOpen: Boolean,
    val isCenterPlacementActive: Boolean,
    val isPhotoViewerCoveringMap: Boolean,
    val showTrailMakerButton: Boolean,
    val showAddPlaceButton: Boolean,
    val onDetentChange: (SheetDetent) -> Unit,
    val onOpenHike: (HikeId) -> Unit,
    val onOpenRecording: () -> Unit,
    val onOpenTrailMaker: () -> Unit,
    val onAddPlace: () -> Unit,
    val onProceedToRecording: () -> Unit,
    val onOpenMapModes: () -> Unit,
    val navigationContent: @Composable () -> Unit
)

@Immutable
private data class ShellNavActions(
    val exitSearch: () -> Unit,
    val onSearchFocusChanged: (Boolean) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHike: (HikeId) -> Unit,
    val onOpenRecording: () -> Unit,
    val onOpenTrailMaker: () -> Unit,
    val onAddPlace: () -> Unit,
    val onProceedToRecording: () -> Unit,
    val onOpenMapModes: () -> Unit,
    val onHikeDeleted: (HikeId) -> Unit
)

@Stable
internal class ShellNavControllers(
    val sheetDetentState: MutableState<SheetDetent>,
    val detentBeforeSettingsState: MutableState<SheetDetent?>,
    val detentBeforeMapModesState: MutableState<SheetDetent?>,
    val detentBeforePhotoViewerState: MutableState<SheetDetent?>,
    val isPhotoViewerExpandedState: MutableState<Boolean>,
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

    var detentBeforeMapModes: SheetDetent?
        get() = detentBeforeMapModesState.value
        set(value) {
            detentBeforeMapModesState.value = value
        }

    var detentBeforePhotoViewer: SheetDetent?
        get() = detentBeforePhotoViewerState.value
        set(value) {
            detentBeforePhotoViewerState.value = value
        }

    var isPhotoViewerExpanded: Boolean
        get() = isPhotoViewerExpandedState.value
        set(value) {
            isPhotoViewerExpandedState.value = value
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
    val detentBeforeMapModesState = rememberSaveable { mutableStateOf<SheetDetent?>(null) }
    val detentBeforePhotoViewerState = rememberSaveable { mutableStateOf<SheetDetent?>(null) }
    val isPhotoViewerExpandedState = rememberSaveable { mutableStateOf(true) }
    val isSearchActiveState = rememberSaveable { mutableStateOf(false) }
    val backStack = rememberNavBackStack(MapHomeRoute)
    val navigator = remember(backStack) {
        OpenHikesNavigator(
            backStack = backStack,
            onCollapseSheet = { sheetDetentState.value = SheetDetent.PEEK },
            onRevealMap = {
                isPhotoViewerExpandedState.value = false
                sheetDetentState.value = SheetDetent.HALF
            },
            onExpandFullHeight = {
                isPhotoViewerExpandedState.value = true
                sheetDetentState.value = SheetDetent.EXPANDED
            }
        )
    }
    return remember(
        sheetDetentState,
        detentBeforeSettingsState,
        detentBeforeMapModesState,
        detentBeforePhotoViewerState,
        isPhotoViewerExpandedState,
        isSearchActiveState,
        backStack,
        navigator
    ) {
        ShellNavControllers(
            sheetDetentState = sheetDetentState,
            detentBeforeSettingsState = detentBeforeSettingsState,
            detentBeforeMapModesState = detentBeforeMapModesState,
            detentBeforePhotoViewerState = detentBeforePhotoViewerState,
            isPhotoViewerExpandedState = isPhotoViewerExpandedState,
            isSearchActiveState = isSearchActiveState,
            backStack = backStack,
            navigator = navigator
        )
    }
}

@Composable
internal fun rememberShellNavigationHost(
    launchConfig: LaunchConfig,
    shellLayout: ShellLayout,
    entryProviderInstallers: Set<EntryProviderInstaller>,
    showAttributionDialog: Boolean,
    searchQueryState: State<String>,
    isRecordingActive: Boolean,
    activeRecordingHikeId: HikeId?,
    isWalkActive: Boolean,
    openRecordingRequestCount: Int,
    openWalkHikeRequest: Pair<Int, HikeId>?,
    defaultAddPlaceCoordinateProvider: (HikeId) -> GeoCoordinate,
    latestRecordingCoordinateProvider: () -> GeoCoordinate?,
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
        activeRecordingHikeId = activeRecordingHikeId,
        isWalkActive = isWalkActive,
        defaultAddPlaceCoordinateProvider = defaultAddPlaceCoordinateProvider,
        latestRecordingCoordinateProvider = latestRecordingCoordinateProvider,
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
    val topRoute = controllers.backStack.lastOrNull()
    val showAddPlaceButton = topRoute is HikeDetailRoute ||
        (topRoute == RecordingRoute && activeRecordingHikeId != null)
    return ShellNavigationHostState(
        sheetDetent = controllers.sheetDetent,
        isMapModesOpen = topRoute == MapModesRoute,
        isCenterPlacementActive = topRoute is AddHikePlaceRoute,
        isPhotoViewerCoveringMap = topRoute is HikePhotoViewerRoute && controllers.isPhotoViewerExpanded,
        showTrailMakerButton = controllers.backStack.size <= 1,
        showAddPlaceButton = showAddPlaceButton,
        onDetentChange = { next ->
            controllers.sheetDetent = next
            if (next == SheetDetent.PEEK && controllers.isSearchActive) actions.exitSearch()
        },
        onOpenHike = actions.onOpenHike,
        onOpenRecording = actions.onOpenRecording,
        onOpenTrailMaker = actions.onOpenTrailMaker,
        onAddPlace = actions.onAddPlace,
        onProceedToRecording = actions.onProceedToRecording,
        onOpenMapModes = actions.onOpenMapModes,
        navigationContent = navigationContent
    )
}

@Composable
private fun rememberShellNavActions(
    controllers: ShellNavControllers,
    searchQuery: String,
    isRecordingActive: Boolean,
    activeRecordingHikeId: HikeId?,
    isWalkActive: Boolean,
    defaultAddPlaceCoordinateProvider: (HikeId) -> GeoCoordinate,
    latestRecordingCoordinateProvider: () -> GeoCoordinate?,
    onRequestAutoStartRecording: () -> Unit,
    onRequestEndWalkForRecording: () -> Unit,
    onClearSearch: () -> Unit
): ShellNavActions {
    val focusManager = LocalFocusManager.current
    val currentSearchQuery by rememberUpdatedState(searchQuery)
    val currentIsRecordingActive by rememberUpdatedState(isRecordingActive)
    val currentActiveRecordingHikeId by rememberUpdatedState(activeRecordingHikeId)
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
        onSearchFocusChanged = { focused -> controllers.updateSearchFocus(focused, currentSearchQuery) },
        onOpenSettings = { controllers.openSettings(exitSearch) },
        onOpenHike = { hikeId ->
            focusManager.clearFocus(force = true)
            controllers.navigateHalfSheetIfPeek(HikeDetailRoute(hikeId))
        },
        onOpenRecording = {
            if (!currentIsRecordingActive &&
                currentIsWalkActive
            ) {
                onRequestEndWalkForRecording()
            } else {
                proceedToRecording()
            }
        },
        onOpenTrailMaker = {
            if (controllers.isSearchActive) exitSearch()
            focusManager.clearFocus(force = true)
            controllers.navigateHalfSheetIfPeek(TrailMakerRoute)
        },
        onAddPlace = {
            focusManager.clearFocus(force = true)
            controllers.openAddPlace(
                activeRecordingHikeId = currentActiveRecordingHikeId,
                defaultAddPlaceCoordinateProvider = defaultAddPlaceCoordinateProvider,
                latestRecordingCoordinateProvider = latestRecordingCoordinateProvider
            )
        },
        onProceedToRecording = proceedToRecording,
        onOpenMapModes = {
            controllers.toggleMapModesRoute(
                exitSearch = exitSearch,
                clearFocus = { focusManager.clearFocus(force = true) }
            )
        },
        onHikeDeleted = { hikeId -> controllers.navigator.removeRoutesForHike(hikeId) }
    )
}

private fun ShellNavControllers.updateSearchFocus(focused: Boolean, currentSearchQuery: String) {
    if (focused) {
        isSearchActive = true
        sheetDetent = SheetDetent.EXPANDED
    } else if (currentSearchQuery.isBlank()) {
        isSearchActive = false
    }
}

private fun ShellNavControllers.openSettings(exitSearch: () -> Unit) {
    if (isSearchActive) exitSearch()
    if (detentBeforeSettings == null) detentBeforeSettings = sheetDetent
    sheetDetent = SheetDetent.EXPANDED
    navigator.navigate(SettingsRoute)
}

private fun ShellNavControllers.toggleMapModesRoute(exitSearch: () -> Unit, clearFocus: () -> Unit) {
    if (backStack.lastOrNull() == MapModesRoute) {
        navigator.pop()
    } else {
        if (isSearchActive) exitSearch()
        clearFocus()
        if (detentBeforeMapModes == null) {
            detentBeforeMapModes = sheetDetent
        }
        sheetDetent = SheetDetent.HALF
        navigator.removeRoutesMatching { it == MapModesRoute }
        navigator.navigate(MapModesRoute)
    }
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

private fun ShellNavControllers.openAddPlace(
    activeRecordingHikeId: HikeId?,
    defaultAddPlaceCoordinateProvider: (HikeId) -> GeoCoordinate,
    latestRecordingCoordinateProvider: () -> GeoCoordinate?
) {
    when (val top = backStack.lastOrNull()) {
        is HikeDetailRoute -> {
            val coord = defaultAddPlaceCoordinateProvider(top.hikeId)
            navigateHalfSheetIfPeek(AddHikePlaceRoute(top.hikeId, coord.latitude, coord.longitude))
        }
        RecordingRoute -> if (activeRecordingHikeId != null) {
            val coord = latestRecordingCoordinateProvider()
            navigateHalfSheetIfPeek(
                RecordingAddPlaceRoute(activeRecordingHikeId, coord?.latitude, coord?.longitude)
            )
        }
        else -> Unit
    }
}

private fun ShellNavControllers.navigateHalfSheetIfPeek(route: OpenHikesRoute) {
    if (sheetDetent == SheetDetent.PEEK) sheetDetent = SheetDetent.HALF
    if (backStack.lastOrNull() != route) navigator.navigate(route)
}

@Composable
private fun KeepPlacementSheetHalfDetent(controllers: ShellNavControllers) {
    val topRoute = controllers.backStack.lastOrNull()
    LaunchedEffect(topRoute) {
        if (topRoute is AddHikePlaceRoute && controllers.sheetDetent != SheetDetent.HALF) {
            controllers.sheetDetent = SheetDetent.HALF
        }
    }
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
    KeepPlacementSheetHalfDetent(controllers)
    HandleExternalNavigationRequests(
        controllers = controllers,
        openRecordingRequestCount = openRecordingRequestCount,
        openWalkHikeRequest = openWalkHikeRequest,
        onExitSearch = onExitSearch
    )
    SyncShellBackStackEffect(controllers = controllers, onSyncBackStack = onSyncBackStack)
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
