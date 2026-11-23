package app.openhikes.feature.hikes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val WalkCardCornerRadius = 24.dp
private val WalkCardPadding = 16.dp
private val WalkTouchTargetMin = 48.dp
private val WalkRowMinHeight = 64.dp
private val PhaseDotSize = 10.dp
private val ProgressHeight = 8.dp
private val CoverageRingSize = 40.dp
private val CoverageRingStroke = 4.dp
private const val WALK_CLOCK_TICK_MILLIS = 1_000L
private const val FULL_SWEEP_DEGREES = 360f
private const val RING_START_DEGREES = -90f

/**
 * Top bar walk toggle button (`Start Hike` -> `Pause Hike` -> `Resume Hike`).
 * This is the sole pause/resume control on the hike detail screen.
 */
@Composable
internal fun WalkToggleTopBarButton(
    phase: TrailWalkPhase?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (labelRes, iconRes) = when (phase) {
        null -> R.string.walk_action_start to OpenHikesIcons.PlayArrow
        TrailWalkPhase.FOLLOWING -> R.string.walk_action_pause to OpenHikesIcons.Pause
        TrailWalkPhase.PAUSED -> R.string.walk_action_resume to OpenHikesIcons.PlayArrow
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier
            .defaultMinSize(minHeight = WalkTouchTargetMin)
            .testTag(OpenHikesTestTags.WALK_TOGGLE)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(labelRes),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Material 3 Expressive connected toggle group switching between Details and History tabs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HikeDetailSegmentRow(
    selectedTab: HikeDetailTab,
    onSelectTab: (HikeDetailTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val detailsInteraction = remember { MutableInteractionSource() }
    val historyInteraction = remember { MutableInteractionSource() }

    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.WALK_SEGMENT),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        customItem(
            buttonGroupContent = {
                ToggleButton(
                    checked = selectedTab == HikeDetailTab.DETAILS,
                    onCheckedChange = { onSelectTab(HikeDetailTab.DETAILS) },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    interactionSource = detailsInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .animateWidth(detailsInteraction)
                        .defaultMinSize(minHeight = WalkTouchTargetMin)
                        .testTag(OpenHikesTestTags.WALK_SEGMENT_DETAILS)
                ) {
                    Text(text = stringResource(R.string.walk_tab_details), maxLines = 1)
                }
            },
            menuContent = {}
        )
        customItem(
            buttonGroupContent = {
                ToggleButton(
                    checked = selectedTab == HikeDetailTab.HISTORY,
                    onCheckedChange = { onSelectTab(HikeDetailTab.HISTORY) },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    interactionSource = historyInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .animateWidth(historyInteraction)
                        .defaultMinSize(minHeight = WalkTouchTargetMin)
                        .testTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY)
                ) {
                    Text(text = stringResource(R.string.walk_tab_history), maxLines = 1)
                }
            },
            menuContent = {}
        )
    }
}

/**
 * Live trail-following status card displayed above both Details and History tabs while a walk
 * is active or paused on this hike. High-frequency progress and 1 Hz elapsed time are read
 * inside isolated leaf composables.
 */
@Composable
internal fun HikeActiveWalkCard(
    phase: TrailWalkPhase,
    tintHex: String,
    distanceUnit: DistanceUnit,
    walkProgressFlow: StateFlow<TrailWalkProgressState>,
    activeSecondsProvider: () -> Double,
    onRequestEndWalk: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(WalkCardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WalkCardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WalkPhaseLeafRow(
                phase = phase,
                activeSecondsProvider = activeSecondsProvider
            )
            TrailProgressLeaf(
                walkProgressFlow = walkProgressFlow,
                accentColor = accent,
                distanceUnit = distanceUnit
            )
            OutlinedButton(
                onClick = onRequestEndWalk,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = WalkTouchTargetMin)
                    .testTag(OpenHikesTestTags.WALK_CONTROLS)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Stop),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.walk_action_end_and_save))
            }
        }
    }
}

