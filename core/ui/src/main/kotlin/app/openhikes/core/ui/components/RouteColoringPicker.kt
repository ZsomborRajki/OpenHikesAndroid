package app.openhikes.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.model.RouteShade
import app.openhikes.core.model.RouteSteepness
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val ShadeKeyBarHeight = 8.dp
private val TouchTargetMinHeight = 48.dp
private const val OFF_KEY_ALPHA = 0.40f
private const val LARGE_FONT_THRESHOLD = 1.5f
private const val PERCENT_DIVISOR = 100.0

/**
 * Shared Material 3 Expressive *Color By* control and six-step [RouteShade] key used in both Route
 * Style (saved hikes) and Trail Maker (*On the Map*).
 */
@Composable
fun RouteColoringPicker(
    selectedColoring: RouteColoring,
    onSelectColoring: (RouteColoring) -> Unit,
    modifier: Modifier = Modifier,
    elevationUnavailableNote: String? = null
) {
    val shownColoring = selectedColoring.lockingElevation(locked = elevationUnavailableNote != null)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Landscape),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.route_coloring_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        RouteColoringSegmentedRow(
            shownColoring = shownColoring,
            elevationLocked = elevationUnavailableNote != null,
            onSelectColoring = onSelectColoring
        )

        RouteShadeKey(
            coloring = shownColoring,
            modifier = Modifier.alpha(if (shownColoring == RouteColoring.OFF) OFF_KEY_ALPHA else 1f)
        )

        Text(
            text = coloringCaption(shownColoring),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (elevationUnavailableNote != null) {
            Text(
                text = elevationUnavailableNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteColoringSegmentedRow(
    shownColoring: RouteColoring,
    elevationLocked: Boolean,
    onSelectColoring: (RouteColoring) -> Unit
) {
    val options = RouteColoring.displayOrder
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == shownColoring
            val label = coloringLabel(option)
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelectColoring(option) },
                enabled = !(option == RouteColoring.ELEVATION && elevationLocked),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTargetMinHeight)
                    .semantics { selected = isSelected }
                    .testTag(OpenHikesTestTags.routeColoringOption(option.rawValue))
            ) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Six-step green-to-black route color scale key showing percent grade thresholds for [RouteColoring.ELEVATION]
 * and "Easier" to "Harder" endpoints for [RouteColoring.DIFFICULTY] / [RouteColoring.OFF].
 */
@Composable
fun RouteShadeKey(coloring: RouteColoring, modifier: Modifier = Modifier) {
    val description = if (coloring == RouteColoring.ELEVATION) {
        stringResource(R.string.route_shade_key_steepness_description)
    } else {
        stringResource(R.string.route_shade_key_difficulty_description)
    }
    val spokenRange = if (coloring == RouteColoring.ELEVATION) {
        stringResource(R.string.route_shade_key_steepness_state)
    } else {
        stringResource(R.string.route_shade_key_difficulty_state)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.ROUTE_SHADE_KEY)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = spokenRange
            },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ShadeKeyBarHeight)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            RouteShade.scale.forEach { shade ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(ShadeKeyBarHeight)
                        .background(routeShadeColor(shade))
                )
            }
        }
        RouteShadeKeyLabels(
            coloring = coloring,
            isLargeFont = LocalDensity.current.fontScale >= LARGE_FONT_THRESHOLD
        )
    }
}

@Composable
private fun RouteShadeKeyLabels(coloring: RouteColoring, isLargeFont: Boolean) {
    if (coloring == RouteColoring.ELEVATION && !isLargeFont) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            RouteShade.scale.forEach { shade ->
                val bound = RouteSteepness.lowerBoundPercent(shade) / PERCENT_DIVISOR
                Text(
                    text = HikeMetricsFormatter.formatPercent(bound),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else if (coloring == RouteColoring.ELEVATION) {
        Text(
            text = stringResource(R.string.route_shade_key_steepness_range),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.route_shade_key_easier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.route_shade_key_harder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun coloringLabel(coloring: RouteColoring): String = when (coloring) {
    RouteColoring.DIFFICULTY -> stringResource(R.string.route_coloring_difficulty)
    RouteColoring.ELEVATION -> stringResource(R.string.route_coloring_elevation)
    RouteColoring.OFF -> stringResource(R.string.route_coloring_off)
}

@Composable
private fun coloringCaption(coloring: RouteColoring): String = when (coloring) {
    RouteColoring.DIFFICULTY -> stringResource(R.string.route_coloring_caption_difficulty)
    RouteColoring.ELEVATION -> stringResource(R.string.route_coloring_caption_elevation)
    RouteColoring.OFF -> stringResource(R.string.route_coloring_caption_off)
}
