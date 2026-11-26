package app.openhikes.feature.trailmaker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailStopSearchResult
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val SheetCornerRadius = 24.dp
private val SheetPadding = 20.dp
private val SheetSpacing = 12.dp
private val SheetTouchTarget = 48.dp
private val SheetIconSize = 20.dp
private val SearchListMaxHeight = 320.dp
private val SpinnerSize = 20.dp
private val RowVerticalPadding = 6.dp
private val ActionSpacing = 8.dp
private val ActionIconGap = 6.dp
private val SearchOverlayMargin = 12.dp
private const val SEARCH_SCRIM_ALPHA = 0.32f

@Composable
internal fun TrailStopSearchOverlay(state: TrailStopSearchUiState, onAction: (TrailMakerAction) -> Unit) {
    val sheetTitle = when (val target = state.target) {
        is TrailStopSearchTarget.Open -> stopRoleLabel(target.role)
        is TrailStopSearchTarget.Existing -> stopRoleLabel(target.role)
        TrailStopSearchTarget.NewStop -> stringResource(R.string.trail_stop_add)
    }
    val onClose = { onAction(TrailMakerAction.CloseStopSearch) }

    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SEARCH_SCRIM_ALPHA))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose
            )
            .padding(SearchOverlayMargin),
        contentAlignment = Alignment.TopCenter
    ) {
        TrailStopSearchCard(
            title = sheetTitle,
            state = state,
            onClose = onClose,
            onAction = onAction
        )
    }
}

@Composable
private fun TrailStopSearchCard(
    title: String,
    state: TrailStopSearchUiState,
    onClose: () -> Unit,
    onAction: (TrailMakerAction) -> Unit
) {
    val showRecents = state.query.isBlank() || state.query == state.prefill
    Surface(
        shape = RoundedCornerShape(SheetCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {}
            .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_SHEET)
    ) {
        Column(
            modifier = Modifier.padding(SheetPadding),
            verticalArrangement = Arrangement.spacedBy(SheetSpacing)
        ) {
            TrailStopSearchHeader(title = title, onCancel = onClose)
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(TrailMakerAction.UpdateSearchQuery(it)) },
                singleLine = true,
                leadingIcon = {
                    Icon(painter = painterResource(OpenHikesIcons.Search), contentDescription = null)
                },
                placeholder = { Text(text = stringResource(R.string.trail_search_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_FIELD)
            )
            TrailStopSearchResultsList(
                state = state,
                showRecents = showRecents,
                onAction = onAction
            )
        }
    }
}

@Composable
private fun TrailStopSearchHeader(title: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .defaultMinSize(minHeight = SheetTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_CANCEL)
        ) {
            Text(text = stringResource(R.string.trail_search_cancel))
        }
    }
}

@Composable
private fun TrailStopSearchResultsList(
    state: TrailStopSearchUiState,
    showRecents: Boolean,
    onAction: (TrailMakerAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SearchListMaxHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showRecents) {
            recentStopItems(state = state, onAction = onAction)
        } else {
            searchResultItems(state = state, onAction = onAction)
        }
    }
}

private fun LazyListScope.recentStopItems(state: TrailStopSearchUiState, onAction: (TrailMakerAction) -> Unit) {
    item(key = "recents_header") {
        Text(
            text = stringResource(R.string.trail_search_recents_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    item(key = "my_location") {
        MyLocationSearchRow(
            available = state.myLocationAvailable,
            denied = state.myLocationDenied,
            onClick = { onAction(TrailMakerAction.PickMyLocation) }
        )
    }
    items(state.recents, key = { it.id }) { recent ->
        RecentStopRow(
            recent = recent,
            onPick = { onAction(TrailMakerAction.PickRecentStop(recent)) },
            onRemove = { onAction(TrailMakerAction.RemoveRecentStop(recent.id)) }
        )
    }
}

private fun LazyListScope.searchResultItems(state: TrailStopSearchUiState, onAction: (TrailMakerAction) -> Unit) {
    if (state.isSearching) {
        item(key = "searching") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SheetSpacing),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(SpinnerSize))
            }
        }
    }
    items(state.results, key = { it.id }) { result ->
        SearchResultRow(
            result = result,
            onClick = { onAction(TrailMakerAction.PickSearchResult(result)) }
        )
    }
    if (state.didFail) {
        item(key = "failed") {
            Text(
                text = stringResource(R.string.trail_search_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = ActionSpacing)
                    .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_FAILED)
            )
        }
    }
}

@Composable
private fun MyLocationSearchRow(available: Boolean, denied: Boolean, onClick: () -> Unit) {
    val status = when {
        available -> null
        denied -> stringResource(R.string.trail_search_location_denied)
        else -> stringResource(R.string.trail_search_waiting_location)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SheetTouchTarget)
            .clickable(enabled = available, role = Role.Button, onClick = onClick)
            .padding(vertical = RowVerticalPadding)
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_HERE),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.MyLocation),
            contentDescription = null,
            tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SheetIconSize)
        )
        Spacer(modifier = Modifier.width(SheetSpacing))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.trail_search_my_location),
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecentStopRow(recent: TrailStopRecent, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SheetTouchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = SheetTouchTarget)
                .clickable(role = Role.Button, onClick = onPick)
                .padding(vertical = RowVerticalPadding)
                .semantics(mergeDescendants = true) {}
                .testTag(OpenHikesTestTags.TRAIL_STOP_SEARCH_RECENT),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.History),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SheetIconSize)
            )
            Spacer(modifier = Modifier.width(SheetSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (recent.subtitle.isNotBlank()) {
                    Text(
                        text = recent.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.defaultMinSize(minWidth = SheetTouchTarget, minHeight = SheetTouchTarget)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.trail_search_remove_recent_description),
                modifier = Modifier.size(SheetIconSize)
            )
        }
    }
}

