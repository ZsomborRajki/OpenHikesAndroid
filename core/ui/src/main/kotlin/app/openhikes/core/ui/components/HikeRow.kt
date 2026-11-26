package app.openhikes.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque

private val RowCornerRadius = 20.dp
private val RowMinHeight = 64.dp
private val RowPaddingHorizontal = 12.dp
private val RowPaddingVertical = 10.dp
private val RowItemGap = 12.dp
private val BadgeSize = 40.dp
private val BadgeIconSize = 20.dp
private val TouchTargetMinSize = 48.dp
private val SelectedBorderWidth = 1.5.dp
private const val SELECTED_CONTAINER_ALPHA = 0.14f

/**
 * Reusable hike list item with colored trail glyph, merged metadata semantics,
 * optional reorder controls, long-press reorder offer, and an accessible delete button.
 */
@Suppress("LongParameterList")
@Composable
fun HikeRow(
    hike: HikeSummary,
    distanceUnit: DistanceUnit,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    walkPhase: TrailWalkPhase? = null,
    isRecording: Boolean = false,
    isReordering: Boolean = false,
    isPinned: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onRequestReorder: (() -> Unit)? = null
) {
    val routeTint = parseHexColorOrDefault(hike.tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    val containerColor = if (isSelected) {
        routeTint.copy(alpha = SELECTED_CONTAINER_ALPHA)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val subtitle = remember(hike.distanceMeters, hike.dateEpochMillis, distanceUnit) {
        HikeMetricsFormatter.formatHikeSubtitle(hike.distanceMeters, hike.dateEpochMillis, distanceUnit)
    }
    var showContextMenu by remember { mutableStateOf(false) }
    val onLongClick = if (!isReordering && !isPinned && onRequestReorder != null) {
        { showContextMenu = true }
    } else {
        null
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(RowCornerRadius),
        border = if (isSelected) BorderStroke(SelectedBorderWidth, routeTint) else null,
        modifier = modifier.fillMaxWidth()
    ) {
        Box {
            HikeRowContent(
                hike = hike,
                subtitle = subtitle,
                routeTint = routeTint,
                isSelected = isSelected,
                walkPhase = walkPhase,
                isRecording = isRecording,
                isReordering = isReordering,
                isPinned = isPinned,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onClick = onClick,
                onLongClick = onLongClick,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDeleteClick = onDeleteClick
            )
            if (onRequestReorder != null) {
                HikeRowReorderMenu(
                    expanded = showContextMenu,
                    onDismiss = { showContextMenu = false },
                    onReorder = {
                        showContextMenu = false
                        onRequestReorder()
                    }
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun HikeRowContent(
    hike: HikeSummary,
    subtitle: String,
    routeTint: Color,
    isSelected: Boolean,
    walkPhase: TrailWalkPhase?,
    isRecording: Boolean,
    isReordering: Boolean,
    isPinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = RowMinHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HikeRowSummaryButton(
            hike = hike,
            subtitle = subtitle,
            routeTint = routeTint,
            isSelected = isSelected,
            walkPhase = walkPhase,
            isRecording = isRecording,
            isReordering = isReordering,
            isPinned = isPinned,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onClick = onClick,
            onLongClick = onLongClick,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            modifier = Modifier.weight(1f)
        )
        HikeRowTrailingControls(
            hike = hike,
            isReordering = isReordering,
            isPinned = isPinned,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDeleteClick = onDeleteClick
        )
    }
}

@Composable
private fun HikeRowReorderMenu(expanded: Boolean, onDismiss: () -> Unit, onReorder: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.hike_row_reorder_action)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(OpenHikesIcons.SwapVert),
                    contentDescription = null
                )
            },
            onClick = onReorder
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HikeRowSummaryButton(
    hike: HikeSummary,
    subtitle: String,
    routeTint: Color,
    isSelected: Boolean,
    walkPhase: TrailWalkPhase?,
    isRecording: Boolean,
    isReordering: Boolean,
    isPinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val moveUpLabel = stringResource(R.string.hike_row_move_up_description, hike.displayTitle)
    val moveDownLabel = stringResource(R.string.hike_row_move_down_description, hike.displayTitle)
    val interactionModifier = if (isReordering) {
        Modifier
    } else {
        Modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .then(interactionModifier)
            .semantics(mergeDescendants = true) {
                selected = isSelected
                if (isReordering && !isPinned) {
                    val actions = buildList {
                        if (canMoveUp) {
                            add(
                                CustomAccessibilityAction(moveUpLabel) {
                                    onMoveUp()
                                    true
                                }
                            )
                        }
                        if (canMoveDown) {
                            add(
                                CustomAccessibilityAction(moveDownLabel) {
                                    onMoveDown()
                                    true
                                }
                            )
                        }
                    }
                    if (actions.isNotEmpty()) customActions = actions
                }
            }
            .padding(
                start = RowPaddingHorizontal,
                top = RowPaddingVertical,
                bottom = RowPaddingVertical
            )
            .testTag(OpenHikesTestTags.hikeRow(hike.id.value)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowItemGap)
    ) {
        HikeRowBadge(routeTint = routeTint)
        HikeRowMetadata(
            title = hike.displayTitle,
            subtitle = subtitle,
            isRecording = isRecording,
            walkPhase = walkPhase,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HikeRowBadge(routeTint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BadgeSize)
            .clip(CircleShape)
            .background(routeTint)
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Hiking),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(BadgeIconSize)
        )
    }
}

@Composable
private fun HikeRowMetadata(
    title: String,
    subtitle: String,
    isRecording: Boolean,
    walkPhase: TrailWalkPhase?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            when {
                isRecording -> ActivityBadgeChip(
                    label = stringResource(R.string.hike_row_recording),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
                walkPhase != null -> WalkPhaseChip(phase = walkPhase)
            }
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HikeRowTrailingControls(
    hike: HikeSummary,
    isReordering: Boolean,
    isPinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteClick: () -> Unit
) {
    if (!isReordering) {
        HikeRowDeleteButton(title = hike.displayTitle, hikeId = hike.id.value, onClick = onDeleteClick)
    } else if (!isPinned) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 4.dp)
        ) {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.hikeRowMoveUp(hike.id.value))
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.ArrowUpward),
                    contentDescription = stringResource(R.string.hike_row_move_up_description, hike.displayTitle)
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.hikeRowMoveDown(hike.id.value))
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.ArrowDownward),
                    contentDescription = stringResource(R.string.hike_row_move_down_description, hike.displayTitle)
                )
            }
        }
    }
}

@Composable
private fun WalkPhaseChip(phase: TrailWalkPhase) {
    val (labelRes, containerColor, contentColor) = when (phase) {
        TrailWalkPhase.FOLLOWING -> Triple(
            R.string.hike_row_walk_active,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        TrailWalkPhase.PAUSED -> Triple(
            R.string.hike_row_walk_paused,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    ActivityBadgeChip(
        label = stringResource(labelRes),
        containerColor = containerColor,
        contentColor = contentColor
    )
}

@Composable
private fun ActivityBadgeChip(label: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        shape = CircleShape
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun HikeRowDeleteButton(title: String, hikeId: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 4.dp)
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .testTag(OpenHikesTestTags.hikeRowDelete(hikeId))
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Delete),
            contentDescription = stringResource(R.string.hike_row_delete_description, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
