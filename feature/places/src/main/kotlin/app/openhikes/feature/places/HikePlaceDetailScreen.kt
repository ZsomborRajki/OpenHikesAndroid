package app.openhikes.feature.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.components.labelRes
import app.openhikes.core.ui.components.rememberDisplayName
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
fun HikePlaceDetailRoute(
    hikeId: HikeId,
    placeId: String,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onCollapseSheet: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HikePlaceDetailViewModel = hiltViewModel<HikePlaceDetailViewModel, HikePlaceDetailViewModel.Factory>(
        key = "${hikeId.value}:$placeId"
    ) { factory ->
        factory.create(hikeId.value, placeId)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.isLoaded, uiState.row) {
        if (uiState.isLoaded && uiState.row == null) {
            onBack()
        }
    }

    LaunchedEffect(uiState.pendingOpenPlaceId) {
        val nextPlaceId = uiState.pendingOpenPlaceId ?: return@LaunchedEffect
        viewModel.consumeOpenPlace()
        onOpenPlace(nextPlaceId)
    }

    HikePlaceDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onShowOnMap = {
            onCollapseSheet()
            viewModel.onShowOnMap()
        },
        onShare = { title ->
            val place = uiState.row?.place ?: return@HikePlaceDetailScreen
            sharePlaceIntent(context, title, place.coordinate, place.osm?.url)
        },
        onStartEdit = viewModel::onStartEdit,
        onEditNameChange = viewModel::onEditNameChange,
        onEditSymbolChange = viewModel::onEditSymbolChange,
        onEditNoteChange = viewModel::onEditNoteChange,
        onSaveEdit = viewModel::onSaveEdit,
        onCancelEdit = viewModel::onCancelEdit,
        onRequestRemove = viewModel::onRequestRemove,
        onConfirmRemove = viewModel::onConfirmRemove,
        onCancelRemove = viewModel::onCancelRemove,
        onDismissSaveRefusal = viewModel::onDismissSaveRefusal,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        modifier = modifier
    )
}

@Suppress("LongParameterList")
@Composable
fun HikePlaceDetailScreen(
    uiState: HikePlaceDetailUiState,
    onBack: () -> Unit,
    onShowOnMap: () -> Unit,
    onShare: (String) -> Unit,
    onStartEdit: () -> Unit,
    onEditNameChange: (String) -> Unit,
    onEditSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onEditNoteChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRequestRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onDismissSaveRefusal: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val row = uiState.row
    val title = row?.place?.rememberDisplayName().orEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.HIKE_PLACE_SCREEN)
    ) {
        if (uiState.isEditing) {
            HikePlaceEditContent(
                name = uiState.editName,
                symbol = uiState.editSymbol,
                note = uiState.editNote,
                onNameChange = onEditNameChange,
                onSymbolChange = onEditSymbolChange,
                onNoteChange = onEditNoteChange,
                onSave = onSaveEdit,
                onCancel = onCancelEdit
            )
        } else {
            PlaceDetailTopBar(canEdit = row?.place?.isHikersOwn == true, onBack = onBack, onStartEdit = onStartEdit)
            if (row == null) {
                PlaceDetailMissingState(modifier = Modifier.weight(1f))
            } else {
                HikePlaceDetailBody(
                    row = row,
                    title = title,
                    uiState = uiState,
                    onShowOnMap = onShowOnMap,
                    onShare = { onShare(title) },
                    onRequestRemove = onRequestRemove,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    HikePlaceDetailDialogs(
        uiState = uiState,
        title = title,
        onConfirmRemove = onConfirmRemove,
        onCancelRemove = onCancelRemove,
        onDismissSaveRefusal = onDismissSaveRefusal
    )
}

@Composable
private fun PlaceDetailTopBar(canEdit: Boolean, onBack: () -> Unit, onStartEdit: () -> Unit) {
    PlaceTopBar(
        title = stringResource(R.string.places_screen_title_place),
        backTestTag = OpenHikesTestTags.HIKE_PLACE_BACK,
        onBack = onBack,
        trailing = {
            if (canEdit) {
                IconButton(
                    onClick = onStartEdit,
                    modifier = Modifier
                        .defaultMinSize(minWidth = PlaceRowMinHeight, minHeight = PlaceRowMinHeight)
                        .testTag(OpenHikesTestTags.HIKE_PLACE_EDIT)
                ) {
                    Icon(
                        painter = painterResource(OpenHikesIcons.Edit),
                        contentDescription = stringResource(R.string.places_action_edit)
                    )
                }
            }
        }
    )
}

@Composable
private fun PlaceDetailMissingState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(PlaceScreenPadding)
    ) {
        Text(
            text = stringResource(R.string.places_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HikePlaceDetailBody(
    row: TrailPlaceRow,
    title: String,
    uiState: HikePlaceDetailUiState,
    onShowOnMap: () -> Unit,
    onShare: () -> Unit,
    onRequestRemove: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val place = row.place
    val subtitle = buildPlaceDetailSubtitle(row = row, distanceUnit = uiState.distanceUnit)

    Column(
        verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PlaceScreenPadding)
    ) {
        PlaceHeaderRow(
            symbol = place.symbolKind,
            title = title,
            subtitle = subtitle,
            titleTestTag = OpenHikesTestTags.HIKE_PLACE_TITLE
        )
        PlaceDetailActionsRow(onShowOnMap = onShowOnMap, onShare = onShare)
        if (place.note.isNotBlank()) {
            Text(
                text = place.note,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OpenHikesTestTags.HIKE_PLACE_NOTE)
            )
        }
        TrailPlaceFactsAndLocationSection(
            facts = place.osm?.facts.orEmpty(),
            coordinate = place.coordinate,
            distanceUnit = uiState.distanceUnit,
            openStreetMapUrl = place.osm?.url,
            wikidataPlace = uiState.wikidataPlace,
            onOpenUrl = onOpenUrl
        )
        PlaceDetailRemoveButton(onRequestRemove = onRequestRemove)
    }
}

@Composable
private fun PlaceDetailActionsRow(onShowOnMap: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onShowOnMap,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.HIKE_PLACE_SHOW_ON_MAP)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Map),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(18.dp)
            )
            Text(text = stringResource(R.string.places_action_show_on_map))
        }
        FilledTonalButton(
            onClick = onShare,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.HIKE_PLACE_SHARE)
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