@Composable
private fun SearchResultRow(result: TrailStopSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SheetTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = RowVerticalPadding)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Place),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SheetIconSize)
        )
        Spacer(modifier = Modifier.width(SheetSpacing))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun TrailPlaceCardDialog(
    card: TrailPlaceCardState,
    distanceUnit: DistanceUnit,
    onShare: (String, String) -> Unit,
    onAction: (TrailMakerAction) -> Unit
) {
    val roleLabel = card.role?.let { stopRoleLabel(it) }
    val distanceAlong = card.distanceAlongRouteMeters?.let { meters ->
        stringResource(R.string.trail_stop_along_route, HikeMetricsFormatter.formatDistance(meters, distanceUnit))
    }
    val subtitleParts = listOfNotNull(
        if (card.nameForStop.isNotBlank()) roleLabel else null,
        distanceAlong
    )
    val subtitle = if (subtitleParts.isEmpty()) null else subtitleParts.joinToString(" · ")
    val lat = card.coordinate.latitude
    val lon = card.coordinate.longitude
    val osmLink = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
    val shareText = listOfNotNull(card.title, card.address, card.coordinatesText, osmLink).joinToString("\n")

    Dialog(onDismissRequest = { onAction(TrailMakerAction.ClosePlaceCard) }) {
        Surface(
            shape = RoundedCornerShape(SheetCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.TRAIL_PLACE_SHEET)
        ) {
            Column(
                modifier = Modifier.padding(SheetPadding),
                verticalArrangement = Arrangement.spacedBy(SheetSpacing)
            ) {
                TrailPlaceCardHeader(
                    card = card,
                    subtitle = subtitle,
                    onClose = { onAction(TrailMakerAction.ClosePlaceCard) }
                )
                TrailPlaceCardActions(
                    card = card,
                    onShare = { onShare(card.title, shareText) },
                    onAction = onAction
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TrailPlaceCardDetails(card = card)
            }
        }
    }
}

@Composable
private fun TrailPlaceCardHeader(card: TrailPlaceCardState, subtitle: String?, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Place),
            contentDescription = null,
            tint = if (card.isDroppedPin) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(modifier = Modifier.width(SheetSpacing))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag(OpenHikesTestTags.TRAIL_PLACE_TITLE)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .defaultMinSize(minWidth = SheetTouchTarget, minHeight = SheetTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_PLACE_CLOSE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.trail_place_close_description)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrailPlaceCardActions(
    card: TrailPlaceCardState,
    onShare: () -> Unit,
    onAction: (TrailMakerAction) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ActionSpacing),
        verticalArrangement = Arrangement.spacedBy(ActionSpacing)
    ) {
        if (card.isDroppedPin) {
            PlaceCardPrimaryActionButton(
                label = stringResource(R.string.trail_place_add_stop),
                iconRes = OpenHikesIcons.Add,
                testTag = OpenHikesTestTags.TRAIL_PLACE_ADD_STOP,
                isDestructive = false,
                onClick = { onAction(TrailMakerAction.AddStopFromPlaceCard) }
            )
            FilledTonalButton(
                onClick = { onAction(TrailMakerAction.RemoveDroppedPin) },
                modifier = Modifier
                    .defaultMinSize(minHeight = SheetTouchTarget)
                    .testTag(OpenHikesTestTags.TRAIL_PLACE_REMOVE)
            ) {
                Text(text = stringResource(R.string.trail_place_remove_pin))
            }
        } else if (card.waypointId != null) {
            PlaceCardPrimaryActionButton(
                label = stringResource(R.string.trail_place_remove_stop),
                iconRes = OpenHikesIcons.Delete,
                testTag = OpenHikesTestTags.TRAIL_PLACE_REMOVE_STOP,
                isDestructive = true,
                onClick = { onAction(TrailMakerAction.RemoveStopFromPlaceCard(card.waypointId)) }
            )
        }
        FilledTonalButton(
            onClick = onShare,
            modifier = Modifier
                .defaultMinSize(minHeight = SheetTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_PLACE_SHARE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Share),
                contentDescription = null,
                modifier = Modifier.size(SheetIconSize)
            )
            Spacer(modifier = Modifier.width(ActionIconGap))
            Text(text = stringResource(R.string.trail_place_share))
        }
    }
}

@Composable
private fun PlaceCardPrimaryActionButton(
    label: String,
    iconRes: Int,
    testTag: String,
    isDestructive: Boolean,
    onClick: () -> Unit
) {
    val colors = if (isDestructive) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        colors = colors,
        modifier = Modifier
            .defaultMinSize(minHeight = SheetTouchTarget)
            .testTag(testTag)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(SheetIconSize)
        )
        Spacer(modifier = Modifier.width(ActionIconGap))
        Text(text = label)
    }
}

@Composable
private fun TrailPlaceCardDetails(card: TrailPlaceCardState) {
    card.address?.let { address ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.trail_place_address_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.trail_place_coordinates_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = card.coordinatesText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_PLACE_COORDINATES)
        )
    }
}
