package app.openhikes.feature.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
fun PlacesNearbyRoute(
    hikeId: HikeId,
    initialLatitude: Double?,
    initialLongitude: Double?,
    onBack: () -> Unit,
    onAddOwnPlace: (GeoCoordinate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlacesNearbyViewModel = hiltViewModel<PlacesNearbyViewModel, PlacesNearbyViewModel.Factory>(
        key = "${hikeId.value}:nearby"
    ) { factory ->
        factory.create(hikeId.value, initialLatitude, initialLongitude)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.pendingLongPressCoordinate) {
        val coord = uiState.pendingLongPressCoordinate ?: return@LaunchedEffect
        viewModel.consumeLongPressCoordinate()
        onAddOwnPlace(coord)
    }

    PlacesNearbyScreen(
        uiState = uiState,
        onBack = onBack,
        onToggleSymbol = viewModel::onToggleSymbol,
        onRetry = viewModel::onRetry,
        onOpenPlace = viewModel::onSelectPlace,
        onAddPlace = viewModel::onAddPlace,
        onCloseCard = viewModel::onCloseCard,
        onShareCard = { title ->
            val place = uiState.selectedCard?.row?.place ?: return@PlacesNearbyScreen
            sharePlaceIntent(context, title, place.coordinate, place.osm?.url)
        },
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        onDismissSaveRefusal = viewModel::onDismissSaveRefusal,
        modifier = modifier
    )
}

@Suppress("LongParameterList")
@Composable
fun PlacesNearbyScreen(
    uiState: PlacesNearbyUiState,
    onBack: () -> Unit,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onAddPlace: (String) -> Unit,
    onCloseCard: () -> Unit,
    onShareCard: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismissSaveRefusal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.PLACES_NEARBY_SCREEN)
    ) {
        val selected = uiState.selectedCard
        if (selected != null) {
            PlaceAroundCardOverlay(
                row = selected.row,
                isOnHike = selected.isOnHike,
                distanceUnit = uiState.distanceUnit,
                wikidataPlace = selected.wikidataPlace,
                onAdd = { onAddPlace(selected.row.id) },
                onShare = onShareCard,
                onClose = onCloseCard,
                onOpenUrl = onOpenUrl
            )
        } else {
            PlacesNearbyListContent(
                uiState = uiState,
                onBack = onBack,
                onToggleSymbol = onToggleSymbol,
                onRetry = onRetry,
                onOpenPlace = onOpenPlace,
                onAddPlace = onAddPlace
            )
        }
    }

    if (uiState.showSaveRefusal) {
        PlaceSaveRefusalDialog(onDismiss = onDismissSaveRefusal)
    }
}

@Composable
private fun PlacesNearbyListContent(
    uiState: PlacesNearbyUiState,
    onBack: () -> Unit,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onAddPlace: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PlaceTopBar(
            title = stringResource(R.string.places_screen_title_nearby),
            backTestTag = OpenHikesTestTags.PLACES_NEARBY_BACK,
            onBack = onBack
        )
        LazyColumn(
            contentPadding = PaddingValues(PlaceScreenPadding),
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item(key = "chips") {
                TrailPlaceKindChipsRow(
                    shownSymbols = uiState.shownSymbols,
                    onToggleSymbol = onToggleSymbol
                )
            }
            item(key = "status") {
                PlacesNearbyStatusBlock(uiState = uiState, onRetry = onRetry)
            }
            if (uiState.entries.isNotEmpty()) {
                item(key = "list") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(PlaceCardCornerRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OpenHikesTestTags.PLACES_NEARBY_LIST)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            uiState.entries.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                TrailPlaceAroundListRow(
                                    entry = item.entry,
                                    distanceUnit = uiState.distanceUnit,
                                    overrideDistanceMeters = item.meters,
                                    onOpen = { onOpenPlace(item.id) },
                                    onAdd = { onAddPlace(item.id) }
                                )
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Text(
                    text = stringResource(R.string.places_nearby_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlacesNearbyStatusBlock(uiState: PlacesNearbyUiState, onRetry: () -> Unit) {
    when {
        uiState.isSearching -> PlacesNearbySearchingRow()
        uiState.outage != null && uiState.entries.isEmpty() -> PlacesNearbyFailedCard(uiState.outage, onRetry)
        uiState.outage != null -> PlacesNearbyOutageRow(uiState.outage)
        uiState.hasAnsweredEmpty -> PlacesNearbyInfoCard(
            title = stringResource(R.string.places_around_empty_title),
            message = stringResource(R.string.places_nearby_empty_message)
        )
        uiState.entries.isEmpty() && uiState.isZoomedOutTooFar -> PlacesNearbyInfoCard(
            title = stringResource(R.string.places_nearby_zoom_in_title),
            message = stringResource(R.string.places_nearby_zoom_in_message)
        )
    }
}

@Composable
private fun PlacesNearbySearchingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.PLACES_NEARBY_SEARCHING)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.places_nearby_searching),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlacesNearbyFailedCard(outage: app.openhikes.core.model.PlaceSearchOutage, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(PlaceCardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.places_search_failed_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = outageMessage(outage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)) {
                Text(text = stringResource(R.string.places_action_try_again))
            }
        }
    }
}

@Composable
private fun PlacesNearbyOutageRow(outage: app.openhikes.core.model.PlaceSearchOutage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painter = painterResource(OpenHikesIcons.Warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = outageMessage(outage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PlacesNearbyInfoCard(title: String, message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(PlaceCardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
