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
import androidx.compose.foundation.lazy.items
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
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.components.ElevationProfileChart
import app.openhikes.core.ui.components.HikeSegmentList
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val DetailHorizontalPadding = 16.dp
private val DetailSectionSpacing = 16.dp
private val BannerCornerRadius = 16.dp
private val TouchTargetMinSize = 48.dp
private val EmptyWalkProgressFlow: StateFlow<TrailWalkProgressState> = MutableStateFlow(TrailWalkProgressState())

/**
 * Stateful hike detail destination scoping [HikeDetailViewModel] to [hikeId].
 */
@Composable
fun HikeDetailRoute(
    hikeId: HikeId,
    onBack: () -> Unit,
    onOpenRouteStyle: (HikeId) -> Unit,
    onOpenWalkSummary: (HikeId, String) -> Unit,
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

    LaunchedEffect(uiState.walkSession.lastEndedWalk) {
        val ended = uiState.walkSession.lastEndedWalk ?: return@LaunchedEffect
        if (ended.hikeId == hikeId) {
            viewModel.onConsumeEndedWalk()
            onOpenWalkSummary(hikeId, ended.id)
        }
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
        modifier = modifier,
        walkProgressFlow = viewModel.walkProgress,
        activeSecondsProvider = viewModel::walkActiveSeconds,
        onDismissMessages = viewModel::onDismissMessages,
        onOfflineAction = viewModel::onOfflineAction,
        onWalkAction = { action -> viewModel.onWalkAction(action, onOpenWalkSummary) }
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
    walkProgressFlow: StateFlow<TrailWalkProgressState> = EmptyWalkProgressFlow,
    activeSecondsProvider: () -> Double = { 0.0 },
    onDismissMessages: () -> Unit = {},
    onOfflineAction: (HikeOfflineAction) -> Unit = {},
    onWalkAction: (HikeWalkAction) -> Unit = {}
) {
    val hike = uiState.hike
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        HikeDetailTopBar(
            title = hike?.displayTitle.orEmpty(),
            onBack = onBack,
            trailingAction = {
                if (hike != null) {
                    WalkToggleTopBarButton(
                        phase = if (uiState.isWalkingThisHike) uiState.walkSession.phase else null,
                        enabled = !uiState.isWalkingOtherHike && !uiState.isRecordingActive,
                        onClick = { onWalkAction(HikeWalkAction.ToggleWalk) }
                    )
                }
            }
        )
        DetailStatusBanners(uiState = uiState, onDismiss = onDismissMessages)
        if (hike == null && uiState.isLoaded) {
            MissingHikeState(modifier = Modifier.weight(1f))
        } else if (hike != null) {
            HikeDetailContentList(
                uiState = uiState,
                listState = listState,
                callbacks = DetailListCallbacks(
                    scrubDistanceProvider = scrubDistanceProvider,
                    walkProgressFlow = walkProgressFlow,
                    activeSecondsProvider = activeSecondsProvider,
                    onScrubDistance = onScrubDistance,
                    onZoomToRoute = onZoomToRoute,
                    onExportGpx = onExportGpx,
                    onOpenOfflineDialog = { onOfflineAction(HikeOfflineAction.OpenDialog) },
                    onOpenRouteStyle = onOpenRouteStyle,
                    onStartRename = {
                        onStartRename()
                        coroutineScope.launch { listState.animateScrollToItem(0) }
                    },
                    onTitleDraftChange = onTitleDraftChange,
                    onCommitRename = onCommitRename,
                    onCancelRename = onCancelRename,
                    onRequestDelete = onRequestDelete,
                    onWalkAction = onWalkAction
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }

    HikeDetailDialogs(
        uiState = uiState,
        onConfirmDelete = onConfirmDelete,
        onCancelDelete = onCancelDelete,
        onOfflineAction = onOfflineAction,
        onWalkAction = onWalkAction
    )
}

@Composable
private fun HikeDetailDialogs(
    uiState: HikeDetailUiState,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onOfflineAction: (HikeOfflineAction) -> Unit,
    onWalkAction: (HikeWalkAction) -> Unit
) {
    val hike = uiState.hike
    if (uiState.showDeleteDialog && hike != null) {
        DeleteHikeConfirmationDialog(
            hikeTitle = hike.displayTitle,
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete
        )
    }
    if (uiState.offlineCoverage.showDialog && uiState.offlineCoverage.canOpenDialog) {
        HikeOfflineCountryDialog(
            coverage = uiState.offlineCoverage,
            onDownloadCountry = { onOfflineAction(HikeOfflineAction.Download(it)) },
            onRetryCountry = { onOfflineAction(HikeOfflineAction.Retry(it)) },
            onCancelCountry = { onOfflineAction(HikeOfflineAction.Cancel(it)) },
            onDismiss = { onOfflineAction(HikeOfflineAction.DismissDialog) }
        )
    }
    if (uiState.showEndWalkDialog) {
        EndWalkConfirmationDialog(
            onConfirm = { onWalkAction(HikeWalkAction.ConfirmEndWalk) },
            onDismiss = { onWalkAction(HikeWalkAction.CancelEndWalk) }
        )
    }
    if (uiState.showEndWalkRefusedDialog) {
        EndWalkRefusedDialog(
            onDismiss = { onWalkAction(HikeWalkAction.DismissEndRefused) }
        )
    }
}

private data class DetailListCallbacks(
    val scrubDistanceProvider: () -> Double,
    val walkProgressFlow: StateFlow<TrailWalkProgressState>,
    val activeSecondsProvider: () -> Double,
    val onScrubDistance: (Double) -> Unit,
    val onZoomToRoute: () -> Unit,
    val onExportGpx: () -> Unit,
    val onOpenOfflineDialog: () -> Unit,
    val onOpenRouteStyle: () -> Unit,
    val onStartRename: () -> Unit,
    val onTitleDraftChange: (String) -> Unit,
    val onCommitRename: () -> Unit,
    val onCancelRename: () -> Unit,
    val onRequestDelete: () -> Unit,
    val onWalkAction: (HikeWalkAction) -> Unit
)

@Composable
private fun HikeDetailContentList(
    uiState: HikeDetailUiState,
    listState: LazyListState,
    callbacks: DetailListCallbacks,
    modifier: Modifier = Modifier
) {
    val hike = uiState.hike ?: return
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
                onTitleDraftChange = callbacks.onTitleDraftChange,
                onCommitRename = callbacks.onCommitRename,
                onCancelRename = callbacks.onCancelRename
            )
        }
        item(key = "segment_tabs") {
            HikeDetailSegmentRow(
                selectedTab = uiState.selectedTab,
                onSelectTab = { callbacks.onWalkAction(HikeWalkAction.SelectTab(it)) }
            )
        }
        addWalkStatusItem(uiState = uiState, callbacks = callbacks)
        when (uiState.selectedTab) {
            HikeDetailTab.DETAILS -> addDetailTabItems(uiState = uiState, callbacks = callbacks)
            HikeDetailTab.HISTORY -> addHistoryTabItems(
                uiState = uiState,
                onOpenWalkSummary = { walkId -> callbacks.onWalkAction(HikeWalkAction.OpenWalkSummary(walkId)) }
            )
        }
    }
}

