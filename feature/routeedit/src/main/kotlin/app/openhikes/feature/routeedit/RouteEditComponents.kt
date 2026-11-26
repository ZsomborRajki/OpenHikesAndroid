package app.openhikes.feature.routeedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.StateFlow

private val CardCornerRadius = 24.dp
private val RowMinHeight = 52.dp
private val TouchTargetMinSize = 48.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp
private const val LARGE_FONT_SCALE_THRESHOLD = 1.5f
private const val DISABLED_ALPHA = 0.38f

@Composable
internal fun RouteEditTopBar(canSave: Boolean, onBack: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.ROUTE_EDIT_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.route_edit_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.route_edit_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier
                .defaultMinSize(minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.ROUTE_TRIM_SAVE)
        ) {
            Text(text = stringResource(R.string.route_edit_save))
        }
    }
}

@Composable
internal fun RouteTrimFiguresStrip(
    rangeStateFlow: StateFlow<RouteTrimRangeState>,
    distanceUnit: DistanceUnit,
    isSelectingStretch: Boolean,
    modifier: Modifier = Modifier
) {
    val range by rangeStateFlow.collectAsStateWithLifecycle()
    val isLargeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE_THRESHOLD
    val items = buildList {
        val distanceLabel = if (isSelectingStretch) {
            stringResource(R.string.route_edit_stat_stretch)
        } else {
            stringResource(R.string.route_edit_stat_distance)
        }
        add(distanceLabel to HikeMetricsFormatter.formatDistance(range.displayedDistanceMeters, distanceUnit))
        range.keptDurationSeconds?.let { seconds ->
            add(stringResource(R.string.route_edit_stat_duration) to HikeMetricsFormatter.formatDuration(seconds))
        }
        if (!isSelectingStretch) {
            add(
                stringResource(R.string.route_edit_stat_removed) to
                    HikeMetricsFormatter.formatDistance(range.removedMeters, distanceUnit)
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.ROUTE_TRIM_FIGURES)
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier.padding(RowHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { (label, value) -> StatFigureColumn(label = label, value = value) }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(RowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { (label, value) ->
                    StatFigureColumn(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatFigureColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun NotOnFootOffersCard(
    leadingMeters: Double?,
    trailingMeters: Double?,
    distanceUnit: DistanceUnit,
    onRemoveStart: () -> Unit,
    onRemoveEnd: () -> Unit
) {
    if (leadingMeters == null && trailingMeters == null) return
    RouteEditSectionCard(title = stringResource(R.string.route_edit_not_on_foot_section)) {
        if (leadingMeters != null) {
            val formatted = HikeMetricsFormatter.formatDistance(leadingMeters, distanceUnit)
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_remove_start, formatted),
                iconRes = OpenHikesIcons.DirectionsCar,
                enabled = true,
                onClick = onRemoveStart,
                testTag = OpenHikesTestTags.ROUTE_TRIM_REMOVE_START
            )
        }
        if (leadingMeters != null && trailingMeters != null) {
            RouteEditCardDivider()
        }
        if (trailingMeters != null) {
            val formatted = HikeMetricsFormatter.formatDistance(trailingMeters, distanceUnit)
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_remove_end, formatted),
                iconRes = OpenHikesIcons.DirectionsCar,
                enabled = true,
                onClick = onRemoveEnd,
                testTag = OpenHikesTestTags.ROUTE_TRIM_REMOVE_END
            )
        }
    }
}

@Composable
internal fun RouteStepsCard(uiState: RouteEditUiState, onAction: (RouteEditAction) -> Unit) {
    if (uiState.isSelectingStretch) {
        RouteStretchVerbsCard(uiState = uiState, onAction = onAction)
    } else {
        RouteEditSectionCard(title = null) {
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_change_stretch),
                iconRes = OpenHikesIcons.Route,
                enabled = uiState.canMakeSteps,
                onClick = { onAction(RouteEditAction.BeginStretch) },
                testTag = OpenHikesTestTags.ROUTE_STRETCH_BEGIN
            )
            RouteEditCardDivider()
            WholeRouteMenuRow(uiState = uiState, onAction = onAction)
            if (uiState.hasSteps) {
                RouteEditCardDivider()
                RouteEditActionRow(
                    label = stringResource(R.string.route_edit_undo),
                    iconRes = OpenHikesIcons.Undo,
                    enabled = uiState.canUndo,
                    onClick = { onAction(RouteEditAction.UndoStep) },
                    testTag = OpenHikesTestTags.ROUTE_STEP_UNDO
                )
            }
        }
    }
}

@Composable
private fun WholeRouteMenuRow(uiState: RouteEditUiState, onAction: (RouteEditAction) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        RouteEditActionRow(
            label = stringResource(R.string.route_edit_whole_route),
            iconRes = OpenHikesIcons.MoreHoriz,
            enabled = uiState.canMakeSteps,
            isBusy = uiState.isRouting,
            onClick = { menuExpanded = true },
            testTag = OpenHikesTestTags.ROUTE_WHOLE_MENU
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            WholeRouteMenuItems(
                uiState = uiState,
                onSelect = { action ->
                    menuExpanded = false
                    onAction(action)
                }
            )
        }
    }
}

