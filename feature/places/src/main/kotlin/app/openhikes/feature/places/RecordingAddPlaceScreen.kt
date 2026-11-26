package app.openhikes.feature.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.ui.components.TrailPlaceRowContent
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
fun RecordingAddPlaceRoute(
    hikeId: HikeId,
    latitude: Double?,
    longitude: Double?,
    onBack: () -> Unit,
    onAddedPlace: (String) -> Unit,
    onAddOwnPlace: (GeoCoordinate) -> Unit,
    onOpenPlacesNearby: (GeoCoordinate?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingAddPlaceViewModel =
        hiltViewModel<RecordingAddPlaceViewModel, RecordingAddPlaceViewModel.Factory>(
            key = "${hikeId.value}:rec-add:$latitude:$longitude"
        ) { factory ->
            factory.create(hikeId.value, latitude, longitude)
        }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.openedPlaceId) {
        val openedId = uiState.openedPlaceId ?: return@LaunchedEffect
        viewModel.consumeOpenedPlace()
        onAddedPlace(openedId)
    }

    RecordingAddPlaceScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenPlacesNearby = { onOpenPlacesNearby(uiState.coordinate) },
        onPickSuggestion = viewModel::onPickSuggestion,
        onAddOwnPlace = {
            val spot = uiState.coordinate ?: return@RecordingAddPlaceScreen
            onAddOwnPlace(spot)
        },
        onDismissSaveRefusal = viewModel::onDismissSaveRefusal,
        modifier = modifier
    )
}

@Composable
fun RecordingAddPlaceScreen(
    uiState: RecordingAddPlaceUiState,
    onBack: () -> Unit,
    onOpenPlacesNearby: () -> Unit,
    onPickSuggestion: (TrailPoint) -> Unit,
    onAddOwnPlace: () -> Unit,
    onDismissSaveRefusal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.RECORDING_PLACE_SHEET)
    ) {
        PlaceTopBar(
            title = stringResource(R.string.places_screen_title_add_place),
            backTestTag = OpenHikesTestTags.HIKE_PLACE_BACK,
            onBack = onBack
        )
        LazyColumn(
            contentPadding = PaddingValues(PlaceScreenPadding),
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.canFindNearby) {
                item(key = "nearby") {
                    PlaceSectionCard(title = stringResource(R.string.places_screen_title_nearby)) {
                        RecordingActionRow(
                            iconRes = OpenHikesIcons.PoiViewpoint,
                            label = stringResource(R.string.places_screen_title_nearby),
                            testTag = OpenHikesTestTags.RECORDING_PLACES_NEARBY,
                            onClick = onOpenPlacesNearby
                        )
                    }
                }
            }
            if (uiState.coordinate != null) {
                if (uiState.canSearchMappedHere) {
                    item(key = "mapped_here") {
                        MappedHereCard(uiState = uiState, onPickSuggestion = onPickSuggestion)
                    }
                }
                item(key = "own_place") { RecordingAddPlaceOwnCard(onAddOwnPlace = onAddOwnPlace) }
            } else {
                item(key = "no_location") { RecordingAddPlaceNoLocationCard() }
            }
        }
    }

    if (uiState.showSaveRefusal) {
        PlaceSaveRefusalDialog(onDismiss = onDismissSaveRefusal)
    }
}

@Composable
private fun RecordingAddPlaceOwnCard(onAddOwnPlace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceSectionCard(title = stringResource(R.string.places_recording_your_own_section)) {
            RecordingActionRow(
                iconRes = OpenHikesIcons.AddLocation,
                label = stringResource(R.string.places_recording_add_own),
                testTag = OpenHikesTestTags.RECORDING_PLACE_ADD_OWN,
                onClick = onAddOwnPlace
            )
        }
        Text(
            text = stringResource(R.string.places_recording_add_own_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun RecordingAddPlaceNoLocationCard() {
    PlaceSectionCard(title = stringResource(R.string.places_recording_no_location_title)) {
        Text(
            text = stringResource(R.string.places_recording_no_location_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun MappedHereCard(uiState: RecordingAddPlaceUiState, onPickSuggestion: (TrailPoint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaceSectionCard(title = stringResource(R.string.places_recording_mapped_here)) {
            uiState.suggestions.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = PlaceRowMinHeight)
                        .clickable(role = Role.Button) { onPickSuggestion(item.place) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag(OpenHikesTestTags.RECORDING_PLACE_SUGGESTION)
                ) {
                    TrailPlaceRowContent(
                        row = TrailPlaceRow(place = item.place),
                        distanceUnit = uiState.distanceUnit,
                        trailingDistanceMeters = item.distanceMeters,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (uiState.isSearching && uiState.suggestions.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.places_recording_looking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (uiState.suggestions.isEmpty()) {
                Text(
                    text = stringResource(R.string.places_recording_nothing_here),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        uiState.outage?.let { outage ->
            Text(
                text = outageMessage(outage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun RecordingActionRow(iconRes: Int, label: String, testTag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
