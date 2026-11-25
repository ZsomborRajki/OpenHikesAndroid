package app.openhikes.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.R
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.location.OffTrailQuestion
import app.openhikes.core.location.TrailWalkSessionState
import app.openhikes.core.location.TrailWalkStartNotice
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.WalkTimeLeftMark
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val WalkChromeTouchMin = 48.dp
private val WalkPillElevation = 3.dp
private val WalkIconSize = 18.dp
internal val EmptyWalkTimeLeftFlow: StateFlow<WalkTimeLeftMark?> = MutableStateFlow(null)

/**
 * Trail-following overlay state and actions hosted by [MapFloatingControls].
 */
@Immutable
data class MapWalkChromeState(
    val session: TrailWalkSessionState = TrailWalkSessionState(),
    val timeLeftMarkFlow: StateFlow<WalkTimeLeftMark?> = EmptyWalkTimeLeftFlow,
    val onToggleJunctionWarnings: () -> Unit = {},
    val onOpenWalkedHike: (HikeId) -> Unit = {},
    val onDismissStartNotice: () -> Unit = {}
)

/**
 * Toolbar toggle button enabling or muting spoken junction warnings during an active walk.
 */
@Composable
internal fun JunctionWarningsToolbarButton(enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.map_junction_warnings_description)
    IconToggleButton(
        checked = enabled,
        onCheckedChange = { onToggle() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContentColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
            .defaultMinSize(minWidth = WalkChromeTouchMin, minHeight = WalkChromeTouchMin)
            .semantics {
                selected = enabled
                contentDescription = label
            }
            .testTag(OpenHikesTestTags.MAP_JUNCTION_WARNINGS_BUTTON)
    ) {
        Icon(
            painter = painterResource(if (enabled) OpenHikesIcons.VolumeUp else OpenHikesIcons.VolumeOff),
            contentDescription = null
        )
    }
}

/**
 * Dismissible map pill announced when movement along a trail starts a walk automatically.
 */
@Composable
internal fun WalkStartedPill(
    notice: TrailWalkStartNotice,
    onOpenHike: (HikeId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
        shadowElevation = WalkPillElevation,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = WalkChromeTouchMin)
                    .clickable(role = Role.Button) { onOpenHike(notice.hikeId) }
                    .semantics(mergeDescendants = true) {}
                    .padding(start = 12.dp, end = 8.dp)
                    .testTag(OpenHikesTestTags.WALK_STARTED_PILL)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Hiking),
                    contentDescription = null,
                    modifier = Modifier.size(WalkIconSize)
                )
                Text(
                    text = stringResource(R.string.walk_started_pill_label, notice.title),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minWidth = WalkChromeTouchMin, minHeight = WalkChromeTouchMin)
                    .testTag(OpenHikesTestTags.WALK_STARTED_PILL_DISMISS)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Close),
                    contentDescription = stringResource(R.string.walk_started_pill_dismiss_description),
                    modifier = Modifier.size(WalkIconSize)
                )
            }
        }
    }
}

/**
 * Isolated leaf collecting remaining-time estimates so fix updates never recompose shell chrome.
 */
@Composable
internal fun WalkTimeLeftBubble(timeLeftFlow: StateFlow<WalkTimeLeftMark?>, modifier: Modifier = Modifier) {
    val mark by timeLeftFlow.collectAsStateWithLifecycle()
    val remaining = mark?.remainingSeconds ?: return
    val text = HikeMetricsFormatter.formatTimeLeft(remaining)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        shadowElevation = WalkPillElevation,
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.WALK_TIME_LEFT)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Directions),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(WalkIconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun EndWalkForRecordingDialog(trailTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.WALK_RECORDING_CONFIRM_DIALOG),
        title = { Text(text = stringResource(R.string.walk_recording_confirm_title)) },
        text = { Text(text = stringResource(R.string.walk_recording_confirm_body, trailTitle)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.defaultMinSize(minHeight = WalkChromeTouchMin)
            ) {
                Text(text = stringResource(R.string.walk_recording_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = WalkChromeTouchMin)
            ) {
                Text(text = stringResource(R.string.walk_recording_confirm_cancel))
            }
        }
    )
}

@Composable
internal fun OffTrailQuestionDialog(
    question: OffTrailQuestion,
    onKeepFollowing: () -> Unit,
    onRecordInstead: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepFollowing,
        modifier = Modifier.testTag(OpenHikesTestTags.WALK_OFF_TRAIL_DIALOG),
        title = { Text(text = stringResource(R.string.walk_off_trail_title)) },
        text = { Text(text = stringResource(R.string.walk_off_trail_body, question.trailTitle)) },
        confirmButton = {
            TextButton(
                onClick = onRecordInstead,
                modifier = Modifier.defaultMinSize(minHeight = WalkChromeTouchMin)
            ) {
                Text(text = stringResource(R.string.walk_off_trail_record))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepFollowing,
                modifier = Modifier.defaultMinSize(minHeight = WalkChromeTouchMin)
            ) {
                Text(text = stringResource(R.string.walk_off_trail_keep))
            }
        }
    )
}