@Composable
private fun PlaceDetailRemoveButton(onRequestRemove: () -> Unit) {
    TextButton(
        onClick = onRequestRemove,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .testTag(OpenHikesTestTags.HIKE_PLACE_REMOVE)
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Delete),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 6.dp)
                .size(18.dp)
        )
        Text(text = stringResource(R.string.places_action_remove))
    }
}

@ReadOnlyComposable
@Composable
private fun buildPlaceDetailSubtitle(row: TrailPlaceRow, distanceUnit: app.openhikes.core.model.DistanceUnit): String {
    val place = row.place
    val kindLabel = if (place.name.isNotBlank()) {
        place.symbolKind?.let { stringResource(it.labelRes()) }
    } else {
        null
    }
    val alongText = row.anchor?.let {
        HikeMetricsFormatter.formatDistance(it.distanceAlongRouteMeters, distanceUnit)
    }
    val provenance = if (place.isHikersOwn) {
        stringResource(R.string.places_provenance_own)
    } else {
        stringResource(R.string.places_provenance_osm)
    }
    return listOfNotNull(kindLabel, alongText, provenance).joinToString(" · ")
}

@Composable
private fun HikePlaceDetailDialogs(
    uiState: HikePlaceDetailUiState,
    title: String,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onDismissSaveRefusal: () -> Unit
) {
    if (uiState.showRemoveDialog) {
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(text = stringResource(R.string.places_remove_dialog_title, title)) },
            text = { Text(text = stringResource(R.string.places_remove_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = onConfirmRemove,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .defaultMinSize(minHeight = PlaceRowMinHeight)
                        .testTag(OpenHikesTestTags.HIKE_PLACE_REMOVE_CONFIRM)
                ) {
                    Text(text = stringResource(R.string.places_action_remove))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onCancelRemove,
                    modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
                ) {
                    Text(text = stringResource(R.string.places_action_cancel))
                }
            }
        )
    }
    if (uiState.showSaveRefusal) {
        PlaceSaveRefusalDialog(onDismiss = onDismissSaveRefusal)
    }
}

@Composable
private fun HikePlaceEditContent(
    name: String,
    symbol: TrailPlaceSymbol?,
    note: String,
    onNameChange: (String) -> Unit,
    onSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val placeholder = if (symbol != null) {
        stringResource(symbol.labelRes())
    } else {
        stringResource(app.openhikes.core.ui.R.string.place_unnamed_fallback)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HikePlaceEditTopBar(onCancel = onCancel, onSave = onSave)
        Column(
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PlaceScreenPadding)
        ) {
            PlaceSectionCard(title = stringResource(R.string.places_section_details)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(text = stringResource(R.string.places_field_name)) },
                    placeholder = { Text(text = placeholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag(OpenHikesTestTags.HIKE_PLACE_NAME_FIELD)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TrailPlaceKindPickerRow(selected = symbol, onSelect = onSymbolChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text(text = stringResource(R.string.places_field_note)) },
                    placeholder = { Text(text = stringResource(R.string.places_field_note_placeholder)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag(OpenHikesTestTags.HIKE_PLACE_NOTE_FIELD)
                )
            }
        }
    }
}

@Composable
private fun HikePlaceEditTopBar(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
        ) {
            Text(text = stringResource(R.string.places_action_cancel))
        }
        Text(
            text = stringResource(R.string.places_screen_title_edit_place),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Button(
            onClick = onSave,
            modifier = Modifier
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.HIKE_PLACE_SAVE)
        ) {
            Text(text = stringResource(R.string.places_action_save))
        }
    }
}
