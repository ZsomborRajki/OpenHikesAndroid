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
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceAroundEntry
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
fun PlacesAroundRoute(
    hikeId: HikeId,
    onBack: () -> Unit,
    onAddOwnPlace: (GeoCoordinate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlacesAroundViewModel = hiltViewModel<PlacesAroundViewModel, PlacesAroundViewModel.Factory>(
        key = hikeId.value
    ) { factory ->
        factory.create(hikeId.value)
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

    PlacesAroundScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectReach = viewModel::onSelectReach,
        onToggleSymbol = viewModel::onToggleSymbol,
        onRetrySearch = viewModel::onRetrySearch,
        onOpenPlace = viewModel::onSelectPlace,
        onAddPlace = viewModel::onAddPlace,
        onCloseCard = viewModel::onCloseCard,
        onShareCard = { title ->
            val place = uiState.selectedCard?.row?.place ?: return@PlacesAroundScreen
            sharePlaceIntent(context, title, place.coordinate, place.osm?.url)
        },
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        onDismissSaveRefusal = viewModel::onDismissSaveRefusal,
        modifier = modifier
    )
}

@Suppress("LongParameterList")
@Composable
fun PlacesAroundScreen(
    uiState: PlacesAroundUiState,
    onBack: () -> Unit,
    onSelectReach: (TrailPlaceReach) -> Unit,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    onRetrySearch: () -> Unit,
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
            .testTag(OpenHikesTestTags.PLACES_AROUND_SCREEN)
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
            PlacesAroundListContent(
                uiState = uiState,
                onBack = onBack,
                onSelectReach = onSelectReach,
                onToggleSymbol = onToggleSymbol,
                onRetrySearch = onRetrySearch,
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
private fun PlacesAroundListContent(
    uiState: PlacesAroundUiState,
    onBack: () -> Unit,
    onSelectReach: (TrailPlaceReach) -> Unit,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    onRetrySearch: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onAddPlace: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PlaceTopBar(
            title = stringResource(R.string.places_screen_title_around),
            backTestTag = OpenHikesTestTags.PLACES_AROUND_BACK,
            onBack = onBack
        )
        LazyColumn(
            contentPadding = PaddingValues(PlaceScreenPadding),
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item(key = "filters") {
                PlacesAroundFiltersHeader(
                    shownSymbols = uiState.shownSymbols,
                    reach = uiState.reach,
                    distanceUnit = uiState.distanceUnit,
                    onToggleSymbol = onToggleSymbol,
                    onSelectReach = onSelectReach
                )
            }
            item(key = "status") { PlacesAroundStatusBlock(uiState = uiState, onRetry = onRetrySearch) }
            if (uiState.listing.onTrail.isNotEmpty()) {
                item(key = "on_trail") {
                    PlacesAroundSectionCard(
                        title = stringResource(R.string.places_around_section_on_trail),
                        entries = uiState.listing.onTrail,
                        distanceUnit = uiState.distanceUnit,
                        sectionTag = OpenHikesTestTags.PLACES_AROUND_ON_TRAIL,
                        onOpenPlace = onOpenPlace,
                        onAddPlace = onAddPlace
                    )
                }
            }
            if (uiState.listing.nearby.isNotEmpty()) {
                item(key = "nearby") {
                    PlacesAroundSectionCard(
                        title = stringResource(R.string.places_around_section_nearby),
                        entries = uiState.listing.nearby,
                        distanceUnit = uiState.distanceUnit,
                        sectionTag = OpenHikesTestTags.PLACES_AROUND_NEARBY,
                        onOpenPlace = onOpenPlace,
                        onAddPlace = onAddPlace
                    )
                }
            }
            item(key = "footer") {
                Text(
                    text = stringResource(R.string.places_around_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlacesAroundFiltersHeader(
    shownSymbols: Set<TrailPlaceSymbol>,
    reach: TrailPlaceReach,
    distanceUnit: DistanceUnit,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    onSelectReach: (TrailPlaceReach) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TrailPlaceKindChipsRow(shownSymbols = shownSymbols, onToggleSymbol = onToggleSymbol)
        PlacesReachSelector(selected = reach, distanceUnit = distanceUnit, onSelect = onSelectReach)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlacesReachSelector(
    selected: TrailPlaceReach,
    distanceUnit: DistanceUnit,
    onSelect: (TrailPlaceReach) -> Unit
) {
    val options = listOf(TrailPlaceReach.ON_TRAIL, TrailPlaceReach.NEARBY, TrailPlaceReach.AROUND)
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.PLACES_AROUND_REACH)
    ) {
        options.forEachIndexed { index, reach ->
            val shape = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val label = when (reach) {
                TrailPlaceReach.ON_TRAIL -> stringResource(R.string.places_around_reach_on_trail)
                TrailPlaceReach.NEARBY,
                TrailPlaceReach.AROUND -> HikeMetricsFormatter.formatDistance(reach.meters, distanceUnit)
            }
            ToggleButton(
                checked = selected == reach,
                onCheckedChange = { if (it) onSelect(reach) },
                shapes = shape,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = PlaceRowMinHeight)
                    .testTag(OpenHikesTestTags.placesAroundReachOption(reach.name.lowercase()))
            ) {
                Text(text = label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PlacesAroundStatusBlock(uiState: PlacesAroundUiState, onRetry: () -> Unit) {
    when (uiState.phase) {
        PlacesAroundPhase.SEARCHING -> PlacesAroundSearchingRow()
        PlacesAroundPhase.FAILED -> PlacesAroundFailedCard(outage = uiState.outage, onRetry = onRetry)
        PlacesAroundPhase.FOUND -> {
            uiState.outage?.let { PlacesAroundOutageInlineRow(outage = it) }
            if (uiState.listing.isEmpty) {
                PlacesAroundEmptyCard()
            }
        }
    }
}

@Composable
private fun PlacesAroundSearchingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.PLACES_AROUND_SEARCHING)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.places_around_searching),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlacesAroundFailedCard(outage: app.openhikes.core.model.PlaceSearchOutage?, onRetry: () -> Unit) {
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
            if (outage != null) {
                Text(
                    text = outageMessage(outage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)) {
                Text(text = stringResource(R.string.places_action_try_again))
            }
        }
    }
}

@Composable
private fun PlacesAroundOutageInlineRow(outage: app.openhikes.core.model.PlaceSearchOutage) {
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
private fun PlacesAroundEmptyCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(PlaceCardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.places_around_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.places_around_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlacesAroundSectionCard(
    title: String,
    entries: List<TrailPlaceAroundEntry>,
    distanceUnit: DistanceUnit,
    sectionTag: String,
    onOpenPlace: (String) -> Unit,
    onAddPlace: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(PlaceCardCornerRadius),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(sectionTag)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    TrailPlaceAroundListRow(
                        entry = entry,
                        distanceUnit = distanceUnit,
                        onOpen = { onOpenPlace(entry.id) },
                        onAdd = { onAddPlace(entry.id) }
                    )
                }
            }
        }
    }
}
