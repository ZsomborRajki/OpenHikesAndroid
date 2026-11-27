package app.openhikes.feature.trailmaker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val CardCornerRadius = 20.dp
private val CardPadding = 16.dp
private val RailWidth = 24.dp
private val RailSpacing = 12.dp
private val RowMinHeight = 52.dp
private val TouchTarget = 48.dp
private val SmallIconSize = 18.dp
private val DotRadiusDp = 5.5.dp
private val StemStrokeDp = 2.dp
private val DashOnDp = 3.dp
private val DashOffDp = 4.dp
private val RowVerticalPadding = 8.dp
private val FooterTopPadding = 8.dp
private val NoticeSpacing = 6.dp

@Composable
internal fun TrailTravelModeBar(
    selectedMode: TrailTravelMode,
    enabled: Boolean,
    onSelectMode: (TrailTravelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = TrailTravelMode.ordered
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.TRAIL_DRAFT_MODE)
    ) {
        modes.forEachIndexed { index, mode ->
            val label = travelModeLabel(mode)
            val iconRes = travelModeIcon(mode)
            SegmentedButton(
                selected = mode == selectedMode,
                onClick = { onSelectMode(mode) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {},
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTarget)
                    .semantics { contentDescription = label }
                    .testTag(OpenHikesTestTags.trailDraftMode(mode.id))
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
internal fun TrailRouteCard(
    uiState: TrailMakerUiState,
    onAction: (TrailMakerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val editingEnabled = uiState.isEditingEnabled
    Surface(
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            RouteHeaderRow(
                distanceMeters = uiState.distanceMeters,
                travelTimeSeconds = uiState.travelTimeSeconds,
                elevationSummary = uiState.elevationSummary,
                isMeasuringElevation = uiState.isMeasuringElevation,
                distanceUnit = uiState.distanceUnit
            )
            Spacer(modifier = Modifier.height(RowVerticalPadding))
            uiState.rows.forEachIndexed { index, row ->
                TrailStopListRow(
                    row = row,
                    totalRows = uiState.rows.size,
                    hasAddStopRow = uiState.canBeSaved,
                    distanceUnit = uiState.distanceUnit,
                    enabled = editingEnabled,
                    onClick = { onAction(TrailMakerAction.OpenStopSearch(searchTargetForRow(row, index))) },
                    onMoveUp = row.waypointId?.let { id -> { onAction(TrailMakerAction.MoveStop(id, -1)) } },
                    onMoveDown = row.waypointId?.let { id -> { onAction(TrailMakerAction.MoveStop(id, 1)) } },
                    onDelete = row.waypointId?.let { id -> { onAction(TrailMakerAction.DeleteStop(id)) } }
                )
            }
            if (uiState.canBeSaved) {
                TrailAddStopRow(
                    enabled = editingEnabled,
                    onClick = { onAction(TrailMakerAction.OpenStopSearch(TrailStopSearchTarget.NewStop)) }
                )
            }
            if (uiState.hasRetryableLegs) {
                TrailRetryRoutingButton(
                    enabled = editingEnabled,
                    onClick = { onAction(TrailMakerAction.RetryRefusedLegs) }
                )
            }
            TrailRouteFooter(
                canBeSaved = uiState.canBeSaved,
                worstLegSnap = uiState.worstLegSnap
            )
        }
    }
}

private fun searchTargetForRow(row: TrailStopRowItem, index: Int): TrailStopSearchTarget = if (row.isOpen) {
    TrailStopSearchTarget.Open(if (index == 0) TrailStopRole.Start else TrailStopRole.End)
} else {
    TrailStopSearchTarget.Existing(
        waypointId = requireNotNull(row.waypointId),
        role = row.role
    )
}

@Composable
private fun TrailRetryRoutingButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RowVerticalPadding)
            .defaultMinSize(minHeight = TouchTarget)
            .testTag(OpenHikesTestTags.TRAIL_DRAFT_RETRY)
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Refresh),
            contentDescription = null,
            modifier = Modifier.size(SmallIconSize)
        )
        Spacer(modifier = Modifier.width(RowVerticalPadding))
        Text(text = stringResource(R.string.trail_draft_try_again))
    }
}

