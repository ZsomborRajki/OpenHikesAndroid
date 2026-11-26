package app.openhikes.feature.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RecordingEnergyReason
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.ui.components.TrailPlaceRowContent
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.StateFlow

private val TouchTargetMin = 48.dp
private val NoticeCornerRadius = 18.dp
private val IconSize = 18.dp

@Composable
internal fun RecordingHeaderBar(
    session: RecordingSessionState,
    statsFlow: StateFlow<RecordingStatsSnapshot>,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMin, minHeight = TouchTargetMin)
                .testTag(OpenHikesTestTags.RECORDING_BACK)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.recording_back_description)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.recording_screen_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = phaseLabel(session.phase, session.isAutoPaused),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(OpenHikesTestTags.RECORDING_PHASE)
            )
        }
        RecordingPointBadge(statsFlow = statsFlow)
    }
}

@Composable
private fun RecordingPointBadge(statsFlow: StateFlow<RecordingStatsSnapshot>) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    val count = stats.pointCount
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = pluralStringResource(R.plurals.recording_points_badge, count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(OpenHikesTestTags.RECORDING_POINT_COUNT)
        )
    }
}

@Composable
internal fun RecordingRecoveryNotice(
    recoveryState: RecordingRecoveryState,
    distanceUnit: DistanceUnit,
    onDismiss: () -> Unit
) {
    when (recoveryState) {
        RecordingRecoveryState.Absent -> Unit
        RecordingRecoveryState.Resumed -> ResumedRecoveryCard(onDismiss = onDismiss)
        is RecordingRecoveryState.NeedsDecision -> NeedsDecisionRecoveryCard(
            summary = recoveryState.summary,
            distanceUnit = distanceUnit
        )
    }
}

@Composable
private fun ResumedRecoveryCard(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(NoticeCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.RECORDING_RECOVERY_NOTICE)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.recording_recovery_resumed_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.recording_recovery_resumed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(OpenHikesTestTags.RECORDING_RECOVERY_DISMISS)
            ) {
                Text(stringResource(R.string.recording_action_dismiss))
            }
        }
    }
}

@Composable
private fun NeedsDecisionRecoveryCard(
    summary: app.openhikes.core.model.RecordingRecoverySummary,
    distanceUnit: DistanceUnit
) {
    val distance = HikeMetricsFormatter.formatDistance(summary.distanceMeters, distanceUnit)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(NoticeCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.RECORDING_RECOVERY_NOTICE)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.recording_recovery_decision_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = pluralStringResource(
                    R.plurals.recording_recovery_decision_body,
                    summary.pointCount,
                    distance,
                    summary.pointCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
internal fun RecordingConditionsNotice(session: RecordingSessionState) {
    val message = when {
        session.isAutoPaused -> stringResource(R.string.recording_notice_auto_paused)
        session.energyProfile.reason == RecordingEnergyReason.LOW_POWER_AND_HOT ->
            stringResource(R.string.recording_notice_low_power_and_hot)
        session.energyProfile.reason == RecordingEnergyReason.LOW_POWER ->
            stringResource(R.string.recording_notice_low_power)
        session.energyProfile.reason == RecordingEnergyReason.THERMAL ->
            stringResource(R.string.recording_notice_thermal)
        session.energyProfile.reason == RecordingEnergyReason.STATIONARY ->
            stringResource(R.string.recording_notice_stationary)
        else -> null
    } ?: return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(NoticeCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.RECORDING_CONDITIONS_NOTICE)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
internal fun RecordingFailureBanner(
    failure: RecordingFailure,
    canRetrySave: Boolean,
    onAction: (RecordingAction) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NoticeCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.RECORDING_FAILURE_DIALOG)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.Warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(IconSize)
                )
                Text(
                    text = failureMessage(failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRetrySave) {
                    Button(
                        onClick = { onAction(RecordingAction.RetrySave) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = TouchTargetMin)
                            .testTag(OpenHikesTestTags.RECORDING_RETRY_SAVE)
                    ) {
                        Text(stringResource(R.string.recording_action_retry_save))
                    }
                    OutlinedButton(
                        onClick = { onAction(RecordingAction.RequestDiscard) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = TouchTargetMin)
                            .testTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON)
                    ) {
                        Text(stringResource(R.string.recording_action_discard))
                    }
                } else {
                    Button(
                        onClick = { onAction(RecordingAction.TryAgain) },
                        modifier = Modifier
                            .defaultMinSize(minHeight = TouchTargetMin)
                            .testTag(OpenHikesTestTags.RECORDING_TRY_AGAIN_BUTTON)
                    ) {
                        Text(stringResource(R.string.recording_action_try_again))
                    }
                    TextButton(
                        onClick = { onAction(RecordingAction.DismissFailure) },
                        modifier = Modifier.defaultMinSize(minHeight = TouchTargetMin)
                    ) {
                        Text(stringResource(R.string.recording_action_dismiss))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RecordingControlsBar(session: RecordingSessionState, onAction: (RecordingAction) -> Unit) {
    when (session.phase) {
        RecordingPhase.IDLE -> Button(
            onClick = { onAction(RecordingAction.Start) },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TouchTargetMin)
                .testTag(OpenHikesTestTags.RECORDING_START_BUTTON)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.Record),
                contentDescription = null,
                modifier = Modifier.size(IconSize)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.recording_action_start))
        }
        RecordingPhase.WAITING_FOR_FIX,
        RecordingPhase.RECORDING -> RecordingActiveControlsRow(onAction = onAction)
        RecordingPhase.PAUSED -> RecordingPausedControlsColumn(onAction = onAction)
        RecordingPhase.SAVING,
        RecordingPhase.RECOVERING -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        RecordingPhase.FAILED -> Unit
    }
}

