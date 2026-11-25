package app.openhikes.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val SectionSpacing = 14.dp
private val TopListPadding = 4.dp
private val BottomListPadding = 16.dp

/**
 * Stateful Navigation 3 route composable for Map Modes.
 */
@Composable
fun MapModesRoute(
    onClose: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapModesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    MapModesScreen(
        uiState = uiState,
        previews = previews,
        onClose = onClose,
        onSelectStyle = viewModel::onSelectStyle,
        onSetShowsHikingRoutes = viewModel::onSetShowsHikingRoutes,
        onOpenOfflineMaps = onOpenOfflineMaps,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        onDismissSaveError = viewModel::onDismissSaveError,
        modifier = modifier
    )
}

/**
 * Stateless Material 3 Expressive Map Modes screen used across runtime, previews, and tests.
 */
@Composable
fun MapModesScreen(
    uiState: MapModesUiState,
    onClose: () -> Unit,
    onSelectStyle: (String) -> Unit,
    onSetShowsHikingRoutes: (Boolean) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    previews: Map<String, ImageBitmap> = emptyMap(),
    onDismissSaveError: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        MapModesTopBar(onClose = onClose)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag(OpenHikesTestTags.MAP_MODES_SCREEN),
            contentPadding = PaddingValues(top = TopListPadding, bottom = BottomListPadding),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            if (uiState.saveError) {
                item(key = "save-error") {
                    MapModesSaveErrorBanner(onDismiss = onDismissSaveError)
                }
            }
            item(key = "map-styles") {
                MapStylePickerSection(
                    styles = uiState.styles,
                    selectedStyleId = uiState.selectedStyle.id,
                    selectedIndex = uiState.selectedStyleIndex,
                    previews = previews,
                    onSelectStyle = onSelectStyle
                )
            }
            item(key = "hiking-routes") {
                HikingRoutesSection(
                    showsHikingRoutes = uiState.showsHikingRoutes,
                    onSetShowsHikingRoutes = onSetShowsHikingRoutes
                )
            }
            item(key = "offline-maps") {
                MapModesOfflineMapsRow(
                    installedCountryNames = uiState.installedCountryNames,
                    installedByteCount = uiState.installedByteCount,
                    onOpenOfflineMaps = onOpenOfflineMaps
                )
            }
            item(key = "footer") {
                MapModesFooter(
                    hasMissingApiKeyStyles = uiState.hasMissingApiKeyStyles,
                    attribution = uiState.attribution,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}
