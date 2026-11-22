package app.openhikes.feature.hikes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.components.ElevationProfileChart
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.launch

private val DetailHorizontalPadding = 16.dp
private val DetailSectionSpacing = 16.dp
private val BannerCornerRadius = 16.dp
private val TouchTargetMinSize = 48.dp

/**
 * Stateful hike detail destination scoping [HikeDetailViewModel] to [hikeId].
 */
@Composable
fun HikeDetailRoute(
    hikeId: HikeId,
    onBack: () -> Unit,
    onOpenRouteStyle: (HikeId) -> Unit,
    onHikeDeleted: (HikeId) -> Unit,
    onCollapseSheet: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HikeDetailViewModel = hiltViewModel<HikeDetailViewModel, HikeDetailViewModel.Factory>(
        key = hikeId.value
    ) { factory ->
        factory.create(hikeId.value)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrubDistanceState = viewModel.scrubDistanceMeters.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        viewModel.onExportDestinationPicked(uri?.toString())
    }

    LaunchedEffect(uiState.pendingExport) {
        val pending = uiState.pendingExport ?: return@LaunchedEffect
        exportLauncher.launch(pending.fileName)
    }

    HikeDetailScreen(
        uiState = uiState,
        scrubDistanceProvider = { scrubDistanceState.value },
        onScrubDistance = viewModel::onScrubDistance,
        onBack = onBack,
        onZoomToRoute = {
            onCollapseSheet()
            viewModel.onZoomToRoute()
        },
        onExportGpx = viewModel::onRequestExportGpx,
        onOpenRouteStyle = { onOpenRouteStyle(hikeId) },
        onStartRename = viewModel::onStartRename,
        onTitleDraftChange = viewModel::onTitleDraftChange,
        onCommitRename = viewModel::onCommitRename,
        onCancelRename = viewModel::onCancelRename,
        onRequestDelete = viewModel::onRequestDelete,
        onConfirmDelete = { viewModel.onConfirmDelete(onHikeDeleted) },
        onCancelDelete = viewModel::onCancelDelete,
        onDismissMessages = viewModel::onDismissMessages,
        modifier = modifier
    )
}

/**
 * Stateless hike detail screen used by runtime, previews, screenshot tests, and unit UI tests.
 */