@Composable
@Suppress("LongParameterList")
private fun TrailStopListRow(
    row: TrailStopRowItem,
    totalRows: Int,
    hasAddStopRow: Boolean,
    distanceUnit: DistanceUnit,
    enabled: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val roleLabel = stopRoleLabel(row.role)
    val headingText = when {
        row.isOpen && row.position == 0 -> stringResource(R.string.trail_stop_choose_start)
        row.isOpen -> stringResource(R.string.trail_stop_choose_destination)
        row.name.isNotBlank() -> row.name
        else -> roleLabel
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StopRailIndicator(
            drawTopStem = row.position > 0,
            drawBottomStem = row.position < totalRows - 1 || hasAddStopRow,
            isFilledDot = !row.isOpen && (row.role == TrailStopRole.Start || row.role == TrailStopRole.End),
            isOpen = row.isOpen
        )
        Spacer(modifier = Modifier.width(RailSpacing))
        TrailStopRowBody(
            row = row,
            roleLabel = roleLabel,
            headingText = headingText,
            distanceUnit = distanceUnit,
            enabled = enabled,
            onClick = onClick,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            modifier = Modifier.weight(1f)
        )
        val pointNum = row.pointNumber
        if (!row.isOpen && pointNum != null) {
            StopTrailingControls(
                pointNumber = pointNum,
                stopTitle = headingText,
                enabled = enabled,
                canMoveUp = row.canMoveUp,
                canMoveDown = row.canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun TrailStopRowBody(
    row: TrailStopRowItem,
    roleLabel: String,
    headingText: String,
    distanceUnit: DistanceUnit,
    enabled: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val distanceText = row.distanceMeters.takeIf { !row.isOpen && it > 0.0 }
        ?.let { HikeMetricsFormatter.formatDistance(it, distanceUnit) }
    val isNamed = !row.isOpen && row.name.isNotBlank()
    val subtitle = buildStopSubtitle(isNamed, roleLabel, distanceText)
    val spokenState = listOfNotNull(
        if (isNamed) roleLabel else null,
        distanceText,
        row.arrivingSnap?.let { legNoticeText(it) }
    ).joinToString(", ")
    val a11yActions = if (enabled) {
        stopRowReorderActions(
            row = row,
            moveUpLabel = stringResource(R.string.trail_stop_move_up),
            moveDownLabel = stringResource(R.string.trail_stop_move_down),
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown
        )
    } else {
        emptyList()
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = RowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = RowVerticalPadding)
            .semantics(mergeDescendants = true) {
                if (spokenState.isNotEmpty()) stateDescription = spokenState
                if (a11yActions.isNotEmpty()) customActions = a11yActions
            }
            .testTag(stopRowTag(row)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrailStopRowText(
            headingText = headingText,
            subtitle = subtitle,
            isOpen = row.isOpen,
            modifier = Modifier.weight(1f)
        )
        if (row.arrivingSnap?.isWarning == true) {
            Icon(
                painter = painterResource(OpenHikesIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SmallIconSize)
            )
        }
    }
}

@Composable
private fun TrailStopRowText(headingText: String, subtitle: String?, isOpen: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = headingText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOpen) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun stopRowTag(row: TrailStopRowItem): String = when {
    row.isOpen && row.position == 0 -> OpenHikesTestTags.TRAIL_DRAFT_OPEN_START
    row.isOpen -> OpenHikesTestTags.TRAIL_DRAFT_OPEN_DESTINATION
    else -> OpenHikesTestTags.trailDraftPoint(requireNotNull(row.pointNumber))
}

private fun stopRowReorderActions(
    row: TrailStopRowItem,
    moveUpLabel: String,
    moveDownLabel: String,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
): List<CustomAccessibilityAction> = buildList {
    if (row.canMoveUp && onMoveUp != null) {
        add(
            CustomAccessibilityAction(moveUpLabel) {
                onMoveUp()
                true
            }
        )
    }
    if (row.canMoveDown && onMoveDown != null) {
        add(
            CustomAccessibilityAction(moveDownLabel) {
                onMoveDown()
                true
            }
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun StopTrailingControls(
    pointNumber: Int,
    stopTitle: String,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    if (canMoveUp && onMoveUp != null) {
        IconButton(
            onClick = onMoveUp,
            enabled = enabled,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                .testTag(OpenHikesTestTags.trailDraftPointMoveUp(pointNumber))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowUpward),
                contentDescription = stringResource(R.string.trail_stop_move_up),
                modifier = Modifier.size(SmallIconSize)
            )
        }
    }
    if (canMoveDown && onMoveDown != null) {
        IconButton(
            onClick = onMoveDown,
            enabled = enabled,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                .testTag(OpenHikesTestTags.trailDraftPointMoveDown(pointNumber))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowDownward),
                contentDescription = stringResource(R.string.trail_stop_move_down),
                modifier = Modifier.size(SmallIconSize)
            )
        }
    }
    if (onDelete != null) {
        IconButton(
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTarget, minHeight = TouchTarget)
                .testTag(OpenHikesTestTags.trailDraftPointDelete(pointNumber))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Delete),
                contentDescription = stringResource(R.string.trail_stop_delete_description, stopTitle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SmallIconSize)
            )
        }
    }
}