private fun LazyListScope.addWalkStatusItem(uiState: HikeDetailUiState, callbacks: DetailListCallbacks) {
    val hike = uiState.hike ?: return
    val walkPhase = uiState.walkSession.phase
    if (uiState.isWalkingThisHike && walkPhase != null) {
        item(key = "active_walk") {
            HikeActiveWalkCard(
                phase = walkPhase,
                tintHex = hike.tintHex,
                distanceUnit = uiState.distanceUnit,
                walkProgressFlow = callbacks.walkProgressFlow,
                activeSecondsProvider = callbacks.activeSecondsProvider,
                onRequestEndWalk = { callbacks.onWalkAction(HikeWalkAction.RequestEndWalk) }
            )
        }
    } else if (uiState.isWalkingOtherHike) {
        item(key = "other_walk_notice") {
            WalkOtherHikeNotice(otherHikeTitle = uiState.walkSession.walkedHikeTitle)
        }
    }
}

private fun LazyListScope.addDetailTabItems(uiState: HikeDetailUiState, callbacks: DetailListCallbacks) {
    val hike = uiState.hike ?: return
    val profile = uiState.profile
    val statistics = uiState.statistics

    if (profile != null && profile.samples.size > 1) {
        item(key = "elevation") {
            ElevationProfileChart(
                profile = profile,
                distanceUnit = uiState.distanceUnit,
                tintHex = hike.tintHex,
                scrubDistanceProvider = callbacks.scrubDistanceProvider,
                onScrubDistance = callbacks.onScrubDistance
            )
        }
    }
    item(key = "actions") {
        HikeDetailActionsRow(
            onZoomToRoute = callbacks.onZoomToRoute,
            onExportGpx = callbacks.onExportGpx,
            offlineCoverage = uiState.offlineCoverage,
            onOpenOfflineDialog = callbacks.onOpenOfflineDialog
        )
    }
    item(key = "follow_trail") {
        HikeFollowTrailCard(
            enabled = hike.autoFollowEnabled,
            onToggle = { enabled -> callbacks.onWalkAction(HikeWalkAction.SetAutoFollow(enabled)) }
        )
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
        onOpenRouteStyle = callbacks.onOpenRouteStyle,
        onStartRename = callbacks.onStartRename,
        onRequestDelete = callbacks.onRequestDelete
    )
}

private fun LazyListScope.addHistoryTabItems(uiState: HikeDetailUiState, onOpenWalkSummary: (String) -> Unit) {
    val hike = uiState.hike ?: return
    if (uiState.walks.isEmpty()) {
        item(key = "walk_history_empty") {
            WalkHistoryEmptyCard()
        }
    } else {
        items(items = uiState.walks, key = { it.id }) { walk ->
            WalkHistoryRow(
                walk = walk,
                tintHex = hike.tintHex,
                onClick = { onOpenWalkSummary(walk.id) }
            )
        }
    }
}

private fun LazyListScope.addDetailSecondaryItems(
    uiState: HikeDetailUiState,
    onOpenRouteStyle: () -> Unit,
    onStartRename: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val hike = uiState.hike ?: return
    val statistics = uiState.statistics
    val distanceUnit = uiState.distanceUnit
    item(key = "about") {
        HikeAboutCard(hike = hike)
    }
    if (uiState.routeSegments.size > 1) {
        item(key = "segments") {
            HikeSegmentList(segments = uiState.routeSegments, distanceUnit = distanceUnit)
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
