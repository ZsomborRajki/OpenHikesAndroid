package app.openhikes.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.navigation.HikeDetailRoute
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import app.openhikes.core.ui.navigation.MapHomeRoute
import app.openhikes.core.ui.navigation.MapModesRoute
import app.openhikes.core.ui.navigation.OfflineMapsRoute
import app.openhikes.core.ui.navigation.RecordingRoute

@Composable
internal fun HandleExternalNavigationRequests(
    controllers: ShellNavControllers,
    openRecordingRequestCount: Int,
    openWalkHikeRequest: Pair<Int, HikeId>?,
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
}

@Composable
internal fun SyncShellBackStackEffect(controllers: ShellNavControllers, onSyncBackStack: (List<Any>) -> Unit) {
    LaunchedEffect(controllers.backStack.toList()) {
        val currentStack = controllers.backStack.toList()
        onSyncBackStack(currentStack)
        val top = currentStack.lastOrNull()
        reconcileMapModesDetent(controllers, currentStack, top)
        reconcilePhotoViewerDetent(controllers, top)
        if (top == MapHomeRoute) {
            controllers.detentBeforeSettings?.let { previous ->
                controllers.sheetDetent = previous
                controllers.detentBeforeSettings = null
            }
        }
    }
}

private fun reconcileMapModesDetent(controllers: ShellNavControllers, currentStack: List<Any>, top: Any?) {
    if (MapModesRoute in currentStack) return
    val previousDetent = controllers.detentBeforeMapModes ?: return
    if (top !is OfflineMapsRoute) {
        controllers.sheetDetent = previousDetent
    }
    controllers.detentBeforeMapModes = null
}

private fun reconcilePhotoViewerDetent(controllers: ShellNavControllers, top: Any?) {
    if (top is HikePhotoViewerRoute) {
        if (controllers.detentBeforePhotoViewer == null) {
            controllers.detentBeforePhotoViewer = controllers.sheetDetent
            controllers.sheetDetent = SheetDetent.EXPANDED
            controllers.isPhotoViewerExpanded = true
        }
    } else {
        val beforePhoto = controllers.detentBeforePhotoViewer
        if (beforePhoto != null) {
            if (controllers.sheetDetent == SheetDetent.EXPANDED) {
                controllers.sheetDetent = beforePhoto
            }
            controllers.detentBeforePhotoViewer = null
        }
        controllers.isPhotoViewerExpanded = true
    }
}
