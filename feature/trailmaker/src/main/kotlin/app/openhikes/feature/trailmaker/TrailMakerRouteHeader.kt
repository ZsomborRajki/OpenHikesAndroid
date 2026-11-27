package app.openhikes.feature.trailmaker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteElevationSummary
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val ElevationMetricIconSize = 14.dp
private val ElevationSpinnerStroke = 2.dp
private val FigureSpacing = 10.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteHeaderRow(
    distanceMeters: Double,
    travelTimeSeconds: Double?,
    elevationSummary: RouteElevationSummary?,
    isMeasuringElevation: Boolean,
    distanceUnit: DistanceUnit
) {
    val lengthText = HikeMetricsFormatter.formatDistance(distanceMeters, distanceUnit)
    val spokenState = routeHeaderSpokenState(
        distanceMeters = distanceMeters,
        travelTimeSeconds = travelTimeSeconds,
        elevationSummary = elevationSummary,
        isMeasuringElevation = isMeasuringElevation,
        distanceUnit = distanceUnit
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = spokenState },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.trail_route_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FigureSpacing),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            RouteElevationHeaderFigures(
                elevationSummary = elevationSummary,
                isMeasuringElevation = isMeasuringElevation,
                distanceUnit = distanceUnit
            )
            if (travelTimeSeconds != null) {
                Text(
                    text = HikeMetricsFormatter.formatDuration(travelTimeSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = lengthText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_DRAFT_LENGTH)
            )
        }
    }
}

@Composable
private fun RouteElevationHeaderFigures(
    elevationSummary: RouteElevationSummary?,
    isMeasuringElevation: Boolean,
    distanceUnit: DistanceUnit
) {
    if (isMeasuringElevation) {
        CircularProgressIndicator(
            strokeWidth = ElevationSpinnerStroke,
            modifier = Modifier
                .size(ElevationMetricIconSize)
                .testTag(OpenHikesTestTags.TRAIL_DRAFT_ELEVATION_MEASURING)
        )
    }
    elevationSummary?.gainMeters?.let { gain ->
        RouteElevationFigure(
            iconRes = OpenHikesIcons.ArrowUpward,
            text = HikeMetricsFormatter.formatElevation(gain, distanceUnit),
            testTag = OpenHikesTestTags.TRAIL_DRAFT_CLIMB
        )
    }
    elevationSummary?.lossMeters?.let { loss ->
        RouteElevationFigure(
            iconRes = OpenHikesIcons.ArrowDownward,
            text = HikeMetricsFormatter.formatElevation(loss, distanceUnit),
            testTag = OpenHikesTestTags.TRAIL_DRAFT_DESCENT
        )
    }
}

@Composable
private fun RouteElevationFigure(iconRes: Int, text: String, testTag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.testTag(testTag)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ElevationMetricIconSize)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun routeHeaderSpokenState(
    distanceMeters: Double,
    travelTimeSeconds: Double?,
    elevationSummary: RouteElevationSummary?,
    isMeasuringElevation: Boolean,
    distanceUnit: DistanceUnit
): String {
    val parts = buildList {
        add(HikeMetricsFormatter.formatSpokenDistance(distanceMeters, distanceUnit))
        travelTimeSeconds?.let { add(HikeMetricsFormatter.formatSpokenDuration(it)) }
        if (isMeasuringElevation) add(stringResource(R.string.trail_route_measuring_climb))
        elevationSummary?.gainMeters?.let { gain ->
            add(
                stringResource(
                    R.string.trail_route_spoken_climb,
                    HikeMetricsFormatter.formatSpokenElevation(gain, distanceUnit)
                )
            )
        }
        elevationSummary?.lossMeters?.let { loss ->
            add(
                stringResource(
                    R.string.trail_route_spoken_descent,
                    HikeMetricsFormatter.formatSpokenElevation(loss, distanceUnit)
                )
            )
        }
    }
    return parts.joinToString(", ")
}