@Composable
private fun RecordingActiveControlsRow(onAction: (RecordingAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(
            onClick = { onAction(RecordingAction.Pause) },
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = TouchTargetMin)
                .testTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.Pause),
                contentDescription = null,
                modifier = Modifier.size(IconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.recording_action_pause))
        }
        Button(
            onClick = { onAction(RecordingAction.Stop) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = TouchTargetMin)
                .testTag(OpenHikesTestTags.RECORDING_STOP_BUTTON)
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.Stop),
                contentDescription = null,
                modifier = Modifier.size(IconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.recording_action_stop))
        }
    }
}

@Composable
private fun RecordingPausedControlsColumn(onAction: (RecordingAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onAction(RecordingAction.Resume) },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = TouchTargetMin)
                    .testTag(OpenHikesTestTags.RECORDING_RESUME_BUTTON)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.PlayArrow),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.recording_action_resume))
            }
            FilledTonalButton(
                onClick = { onAction(RecordingAction.Stop) },
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = TouchTargetMin)
                    .testTag(OpenHikesTestTags.RECORDING_STOP_BUTTON)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.Stop),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.recording_action_stop))
            }
        }
        OutlinedButton(
            onClick = { onAction(RecordingAction.RequestDiscard) },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TouchTargetMin)
                .testTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON)
        ) {
            Text(stringResource(R.string.recording_action_discard))
        }
    }
}

@Composable
private fun phaseLabel(phase: RecordingPhase, isAutoPaused: Boolean): String = when (phase) {
    RecordingPhase.IDLE -> stringResource(R.string.recording_phase_idle)
    RecordingPhase.RECOVERING -> stringResource(R.string.recording_phase_recovering)
    RecordingPhase.WAITING_FOR_FIX -> stringResource(R.string.recording_phase_waiting_for_fix)
    RecordingPhase.RECORDING -> stringResource(R.string.recording_phase_recording)
    RecordingPhase.PAUSED -> if (isAutoPaused) {
        stringResource(R.string.recording_phase_auto_paused)
    } else {
        stringResource(R.string.recording_phase_paused)
    }
    RecordingPhase.SAVING -> stringResource(R.string.recording_phase_saving)
    RecordingPhase.FAILED -> stringResource(R.string.recording_phase_failed)
}

@Composable
private fun failureMessage(failure: RecordingFailure): String = when (failure) {
    RecordingFailure.LocationDenied -> stringResource(R.string.recording_failure_location_denied)
    RecordingFailure.PreciseLocationRequired -> stringResource(R.string.recording_failure_precise_required)
    RecordingFailure.TooShort -> stringResource(R.string.recording_failure_too_short)
    RecordingFailure.StorageUnavailable -> stringResource(R.string.recording_failure_storage_unavailable)
    is RecordingFailure.Storage -> stringResource(R.string.recording_failure_storage_generic, failure.detail)
    is RecordingFailure.Save -> stringResource(R.string.recording_failure_save_generic)
}

@Composable
internal fun RecordingPlacesSection(
    places: List<TrailPoint>,
    distanceUnit: DistanceUnit,
    onOpenPlace: (String) -> Unit
) {
    if (places.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.recording_places_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(NoticeCornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.HIKE_PLACES)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                places.forEachIndexed { index, place ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = TouchTargetMin)
                            .clickable(role = Role.Button) { onOpenPlace(place.id) }
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag(OpenHikesTestTags.TRAIL_PLACE_ROW)
                    ) {
                        TrailPlaceRowContent(
                            row = TrailPlaceRow(place = place, anchor = null),
                            distanceUnit = distanceUnit,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(OpenHikesIcons.ChevronRight),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