@Composable
private fun WholeRouteMenuItems(uiState: RouteEditUiState, onSelect: (RouteEditAction) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.route_edit_reverse)) },
        leadingIcon = { Icon(painterResource(OpenHikesIcons.SwapHoriz), contentDescription = null) },
        onClick = { onSelect(RouteEditAction.ReverseRoute) },
        modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_WHOLE_REVERSE)
    )
    if (uiState.isLoop) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.route_edit_start_loop_here))
                    if (!uiState.canStartLoopAtCut) {
                        Text(
                            text = stringResource(R.string.route_edit_start_loop_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            leadingIcon = { Icon(painterResource(OpenHikesIcons.Flag), contentDescription = null) },
            enabled = uiState.canStartLoopAtCut,
            onClick = { onSelect(RouteEditAction.StartLoopHere) },
            modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_WHOLE_START_LOOP_HERE)
        )
    } else if (!uiState.keptEndsMeet) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.route_edit_out_and_back)) },
            leadingIcon = { Icon(painterResource(OpenHikesIcons.UTurnRight), contentDescription = null) },
            onClick = { onSelect(RouteEditAction.OutAndBack) },
            modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_WHOLE_OUT_AND_BACK)
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.route_edit_close_loop)) },
            leadingIcon = { Icon(painterResource(OpenHikesIcons.Loop), contentDescription = null) },
            onClick = { onSelect(RouteEditAction.CloseLoop) },
            modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_WHOLE_CLOSE_LOOP)
        )
    }
}

@Composable
private fun RouteStretchVerbsCard(uiState: RouteEditUiState, onAction: (RouteEditAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteEditSectionCard(title = stringResource(R.string.route_edit_stretch_section)) {
            if (uiState.canFollowTrails) {
                RouteEditActionRow(
                    label = stringResource(R.string.route_edit_stretch_follow),
                    iconRes = OpenHikesIcons.Directions,
                    enabled = uiState.canMakeSteps,
                    isBusy = uiState.isRouting,
                    onClick = { onAction(RouteEditAction.FollowTrails) },
                    testTag = OpenHikesTestTags.ROUTE_STRETCH_FOLLOW
                )
                RouteEditCardDivider()
            }
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_stretch_straighten),
                iconRes = OpenHikesIcons.Straighten,
                enabled = uiState.canMakeSteps,
                onClick = { onAction(RouteEditAction.StraightenStretch) },
                testTag = OpenHikesTestTags.ROUTE_STRETCH_STRAIGHTEN
            )
            RouteEditCardDivider()
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_stretch_remove),
                iconRes = OpenHikesIcons.ContentCut,
                enabled = uiState.canMakeSteps && !uiState.picksWholeLine,
                onClick = { onAction(RouteEditAction.RemoveStretch) },
                testTag = OpenHikesTestTags.ROUTE_STRETCH_REMOVE
            )
            RouteEditCardDivider()
            RouteEditActionRow(
                label = stringResource(R.string.route_edit_stretch_cancel),
                iconRes = OpenHikesIcons.Close,
                enabled = !uiState.isSaving,
                onClick = { onAction(RouteEditAction.EndStretch) },
                testTag = OpenHikesTestTags.ROUTE_STRETCH_CANCEL
            )
        }
        Text(
            text = stringResource(R.string.route_edit_stretch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
internal fun RevertToOriginalCard(enabled: Boolean, onRequestRevert: () -> Unit) {
    RouteEditSectionCard(title = null) {
        RouteEditActionRow(
            label = stringResource(R.string.route_edit_revert),
            iconRes = OpenHikesIcons.Undo,
            enabled = enabled,
            tint = MaterialTheme.colorScheme.error,
            onClick = onRequestRevert,
            testTag = OpenHikesTestTags.ROUTE_TRIM_REVERT
        )
    }
}

@Composable
internal fun RevertConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_edit_revert_dialog_title)) },
        text = { Text(stringResource(R.string.route_edit_revert_dialog_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.ROUTE_TRIM_REVERT_CONFIRM)
            ) {
                Text(stringResource(R.string.route_edit_revert_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(stringResource(R.string.route_edit_cancel))
            }
        },
        modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_TRIM_REVERT_DIALOG)
    )
}

@Composable
internal fun RouteEditRefusalDialog(refusal: RouteEditRefusal, onDismiss: () -> Unit) {
    val (titleRes, messageRes) = when (refusal) {
        RouteEditRefusal.LINE_CHANGED ->
            R.string.route_edit_refusal_not_saved_title to R.string.route_edit_refusal_line_changed_message
        RouteEditRefusal.NOT_SAVED ->
            R.string.route_edit_refusal_not_saved_title to R.string.route_edit_refusal_not_saved_message
        RouteEditRefusal.NO_ORIGINAL ->
            R.string.route_edit_refusal_no_original_title to R.string.route_edit_refusal_no_original_message
        RouteEditRefusal.NOT_HERE_YET ->
            R.string.route_edit_refusal_not_here_title to R.string.route_edit_refusal_not_here_message
        RouteEditRefusal.NO_MAPPED_WAY ->
            R.string.route_edit_refusal_no_mapped_way_title to R.string.route_edit_refusal_no_mapped_way_message
        RouteEditRefusal.TOO_MUCH_TO_KEEP ->
            R.string.route_edit_refusal_too_much_title to R.string.route_edit_refusal_too_much_message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(stringResource(R.string.route_edit_ok))
            }
        },
        modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_EDIT_REFUSAL_DIALOG)
    )
}

@Composable
private fun RouteEditSectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { heading() }
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun RouteEditActionRow(
    label: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    isBusy: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val resolvedTint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, tint = resolvedTint)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = resolvedTint,
            modifier = Modifier.weight(1f)
        )
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun RouteEditCardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = RowHorizontalPadding)
    )
}
