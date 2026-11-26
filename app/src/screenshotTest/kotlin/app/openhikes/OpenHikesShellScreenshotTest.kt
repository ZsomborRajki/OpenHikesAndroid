package app.openhikes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapInteractionEvents
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapRouteOverlay
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.TrailMapRenderer
import app.openhikes.feature.hikes.HikeLibraryScreen
import app.openhikes.feature.hikes.HikeLibraryUiState
import app.openhikes.shell.MapChromeUiState
import app.openhikes.shell.OpenHikesShellContent
import com.android.tools.screenshot.PreviewTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@PreviewTest
@Preview(name = "Shell Compact Portrait", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun ShellCompactPortraitPreview() {
    ShellPreviewHost(widthDp = 411f, heightDp = 891f, darkTheme = false)
}

@PreviewTest
@Preview(name = "Shell Landscape Phone", showBackground = true, widthDp = 800, heightDp = 400)
@Composable
fun ShellLandscapePhonePreview() {
    ShellPreviewHost(widthDp = 800f, heightDp = 400f, darkTheme = false)
}

@PreviewTest
@Preview(name = "Shell Expanded Tablet Dark", showBackground = true, widthDp = 1024, heightDp = 768)
@Composable
fun ShellExpandedTabletDarkPreview() {
    ShellPreviewHost(widthDp = 1024f, heightDp = 768f, darkTheme = true)
}

@Composable
private fun ShellPreviewHost(widthDp: Float, heightDp: Float, darkTheme: Boolean) {
    val previewRenderer = remember {
        object : TrailMapRenderer {
            @Composable
            override fun Render(
                cameraState: MapCameraState,
                cameraCommand: MapCameraCommand?,
                overlayState: MapOverlayState,
                viewportPadding: PaddingValues,
                interactions: MapInteractionEvents,
                onCameraCommandHandled: (Long) -> Unit,
                highlightState: StateFlow<MapRouteHighlight?>,
                liveRecordingOverlayState: StateFlow<MapRouteOverlay?>,
                modifier: Modifier,
                walkCoverageOverlayState: StateFlow<MapWalkCoverageOverlay?>,
                routeEditOverlayState: StateFlow<app.openhikes.core.map.MapRouteEditOverlay?>,
                routeEditHandlerProvider: () -> app.openhikes.core.map.RouteEditMapInteractionHandler?,
                trailDraftOverlayState: StateFlow<app.openhikes.core.map.MapTrailDraftOverlay?>,
                trailDraftHandlerProvider: () -> app.openhikes.core.map.TrailDraftMapInteractionHandler?,
                trailPlacesOverlayState: StateFlow<app.openhikes.core.map.MapTrailPlacesOverlay?>,
                trailPlacesHandlerProvider: () -> app.openhikes.core.map.TrailPlacesMapInteractionHandler?
            ) {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
    val searchQueryState = remember { mutableStateOf("") }
    val viewModelStoreOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    val navigationEventOwner = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    val noOpUriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) = Unit
        }
    }

    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides viewModelStoreOwner,
            LocalNavigationEventDispatcherOwner provides navigationEventOwner,
            LocalUriHandler provides noOpUriHandler
        ) {
            OpenHikesShellContent(
                trailMapRenderer = previewRenderer,
                entryProviderInstallers = emptySet(),
                launchConfig = LaunchConfig(
                    isUiTesting = true,
                    startsWithExpandedSheet = true,
                    simulatesOffline = true
                ),
                windowSizeClass = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(widthDp, heightDp),
                chromeUiState = MapChromeUiState(),
                searchQueryState = searchQueryState,
                initialCameraState = MapCameraState(),
                pendingCameraCommand = null,
                routeHighlightState = MutableStateFlow(null),
                onSyncBackStack = {},
                onSearchQueryChange = {},
                onClearSearch = {},
                onZoomIn = {},
                onZoomOut = {},
                onResetNorth = {},
                onCameraChanged = {},
                onCameraCommandHandled = {},
                onOpenAttributionDialog = {},
                onDismissAttributionDialog = {},
                onOpenUrl = {},
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
}
