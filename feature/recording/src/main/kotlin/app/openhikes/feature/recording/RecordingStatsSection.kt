package app.openhikes.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.ui.components.HikeSegmentList
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val CardCornerRadius = 24.dp
private val CardPadding = 16.dp
private const val TICK_INTERVAL_MILLIS = 1_000L

/**
 * Isolated leaf composable collecting high-frequency [statsFlow] snapshots and hosting a 1 Hz
 * duration leaf so per-fix emissions never recompose [RecordingScreen].
 */
@Composable
internal fun RecordingStatsSection(
    phase: RecordingPhase,
    statsFlow: StateFlow<RecordingStatsSnapshot>,
    distanceUnit: DistanceUnit,
    elapsedSecondsProvider: () -> Double,
    modifier: Modifier = Modifier
) {
    val stats by statsFlow.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (phase == RecordingPhase.WAITING_FOR_FIX && stats.pointCount == 0) {
            WaitingForFixNotice()
        } else {
            RecordingPrimaryStatsCard(
                phase = phase,
                stats = stats,
                distanceUnit = distanceUnit,
                elapsedSecondsProvider = elapsedSecondsProvider
            )
            RecordingSecondaryStatsCard(stats = stats, distanceUnit = distanceUnit)
        }

        HikeSegmentList(segments = stats.segments, distanceUnit = distanceUnit)
    }
}

@Composable
private fun WaitingForFixNotice() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.recording_waiting_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(CardPadding)
        )
    }
}

@Composable
private fun RecordingPrimaryStatsCard(
    phase: RecordingPhase,
    stats: RecordingStatsSnapshot,
    distanceUnit: DistanceUnit,
    elapsedSecondsProvider: () -> Double
) {
    val dash = stringResource(R.string.recording_stat_unavailable)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = stringResource(R.string.recording_stat_distance),
                    value = HikeMetricsFormatter.formatDistance(stats.distanceMeters, distanceUnit),
                    spokenValue = HikeMetricsFormatter.formatSpokenDistance(stats.distanceMeters, distanceUnit),
                    modifier = Modifier.weight(1f)
                )
                RecordingDurationMetricCell(
                    phase = phase,
                    recordedDurationSeconds = stats.recordedDurationSeconds,
                    elapsedSecondsProvider = elapsedSecondsProvider,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCell(
                    label = stringResource(R.string.recording_stat_climb),
                    value = stats.elevationGainMeters?.let {
                        HikeMetricsFormatter.formatElevation(it, distanceUnit)
                    } ?: dash,
                    spokenValue = stats.elevationGainMeters?.let {
                        HikeMetricsFormatter.formatSpokenElevation(it, distanceUnit)
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = stringResource(R.string.recording_stat_moving_time),
                    value = HikeMetricsFormatter.formatDuration(stats.movingSeconds),
                    spokenValue = HikeMetricsFormatter.formatSpokenDuration(stats.movingSeconds),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RecordingSecondaryStatsCard(stats: RecordingStatsSnapshot, distanceUnit: DistanceUnit) {
    val dash = stringResource(R.string.recording_stat_unavailable)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp)) {
            DetailStatRow(
                label = stringResource(R.string.recording_stat_avg_speed),
                value = stats.averageSpeedMps?.let { HikeMetricsFormatter.formatSpeed(it, distanceUnit) } ?: dash,
                spokenValue = stats.averageSpeedMps?.let { HikeMetricsFormatter.formatSpokenSpeed(it, distanceUnit) },
                testTag = OpenHikesTestTags.RECORDING_AVG_SPEED_ROW
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailStatRow(
                label = stringResource(R.string.recording_stat_current_speed),
                value = stats.recentSpeedMps?.let { HikeMetricsFormatter.formatSpeed(it, distanceUnit) } ?: dash,
                spokenValue = stats.recentSpeedMps?.let { HikeMetricsFormatter.formatSpokenSpeed(it, distanceUnit) },
                testTag = OpenHikesTestTags.RECORDING_CURRENT_SPEED_ROW
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailStatRow(
                label = stringResource(R.string.recording_stat_accuracy),
                value = stats.horizontalAccuracyMeters?.let {
                    HikeMetricsFormatter.formatAccuracy(it, distanceUnit)
                } ?: dash,
                spokenValue = stats.horizontalAccuracyMeters?.let {
                    HikeMetricsFormatter.formatSpokenDistance(it, distanceUnit)
                },
                testTag = OpenHikesTestTags.RECORDING_ACCURACY_ROW
            )
        }
    }
}

@Composable
private fun RecordingDurationMetricCell(
    phase: RecordingPhase,
    recordedDurationSeconds: Double,
    elapsedSecondsProvider: () -> Double,
    modifier: Modifier = Modifier
) {
    val isLiveTicking = phase == RecordingPhase.RECORDING || phase == RecordingPhase.WAITING_FOR_FIX
    var liveElapsedSeconds by remember(phase) {
        mutableDoubleStateOf(maxOf(elapsedSecondsProvider(), recordedDurationSeconds))
    }

    LaunchedEffect(isLiveTicking, recordedDurationSeconds) {
        liveElapsedSeconds = maxOf(elapsedSecondsProvider(), recordedDurationSeconds)
        while (isLiveTicking) {
            delay(TICK_INTERVAL_MILLIS)
            liveElapsedSeconds = maxOf(elapsedSecondsProvider(), recordedDurationSeconds)
        }
    }

    val displaySeconds = if (isLiveTicking) liveElapsedSeconds else recordedDurationSeconds
    val roundedSeconds = displaySeconds.roundToLong().toDouble()
    MetricCell(
        label = stringResource(R.string.recording_stat_duration),
        value = HikeMetricsFormatter.formatDuration(roundedSeconds),
        spokenValue = HikeMetricsFormatter.formatSpokenDuration(roundedSeconds),
        modifier = modifier
    )
}

@Composable
private fun MetricCell(label: String, value: String, spokenValue: String?, modifier: Modifier = Modifier) {
    val a11yText = "$label: ${spokenValue ?: value}"
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = a11yText },
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailStatRow(label: String, value: String, spokenValue: String?, testTag: String) {
    val a11yText = "$label: ${spokenValue ?: value}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11yText }
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
