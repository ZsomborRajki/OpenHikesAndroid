package app.openhikes.feature.hikes

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.ui.components.TrailPlaceRowContent
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val PlacesCardCornerRadius = 24.dp
private val PlacesRowMinHeight = 52.dp
private val PlacesTouchTarget = 48.dp
private val PlacesHorizontalPadding = 16.dp
private val PlacesVerticalPadding = 12.dp

/**
 * Places section shown on [HikeDetailScreen]'s Details tab: heading with map-pin visibility
 * switch, grouped place rows ordered along the route, and the *Places Around Trail* trigger.
 */
@Composable
internal fun HikeDetailPlacesSection(
    placeRows: List<TrailPlaceRow>,
    showsPlacePins: Boolean,
    canSearchPlaces: Boolean,
    distanceUnit: DistanceUnit,
    onPlaceAction: (HikePlaceAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (placeRows.isEmpty() && !canSearchPlaces) return

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        PlacesSectionHeader(
            showPinsToggle = placeRows.isNotEmpty(),
            showsPlacePins = showsPlacePins,
            onTogglePins = { enabled -> onPlaceAction(HikePlaceAction.SetShowPlacePins(enabled)) }
        )

        if (placeRows.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(PlacesCardCornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OpenHikesTestTags.HIKE_PLACES)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    placeRows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = PlacesHorizontalPadding)
                            )
                        }
                        HikeDetailPlaceRow(
                            row = row,
                            distanceUnit = distanceUnit,
                            onClick = { onPlaceAction(HikePlaceAction.OpenPlace(row.id)) }
                        )
                    }
                }
            }
        }

        if (canSearchPlaces) {
            FilledTonalButton(
                onClick = { onPlaceAction(HikePlaceAction.OpenPlacesAround) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = PlacesTouchTarget)
                    .testTag(OpenHikesTestTags.HIKE_PLACE_SEARCH)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Search),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.hike_detail_places_around_trail))
            }
        }
    }
}

@Composable
private fun PlacesSectionHeader(showPinsToggle: Boolean, showsPlacePins: Boolean, onTogglePins: (Boolean) -> Unit) {
    val toggleDescription = stringResource(R.string.hike_detail_places_pins_toggle_description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.hike_detail_section_places),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )
        if (showPinsToggle) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.hike_detail_places_pins_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = showsPlacePins,
                    onCheckedChange = onTogglePins,
                    modifier = Modifier
                        .semantics { contentDescription = toggleDescription }
                        .testTag(OpenHikesTestTags.HIKE_PLACE_PINS_TOGGLE)
                )
            }
        }
    }
}

@Composable
private fun HikeDetailPlaceRow(row: TrailPlaceRow, distanceUnit: DistanceUnit, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlacesRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = PlacesHorizontalPadding, vertical = PlacesVerticalPadding)
            .testTag(OpenHikesTestTags.TRAIL_PLACE_ROW)
    ) {
        TrailPlaceRowContent(
            row = row,
            distanceUnit = distanceUnit,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
