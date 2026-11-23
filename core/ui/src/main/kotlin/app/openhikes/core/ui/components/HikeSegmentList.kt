package app.openhikes.core.ui.components

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteSegment
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val CardCornerRadius = 24.dp
private val RowMinHeight = 52.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 10.dp
private val ClimbIconSize = 16.dp
private const val ACCESSIBILITY_FONT_SCALE_THRESHOLD = 1.5f

/**
 * Displays walked route segments and pause durations shared between live recording and hike detail.
 * Renders nothing when the route has 0 or 1 segments.
 */
@Composable
fun HikeSegmentList(segments: List<RouteSegment>, distanceUnit: DistanceUnit, modifier: Modifier = Modifier) {
    if (segments.size <= 1) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.hike_segments_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) SegmentDivider()
                    HikeSegmentRow(
                        number = index + 1,
                        segment = segment,
                        distanceUnit = distanceUnit
                    )
                    segment.stopSeconds?.let { stopSeconds ->
                        SegmentDivider()
                        SegmentPauseRow(stopSeconds = stopSeconds)
                    }
                }
            }
        }
    }
}

@Composable
private fun HikeSegmentRow(number: Int, segment: RouteSegment, distanceUnit: DistanceUnit) {
    val title = stringResource(R.string.hike_segment_label, number)
    val distanceText = HikeMetricsFormatter.formatDistance(segment.distanceMeters, distanceUnit)
    val spokenDistance = HikeMetricsFormatter.formatSpokenDistance(segment.distanceMeters, distanceUnit)
    val durationText = segment.durationSeconds?.let { HikeMetricsFormatter.formatDuration(it) }
    val spokenDuration = segment.durationSeconds?.let { HikeMetricsFormatter.formatSpokenDuration(it) }
    val speedText = segment.averageSpeedMps?.let { HikeMetricsFormatter.formatSpeed(it, distanceUnit) }
    val spokenSpeed = segment.averageSpeedMps?.let { HikeMetricsFormatter.formatSpokenSpeed(it, distanceUnit) }
    val climbText = segment.elevationGainMeters?.let { HikeMetricsFormatter.formatElevation(it, distanceUnit) }
    val spokenClimb = segment.elevationGainMeters?.let {
        stringResource(
            R.string.hike_segment_climb_description,
            HikeMetricsFormatter.formatSpokenElevation(it, distanceUnit)
        )
    }

    val summaryParts = listOfNotNull(durationText, speedText)
    val spokenSummary = listOfNotNull(spokenDistance, spokenDuration, spokenSpeed, spokenClimb)
        .joinToString(separator = ", ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title: $spokenSummary"
            }
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(OpenHikesTestTags.hikeSegment(number)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SegmentHeaderRow(title = title, distanceText = distanceText)
        if (summaryParts.isNotEmpty() || climbText != null) {
            SegmentSummaryMetricsRow(summaryParts = summaryParts, climbText = climbText)
        }
    }
}

@Composable
private fun SegmentHeaderRow(title: String, distanceText: String) {
    val isLargeFont = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE_THRESHOLD
    if (isLargeFont) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = distanceText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SegmentSummaryMetricsRow(summaryParts: List<String>, climbText: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (summaryParts.isNotEmpty()) {
            Text(
                text = summaryParts.joinToString(separator = " · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (climbText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.TrendingUp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ClimbIconSize)
                )
                Text(
                    text = climbText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SegmentPauseRow(stopSeconds: Double) {
    val label = stringResource(R.string.hike_segment_paused_label)
    val value = HikeMetricsFormatter.formatDuration(stopSeconds)
    val spoken = HikeMetricsFormatter.formatSpokenDuration(stopSeconds)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .semantics(mergeDescendants = true) { contentDescription = "$label $spoken" }
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SegmentDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = RowHorizontalPadding)
    )
}