@Composable
fun HikeDetailScreen(
    uiState: HikeDetailUiState,
    scrubDistanceProvider: () -> Double,
    onScrubDistance: (Double) -> Unit,
    onBack: () -> Unit,
    onZoomToRoute: () -> Unit,
    onExportGpx: () -> Unit,
    onOpenRouteStyle: () -> Unit,
    onStartRename: () -> Unit,
    onTitleDraftChange: (String) -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissMessages: () -> Unit = {}
) {
    val hike = uiState.hike
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        HikeDetailTopBar(
            title = hike?.displayTitle.orEmpty(),
            onBack = onBack
        )
        DetailStatusBanners(uiState = uiState, onDismiss = onDismissMessages)
        if (hike == null && uiState.isLoaded) {
            MissingHikeState(modifier = Modifier.weight(1f))
        } else if (hike != null) {
            HikeDetailContentList(
                uiState = uiState,
                listState = listState,
                scrubDistanceProvider = scrubDistanceProvider,
                onScrubDistance = onScrubDistance,
                onZoomToRoute = onZoomToRoute,
                onExportGpx = onExportGpx,
                onOpenRouteStyle = onOpenRouteStyle,
                onStartRename = {
                    onStartRename()
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                },
                onTitleDraftChange = onTitleDraftChange,
                onCommitRename = onCommitRename,
                onCancelRename = onCancelRename,
                onRequestDelete = onRequestDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (uiState.showDeleteDialog && hike != null) {
        DeleteHikeConfirmationDialog(
            hikeTitle = hike.displayTitle,
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete
        )
    }
}

@Composable
private fun HikeDetailContentList(
    uiState: HikeDetailUiState,
    listState: LazyListState,
    scrubDistanceProvider: () -> Double,
    onScrubDistance: (Double) -> Unit,
    onZoomToRoute: () -> Unit,
    onExportGpx: () -> Unit,
    onOpenRouteStyle: () -> Unit,
    onStartRename: () -> Unit,
    onTitleDraftChange: (String) -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hike = uiState.hike ?: return
    val profile = uiState.profile
    val statistics = uiState.statistics

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN),
        contentPadding = PaddingValues(
            start = DetailHorizontalPadding,
            end = DetailHorizontalPadding,
            bottom = DetailSectionSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(DetailSectionSpacing)
    ) {
        item(key = "header") {
            HikeDetailHeader(
                hike = hike,
                isRenaming = uiState.isRenaming,
                titleDraft = uiState.titleDraft,
                onTitleDraftChange = onTitleDraftChange,
                onCommitRename = onCommitRename,
                onCancelRename = onCancelRename
            )
        }
        if (profile != null && profile.samples.size > 1) {
            item(key = "elevation") {
                ElevationProfileChart(
                    profile = profile,
                    distanceUnit = uiState.distanceUnit,
                    tintHex = hike.tintHex,
                    scrubDistanceProvider = scrubDistanceProvider,
                    onScrubDistance = onScrubDistance
                )
            }
        }
        item(key = "actions") {
            HikeDetailActionsRow(onZoomToRoute = onZoomToRoute, onExportGpx = onExportGpx)
        }
        item(key = "headline_stats") {
            HikeHeadlineStatsStrip(
                hike = hike,
                statistics = statistics,
                distanceUnit = uiState.distanceUnit
            )
        }
        addDetailSecondaryItems(
            uiState = uiState,
            onOpenRouteStyle = onOpenRouteStyle,
            onStartRename = onStartRename,
            onRequestDelete = onRequestDelete
        )
    }
}

private fun LazyListScope.addDetailSecondaryItems(
    uiState: HikeDetailUiState,
    onOpenRouteStyle: () -> Unit,
    onStartRename: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val hike = uiState.hike ?: return
    val profile = uiState.profile
    val statistics = uiState.statistics
    val distanceUnit = uiState.distanceUnit
    item(key = "about") {
        HikeAboutCard(hike = hike)
    }
    if (profile != null && profile.segments.size > 1) {
        item(key = "segments") {
            HikeSegmentsCard(profile = profile, distanceUnit = distanceUnit)
        }
    }
    if (statistics != null) {
        item(key = "statistics") {
            HikeStatisticsCard(statistics = statistics, distanceUnit = distanceUnit)
        }
    }
    item(key = "on_map") {
        HikeOnMapCard(hike = hike, onOpenRouteStyle = onOpenRouteStyle)
    }
    item(key = "closing_actions") {
        HikeClosingActionsCard(
            onStartRename = onStartRename,
            onRequestDelete = onRequestDelete
        )
    }
}

@Composable
private fun DetailStatusBanners(uiState: HikeDetailUiState, onDismiss: () -> Unit) {
    val error = uiState.actionError
    if (error != null) {
        val message = when (error) {
            HikeDetailError.RENAME_FAILED -> stringResource(R.string.hikes_save_failed_error)
            HikeDetailError.DELETE_FAILED -> stringResource(R.string.hikes_delete_failed_error)
            HikeDetailError.EXPORT_FAILED -> stringResource(R.string.hikes_export_failed_error)
        }
        StatusBannerSurface(
            message = message,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onDismiss = onDismiss,
            modifier = Modifier.testTag(OpenHikesTestTags.HIKE_ACTION_ERROR)
        )
    } else if (uiState.exportSuccess) {
        StatusBannerSurface(
            message = stringResource(R.string.hikes_export_success_message),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun StatusBannerSurface(
    message: String,
    containerColor: Color,
    contentColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(BannerCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DetailHorizontalPadding, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(
                    text = stringResource(R.string.hikes_error_dismiss),
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun MissingHikeState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(DetailHorizontalPadding)
    ) {
        Text(
            text = stringResource(R.string.hike_detail_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
