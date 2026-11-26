package app.openhikes.feature.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.TrailPlaceAroundEntry
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.ui.components.TrailPlaceRowContent
import app.openhikes.core.ui.components.labelRes
import app.openhikes.core.ui.components.rememberDisplayName
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

/**
 * Selected place preview card displayed inside *Places Around Trail* and *Places Nearby* so the
 * hiker can inspect Wikipedia/OSM details and add a place without leaving the search screen.
 */
@Composable
internal fun PlaceAroundCardOverlay(
    row: TrailPlaceRow,
    isOnHike: Boolean,
    distanceUnit: DistanceUnit,
    wikidataPlace: WikidataPlace?,
    onAdd: () -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val place = row.place
    val title = place.rememberDisplayName()
    val subtitle = placeAroundCardSubtitle(row = row, isOnHike = isOnHike, distanceUnit = distanceUnit)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.PLACES_AROUND_CARD)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(PlaceScreenPadding),
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                PlaceHeaderRow(
                    symbol = place.symbolKind,
                    title = title,
                    subtitle = subtitle,
                    titleTestTag = OpenHikesTestTags.PLACES_AROUND_CARD_TITLE,
                    trailing = { PlaceAroundCardCloseButton(onClose = onClose) }
                )
            }
            item(key = "actions") {
                PlaceAroundCardActionsRow(isOnHike = isOnHike, onAdd = onAdd, onShare = { onShare(title) })
            }
            if (isOnHike && place.note.isNotBlank()) {
                item(key = "note") {
                    Text(
                        text = place.note,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            item(key = "facts") {
                TrailPlaceFactsAndLocationSection(
                    facts = place.osm?.facts.orEmpty(),
                    coordinate = place.coordinate,
                    distanceUnit = distanceUnit,
                    openStreetMapUrl = place.osm?.url,
                    wikidataPlace = wikidataPlace,
                    onOpenUrl = onOpenUrl
                )
            }
        }
    }
}

@Composable
private fun placeAroundCardSubtitle(row: TrailPlaceRow, isOnHike: Boolean, distanceUnit: DistanceUnit): String {
    val place = row.place
    val kindLabel = if (place.name.isNotBlank()) place.symbolKind?.let { stringResource(it.labelRes()) } else null
    val alongText = row.anchor?.let {
        HikeMetricsFormatter.formatDistance(it.distanceAlongRouteMeters, distanceUnit)
    }
    val offTrailText = row.offRouteMeters?.takeIf { !row.isOnTheLine }?.let { meters ->
        stringResource(
            app.openhikes.core.ui.R.string.place_off_trail_distance,
            HikeMetricsFormatter.formatDistance(meters, distanceUnit)
        )
    }
    val onHikeTag = if (isOnHike) stringResource(R.string.places_on_this_hike) else null
    return listOfNotNull(kindLabel, alongText, offTrailText, onHikeTag).joinToString(" · ")
}

@Composable
private fun PlaceAroundCardCloseButton(onClose: () -> Unit) {
    IconButton(
        onClick = onClose,
        modifier = Modifier
            .defaultMinSize(minWidth = PlaceRowMinHeight, minHeight = PlaceRowMinHeight)
            .testTag(OpenHikesTestTags.PLACES_AROUND_CARD_CLOSE)
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Close),
            contentDescription = stringResource(R.string.places_action_close)
        )
    }
}

@Composable
private fun PlaceAroundCardActionsRow(isOnHike: Boolean, onAdd: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (!isOnHike) {
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = PlaceRowMinHeight)
                    .testTag(OpenHikesTestTags.PLACES_AROUND_CARD_ADD)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Add),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp)
                )
                Text(text = stringResource(R.string.places_action_add))
            }
        }
        FilledTonalButton(
            onClick = onShare,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.PLACES_AROUND_CARD_SHARE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Share),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp)
            )
            Text(text = stringResource(R.string.places_action_share))
        }
    }
}

/**
 * Shared selectable list row with immediate `+` Add action or added checkmark used by both
 * *Places Around Trail* and *Places Nearby*.
 */
@Composable
internal fun TrailPlaceAroundListRow(
    entry: TrailPlaceAroundEntry,
    distanceUnit: DistanceUnit,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    overrideDistanceMeters: Double? = entry.row.anchor?.distanceAlongRouteMeters
) {
    val displayName = entry.row.place.rememberDisplayName()
    val addDescription = stringResource(R.string.places_action_add_named, displayName)
    val addedDescription = stringResource(R.string.places_on_this_hike)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Surface(
            onClick = onOpen,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .semantics(mergeDescendants = true) {}
                .testTag(OpenHikesTestTags.TRAIL_PLACE_ROW)
        ) {
            TrailPlaceRowContent(
                row = entry.row,
                distanceUnit = distanceUnit,
                trailingDistanceMeters = overrideDistanceMeters,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        if (entry.isAdded) {
            Icon(
                painter = painterResource(OpenHikesIcons.CheckCircle),
                contentDescription = addedDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .defaultMinSize(minWidth = PlaceRowMinHeight, minHeight = PlaceRowMinHeight)
                    .padding(12.dp)
                    .semantics { contentDescription = addedDescription }
                    .testTag(OpenHikesTestTags.PLACES_AROUND_ADDED)
            )
        } else {
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .defaultMinSize(minWidth = PlaceRowMinHeight, minHeight = PlaceRowMinHeight)
                    .semantics {
                        contentDescription = addDescription
                        role = Role.Button
                    }
                    .testTag(OpenHikesTestTags.PLACES_AROUND_ADD)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.AddCircle),
                    contentDescription = addDescription,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
