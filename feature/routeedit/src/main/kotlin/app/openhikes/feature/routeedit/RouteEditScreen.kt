package app.openhikes.feature.routeedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val ScreenHorizontalPadding = 16.dp
private val ScreenSectionSpacing = 18.dp
private val EmptyRangeFlow: StateFlow<RouteTrimRangeState> = MutableStateFlow(RouteTrimRangeState())

/**
 * Stateful Edit Route destination scoped to [hikeId].
 */
@Composable
fun RouteEditRoute(
    hikeId: HikeId,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouteEditViewModel = hiltViewModel<RouteEditViewModel, RouteEditViewModel.Factory>(
        key = hikeId.value
    ) { factory ->
        factory.create(hikeId.value)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RouteEditScreen(
        uiState = uiState,
        rangeStateFlow = viewModel.rangeState,
        onBack = onBack,
        onSave = { viewModel.onSave(onDone) },
        onConfirmRevert = { viewModel.onConfirmRevert(onDone) },
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

/**
 * Stateless Edit Route screen used by runtime, previews, screenshot tests, and unit UI tests.
 */
@Composable
fun RouteEditScreen(
    uiState: RouteEditUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onConfirmRevert: () -> Unit,
    onAction: (RouteEditAction) -> Unit,
    modifier: Modifier = Modifier,
    rangeStateFlow: StateFlow<RouteTrimRangeState> = EmptyRangeFlow
) {
    Column(modifier = modifier.fillMaxSize()) {
        RouteEditTopBar(canSave = uiState.canSave, onBack = onBack, onSave = onSave)
        RouteEditBodyList(
            uiState = uiState,
            rangeStateFlow = rangeStateFlow,
            onAction = onAction,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        )
    }
    if (uiState.showRevertDialog) {
        RevertConfirmationDialog(
            onConfirm = onConfirmRevert,
            onDismiss = { onAction(RouteEditAction.DismissRevert) }
        )
    }
    uiState.refusal?.let { refusal ->
        RouteEditRefusalDialog(
            refusal = refusal,
            onDismiss = { onAction(RouteEditAction.DismissRefusal) }
        )
    }
}

@Composable
private fun RouteEditBodyList(
    uiState: RouteEditUiState,
    rangeStateFlow: StateFlow<RouteTrimRangeState>,
    onAction: (RouteEditAction) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.testTag(OpenHikesTestTags.ROUTE_EDIT_SCREEN),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = 4.dp,
            bottom = ScreenSectionSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(ScreenSectionSpacing)
    ) {
        item(key = "trimmer") {
            RouteTrimChart(
                profile = uiState.profile,
                tintHex = uiState.tintHex,
                distanceUnit = uiState.distanceUnit,
                isSelectingStretch = uiState.isSelectingStretch,
                rangeStateFlow = rangeStateFlow,
                onMoveHandle = { isStart, meters -> onAction(RouteEditAction.MoveHandle(isStart, meters)) },
                onAdjustHandle = { isStart, direction -> onAction(RouteEditAction.AdjustHandle(isStart, direction)) }
            )
        }
        if (uiState.profile != null) {
            item(key = "figures") {
                RouteTrimFiguresStrip(rangeStateFlow, uiState.distanceUnit, uiState.isSelectingStretch)
            }
            if (uiState.leadingCarriedOfferMeters != null || uiState.trailingCarriedOfferMeters != null) {
                item(key = "not_on_foot") {
                    NotOnFootOffersCard(
                        leadingMeters = uiState.leadingCarriedOfferMeters,
                        trailingMeters = uiState.trailingCarriedOfferMeters,
                        distanceUnit = uiState.distanceUnit,
                        onRemoveStart = { onAction(RouteEditAction.RemoveCarriedStart) },
                        onRemoveEnd = { onAction(RouteEditAction.RemoveCarriedEnd) }
                    )
                }
            }
            item(key = "steps") { RouteStepsCard(uiState = uiState, onAction = onAction) }
            if (uiState.hasOriginal) {
                item(key = "revert") {
                    RevertToOriginalCard(
                        enabled = !uiState.isSaving,
                        onRequestRevert = { onAction(RouteEditAction.RequestRevert) }
                    )
                }
            }
        }
        item(key = "footnote") {
            Text(
                text = stringResource(R.string.route_edit_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
