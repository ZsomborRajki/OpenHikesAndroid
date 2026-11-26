package app.openhikes.feature.places

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.components.labelRes
import app.openhikes.core.ui.testing.OpenHikesTestTags
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_STAGED_LIBRARY_PICK = 10

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
    val photoLaunchers = rememberPlacePhotoStagingLaunchers(
        onSetBusy = viewModel::onSetStagingPhoto,
        onStageCameraPhoto = viewModel::onStageCameraPhoto,
        onStageLibraryPhotos = viewModel::onStageLibraryPhotos
    )

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
        onRetryFailedPhotos = {
            val currentLabel = uiState.symbol?.let { resources.getString(it.labelRes()) }.orEmpty()
            viewModel.onConfirmAdd(currentLabel)
        },
        onContinueWithoutFailedPhotos = viewModel::onContinueWithoutFailedPhotos,
        onDismissPhotoAttachError = viewModel::onDismissPhotoAttachError,
        onTakePhoto = photoLaunchers.onTakePhoto,
        onAddPhotos = photoLaunchers.onAddPhotos,
        onRemoveStagedPhoto = viewModel::onRemoveStagedPhoto,
        modifier = modifier
    )
}

private data class PlacePhotoStagingLaunchers(val onTakePhoto: () -> Unit, val onAddPhotos: () -> Unit)

@Composable
private fun rememberPlacePhotoStagingLaunchers(
    onSetBusy: (Boolean) -> Unit,
    onStageCameraPhoto: (ByteArray, Long?) -> Unit,
    onStageLibraryPhotos: (List<ByteArray>) -> Unit
): PlacePhotoStagingLaunchers {
    val onTakePhoto = rememberPlaceCameraStagingAction(onSetBusy, onStageCameraPhoto)
    val onAddPhotos = rememberPlaceLibraryStagingAction(onSetBusy, onStageLibraryPhotos)
    return PlacePhotoStagingLaunchers(onTakePhoto = onTakePhoto, onAddPhotos = onAddPhotos)
}

@Suppress("InjectDispatcher")
@Composable
internal fun rememberPlaceCameraStagingAction(
    onSetBusy: (Boolean) -> Unit,
    onStageCameraPhoto: (ByteArray, Long?) -> Unit,
    uriProvider: (android.content.Context, File) -> android.net.Uri = { ctx, file ->
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraPath?.let(::File)
        pendingCameraPath = null
        if (!success || file == null) {
            file?.delete()
            onSetBusy(false)
        } else {
            val fallbackMillis = System.currentTimeMillis()
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull().also { file.delete() }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    onStageCameraPhoto(bytes, fallbackMillis)
                } else {
                    onSetBusy(false)
                }
            }
        }
    }

    return {
        onSetBusy(true)
        runCatching {
            val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
            val file = File(dir, "place-${UUID.randomUUID()}.jpg")
            pendingCameraPath = file.absolutePath
            val uri = uriProvider(context, file)
            cameraLauncher.launch(uri)
        }.onFailure {
            pendingCameraPath?.let { path -> File(path).delete() }
            pendingCameraPath = null
            onSetBusy(false)
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun rememberPlaceLibraryStagingAction(
    onSetBusy: (Boolean) -> Unit,
    onStageLibraryPhotos: (List<ByteArray>) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_STAGED_LIBRARY_PICK)
    ) { uris ->
        if (uris.isEmpty()) {
            onSetBusy(false)
        } else {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                }
                onStageLibraryPhotos(loaded)
            }
        }
    }

    return {
        onSetBusy(true)
        runCatching {
            libraryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            onSetBusy(false)
        }
    }
}

@Suppress("LongParameterList")
@Composable
fun AddHikePlaceScreen(
    uiState: AddHikePlaceUiState,
    onNameChange: (String) -> Unit,
    onSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onNoteChange: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onDismissSaveRefusal: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryFailedPhotos: () -> Unit = {},
    onContinueWithoutFailedPhotos: () -> Unit = {},
    onDismissPhotoAttachError: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onAddPhotos: () -> Unit = {},
    onRemoveStagedPhoto: (String) -> Unit = {}
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
        AddPlaceTopBar(
            canAdd = uiState.canConfirmAdd,
            isSaving = uiState.isSaving,
            onCancel = onCancel,
            onAdd = onAdd
        )
        AddPlaceFormContent(
            uiState = uiState,
            headerTitle = headerTitle,
            namePlaceholder = namePlaceholder,
            onNameChange = onNameChange,
            onSymbolChange = onSymbolChange,
            onNoteChange = onNoteChange,
            onTakePhoto = onTakePhoto,
            onAddPhotos = onAddPhotos,
            onRemoveStagedPhoto = onRemoveStagedPhoto,
            modifier = Modifier.weight(1f)
        )
    }

    if (uiState.showSaveRefusal) {
        PlaceSaveRefusalDialog(onDismiss = onDismissSaveRefusal)
    }
    if (uiState.photoAttachFailureCount > 0) {
        PlacePhotoAttachFailureDialog(
            failedCount = uiState.photoAttachFailureCount,
            onRetry = onRetryFailedPhotos,
            onContinueWithout = onContinueWithoutFailedPhotos,
            onDismiss = onDismissPhotoAttachError
        )
    }
}

@Composable
private fun PlacePhotoAttachFailureDialog(
    failedCount: Int,
    onRetry: () -> Unit,
    onContinueWithout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.places_photo_attach_failed_title)) },
        text = {
            Text(
                text = pluralStringResource(
                    R.plurals.places_photo_attach_failed_message,
                    failedCount,
                    failedCount
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
            ) {
                Text(text = stringResource(R.string.places_action_try_again))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onContinueWithout,
                modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
            ) {
                Text(text = stringResource(R.string.places_photo_attach_continue_without))
            }
        }
    )
}

@Suppress("LongParameterList")
@Composable
private fun AddPlaceFormContent(
    uiState: AddHikePlaceUiState,
    headerTitle: String,
    namePlaceholder: String,
    onNameChange: (String) -> Unit,
    onSymbolChange: (TrailPlaceSymbol?) -> Unit,
    onNoteChange: (String) -> Unit,
    onTakePhoto: () -> Unit,
    onAddPhotos: () -> Unit,
    onRemoveStagedPhoto: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
        modifier = modifier
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
        StagedPlacePhotosSection(
            stagedPhotos = uiState.stagedPhotos,
            isBusy = uiState.isSaving || uiState.isStagingPhoto,
            onTakePhoto = onTakePhoto,
            onAddPhotos = onAddPhotos,
            onRemoveStagedPhoto = onRemoveStagedPhoto
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

@Composable
private fun AddPlaceTopBar(canAdd: Boolean, isSaving: Boolean, onCancel: () -> Unit, onAdd: () -> Unit) {
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
            enabled = canAdd,
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
