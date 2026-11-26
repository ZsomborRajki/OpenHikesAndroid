package app.openhikes.feature.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.components.labelRes
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
fun AddHikePlaceRoute(
    hikeId: HikeId,
    initialLatitude: Double,
    initialLongitude: Double,
    onAdded: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddHikePlaceViewModel = hiltViewModel<AddHikePlaceViewModel, AddHikePlaceViewModel.Factory>(
        key = "${hikeId.value}:$initialLatitude:$initialLongitude"
    ) { factory ->
        factory.create(hikeId.value, initialLatitude, initialLongitude)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current

    LaunchedEffect(uiState.addedPlaceId) {
        val createdId = uiState.addedPlaceId ?: return@LaunchedEffect
        viewModel.consumeAddedNavigation()
        onAdded(createdId)
    }

    AddHikePlaceScreen(
        uiState = uiState,
        onNameChange = viewModel::onNameChange,
        onSymbolChange = { next ->
            val prevLabel = uiState.symbol?.let { resources.getString(it.labelRes()) }.orEmpty()
            val nextLabel = next?.let { resources.getString(it.labelRes()) }.orEmpty()
            viewModel.onSymbolChange(next, prevLabel, nextLabel)
        },
        onNoteChange = viewModel::onNoteChange,
        onAdd = {
            val currentLabel = uiState.symbol?.let { resources.getString(it.labelRes()) }.orEmpty()
            viewModel.onConfirmAdd(currentLabel)
        },
        onCancel = onCancel,
        onDismissSaveRefusal = viewModel::onDismissSaveRefusal,
        modifier = modifier
    )
}

@Composable
fun AddHikePlaceScreen(
    uiState: AddHikePlaceUiState,
    onNameChange: (String) -> Unit,
    onSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onNoteChange: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onDismissSaveRefusal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val unnamedFallback = stringResource(app.openhikes.core.ui.R.string.place_unnamed_fallback)
    val headerTitle = uiState.displayName(unnamedFallback) { resources.getString(it.labelRes()) }
    val namePlaceholder = uiState.symbol?.let { stringResource(it.labelRes()) } ?: unnamedFallback

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER)
    ) {
        AddPlaceTopBar(isSaving = uiState.isSaving, onCancel = onCancel, onAdd = onAdd)
        Column(
            verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PlaceScreenPadding)
        ) {
            PlaceHeaderRow(
                symbol = uiState.symbol,
                title = headerTitle,
                subtitle = stringResource(R.string.places_provenance_own),
                titleTestTag = OpenHikesTestTags.HIKE_PLACE_ADDER_TITLE
            )
            Text(
                text = stringResource(R.string.places_adder_placement_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_HINT)
            )
            AddPlaceDetailsCard(
                uiState = uiState,
                namePlaceholder = namePlaceholder,
                onNameChange = onNameChange,
                onSymbolChange = onSymbolChange,
                onNoteChange = onNoteChange
            )
            TrailPlaceFactsAndLocationSection(
                facts = emptyList(),
                coordinate = uiState.coordinate,
                distanceUnit = DistanceUnit.METRIC,
                openStreetMapUrl = null,
                wikidataPlace = null,
                onOpenUrl = {}
            )
        }
    }

    if (uiState.showSaveRefusal) {
        PlaceSaveRefusalDialog(onDismiss = onDismissSaveRefusal)
    }
}

@Composable
private fun AddPlaceTopBar(isSaving: Boolean, onCancel: () -> Unit, onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        TextButton(
            onClick = onCancel,
            enabled = !isSaving,
            modifier = Modifier
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_CANCEL)
        ) {
            Text(text = stringResource(R.string.places_action_cancel))
        }
        Text(
            text = stringResource(R.string.places_screen_title_new_place),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Button(
            onClick = onAdd,
            enabled = !isSaving,
            modifier = Modifier
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_ADD)
        ) {
            Text(text = stringResource(R.string.places_action_add))
        }
    }
}

@Composable
private fun AddPlaceDetailsCard(
    uiState: AddHikePlaceUiState,
    namePlaceholder: String,
    onNameChange: (String) -> Unit,
    onSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onNoteChange: (String) -> Unit
) {
    PlaceSectionCard(title = stringResource(R.string.places_section_details)) {
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChange,
            label = { Text(text = stringResource(R.string.places_field_name)) },
            placeholder = { Text(text = namePlaceholder) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_NAME)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TrailPlaceKindPickerRow(selected = uiState.symbol, onSelect = onSymbolChange)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value = uiState.note,
            onValueChange = onNoteChange,
            label = { Text(text = stringResource(R.string.places_field_note)) },
            placeholder = { Text(text = stringResource(R.string.places_field_note_placeholder)) },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_NOTE)
        )
    }
}