@Composable
private fun StopRailIndicator(drawTopStem: Boolean, drawBottomStem: Boolean, isFilledDot: Boolean, isOpen: Boolean) {
    val dotColor = if (isOpen) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val stemColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .width(RailWidth)
            .height(RowMinHeight)
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = DotRadiusDp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(DashOnDp.toPx(), DashOffDp.toPx()), 0f)
        val strokeWidth = StemStrokeDp.toPx()
        if (drawTopStem) {
            drawLine(
                color = stemColor,
                start = Offset(centerX, 0f),
                end = Offset(centerX, (centerY - radius - strokeWidth).coerceAtLeast(0f)),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = dash
            )
        }
        if (drawBottomStem) {
            drawLine(
                color = stemColor,
                start = Offset(centerX, (centerY + radius + strokeWidth).coerceAtMost(size.height)),
                end = Offset(centerX, size.height),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = dash
            )
        }
        if (isFilledDot) {
            drawCircle(color = dotColor, radius = radius, center = Offset(centerX, centerY))
        } else {
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = strokeWidth)
            )
        }
    }
}

@Composable
private fun TrailAddStopRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.TRAIL_DRAFT_ADD_STOP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(RailWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SmallIconSize)
            )
        }
        Spacer(modifier = Modifier.width(RailSpacing))
        Text(
            text = stringResource(R.string.trail_stop_add),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TrailRouteFooter(canBeSaved: Boolean, worstLegSnap: TrailLegSnap?) {
    val noticeText = worstLegSnap?.let { legNoticeText(it) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FooterTopPadding),
        verticalArrangement = Arrangement.spacedBy(NoticeSpacing)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!canBeSaved) {
            Text(
                text = stringResource(R.string.trail_draft_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_DRAFT_EMPTY)
            )
        }
        if (noticeText != null) {
            val isWarning = worstLegSnap.isWarning
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NoticeSpacing)
            ) {
                Icon(
                    painter = painterResource(
                        if (isWarning) OpenHikesIcons.Warning else OpenHikesIcons.Info
                    ),
                    contentDescription = null,
                    tint = if (isWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(SmallIconSize)
                )
                Text(
                    text = noticeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        if (canBeSaved) {
            Text(
                text = stringResource(R.string.trail_draft_gesture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun FollowPathsCard(
    snapsToPaths: Boolean,
    travelMode: TrailTravelMode,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(RowVerticalPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.trail_snap_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = snapsToPaths,
                    onCheckedChange = onToggle,
                    enabled = enabled,
                    modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_DRAFT_SNAP)
                )
            }
            Text(
                text = stringResource(
                    if (travelMode == TrailTravelMode.HIKING) {
                        R.string.trail_snap_footer_hiking
                    } else {
                        R.string.trail_snap_footer_other
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