@Composable
private fun WalkPhaseLeafRow(phase: TrailWalkPhase, activeSecondsProvider: () -> Double) {
    val isLive = phase == TrailWalkPhase.FOLLOWING
    var activeSeconds by remember(phase) { mutableDoubleStateOf(activeSecondsProvider()) }

    LaunchedEffect(isLive) {
        activeSeconds = activeSecondsProvider()
        while (isLive) {
            delay(WALK_CLOCK_TICK_MILLIS)
            activeSeconds = activeSecondsProvider()
        }
    }

    val dotColor = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val phaseText = if (isLive) {
        stringResource(R.string.walk_phase_active)
    } else {
        stringResource(R.string.walk_phase_paused)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.WALK_PHASE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(PhaseDotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = phaseText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = HikeMetricsFormatter.formatDuration(activeSeconds.roundToLong().toDouble()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrailProgressLeaf(
    walkProgressFlow: StateFlow<TrailWalkProgressState>,
    accentColor: Color,
    distanceUnit: DistanceUnit
) {
    val progress by walkProgressFlow.collectAsStateWithLifecycle()
    val fraction = progress.coveredFraction.toFloat().coerceIn(0f, 1f)
    val percentText = HikeMetricsFormatter.formatPercent(progress.coveredFraction)
    val remainingDistance = HikeMetricsFormatter.formatDistance(progress.metersLeft, distanceUnit)
    val remainingText = stringResource(R.string.walk_progress_remaining, remainingDistance)
    val timeLeftText = progress.secondsLeft?.let { HikeMetricsFormatter.formatTimeLeft(it) }
    val detailLine = if (timeLeftText != null) "$remainingText · $timeLeftText" else remainingText

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.TRAIL_PROGRESS),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = percentText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detailLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(ProgressHeight)
                .clip(CircleShape)
        )
    }
}

@Composable
internal fun WalkOtherHikeNotice(otherHikeTitle: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(WalkCardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.WALK_NOTICE)
    ) {
        Text(
            text = stringResource(R.string.walk_other_hike_notice, otherHikeTitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(WalkCardPadding)
        )
    }
}

@Composable
internal fun HikeFollowTrailCard(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(WalkCardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = WalkRowMinHeight)
                .padding(horizontal = WalkCardPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.walk_follow_trail_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.walk_follow_trail_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag(OpenHikesTestTags.FOLLOW_TRAIL_SWITCH)
            )
        }
    }
}

@Composable
internal fun WalkHistoryEmptyCard(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(WalkCardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.WALK_HISTORY_EMPTY)
    ) {
        Column(
            modifier = Modifier.padding(WalkCardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.History),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.walk_history_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.walk_history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WalkHistoryRow(walk: HikeWalk, tintHex: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fraction = walk.coveredFraction.toFloat().coerceIn(0f, 1f)
    val subtitle = stringResource(
        R.string.walk_row_hiked_subtitle,
        HikeMetricsFormatter.formatPercent(walk.coveredFraction),
        HikeMetricsFormatter.formatDuration(walk.activeSeconds),
        walkEndReasonLabel(walk.endReason)
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(WalkCardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = WalkRowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = WalkCardPadding, vertical = 12.dp)
                .testTag(OpenHikesTestTags.walkRow(walk.id)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WalkCoverageRing(fraction = fraction, accent = accent, trackColor = trackColor)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = HikeMetricsFormatter.formatDate(walk.startedAtEpochMillis),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(OpenHikesIcons.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WalkCoverageRing(fraction: Float, accent: Color, trackColor: Color) {
    Canvas(modifier = Modifier.size(CoverageRingSize)) {
        val stroke = Stroke(width = CoverageRingStroke.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = trackColor,
            startAngle = RING_START_DEGREES,
            sweepAngle = FULL_SWEEP_DEGREES,
            useCenter = false,
            style = stroke
        )
        drawArc(
            color = accent,
            startAngle = RING_START_DEGREES,
            sweepAngle = FULL_SWEEP_DEGREES * fraction,
            useCenter = false,
            style = stroke
        )
    }
}

@Composable
internal fun EndWalkConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.WALK_END_DIALOG),
        title = { Text(text = stringResource(R.string.walk_end_dialog_title)) },
        text = { Text(text = stringResource(R.string.walk_end_dialog_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minHeight = WalkTouchTargetMin)
                    .testTag(OpenHikesTestTags.WALK_END_CONFIRM_BUTTON)
            ) {
                Text(text = stringResource(R.string.walk_end_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = WalkTouchTargetMin)
            ) {
                Text(text = stringResource(R.string.walk_end_dialog_cancel))
            }
        }
    )
}

@Composable
internal fun EndWalkRefusedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.WALK_END_REFUSED_DIALOG),
        title = { Text(text = stringResource(R.string.walk_end_refused_title)) },
        text = { Text(text = stringResource(R.string.walk_end_refused_body)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = WalkTouchTargetMin)
            ) {
                Text(text = stringResource(R.string.walk_end_refused_ok))
            }
        }
    )
}

@Composable
private fun walkEndReasonLabel(reason: TrailWalkEndReason): String = when (reason) {
    TrailWalkEndReason.REACHED_END -> stringResource(R.string.walk_reason_reached_end)
    TrailWalkEndReason.ABANDONED -> stringResource(R.string.walk_reason_abandoned)
    TrailWalkEndReason.RECORDED -> stringResource(R.string.walk_reason_recorded)
    TrailWalkEndReason.ROUTE_CHANGED -> stringResource(R.string.walk_reason_route_changed)
    TrailWalkEndReason.ENDED -> stringResource(R.string.walk_reason_ended)
}
