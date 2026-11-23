package app.openhikes.feature.hikes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.ui.components.HikeRow
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val HorizontalPadding = 16.dp
private val SearchChromeGap = 10.dp
private val ContentSpacing = 12.dp
private val CardCornerRadius = 24.dp
private val CardPadding = 20.dp
private val EmptyIconSize = 28.dp
private val TouchTargetMinSize = 48.dp
private val BannerCornerRadius = 16.dp
private val BannerPadding = 12.dp
private val ListBottomPadding = 20.dp

/**
 * Stateful hike library route inside the adaptive bottom sheet or side panel.
 */
@Composable
fun HikeLibraryRoute(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHike: (HikeId) -> Unit,
    onHikeDeleted: (HikeId) -> Unit,
    modifier: Modifier = Modifier,
    onImportGpxClick: (() -> Unit)? = null,
    viewModel: HikeLibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onImportGpxUri(it.toString()) }
    }

    val handleImportClick = onImportGpxClick ?: {
        importLauncher.launch(arrayOf("*/*"))
    }

    HikeLibraryScreen(
        uiState = uiState,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onClearSearch = onClearSearch,
        onSearchFocusChanged = onSearchFocusChanged,
        onOpenSettings = onOpenSettings,
        onImportGpxClick = handleImportClick,
        onOpenHike = onOpenHike,
        onRequestDelete = viewModel::onRequestDelete,
        onConfirmDelete = { viewModel.onConfirmDelete(onHikeDeleted) },
        onCancelDelete = viewModel::onCancelDelete,
        onDismissImportError = viewModel::onDismissImportError,
        onDismissActionError = viewModel::onDismissActionError,
        modifier = modifier
    )
}

/**
 * Stateless hike library screen shared across runtime, Compose tests, and screenshots.
 */
@Composable
fun HikeLibraryScreen(
    uiState: HikeLibraryUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onImportGpxClick: () -> Unit,
    onOpenHike: (HikeId) -> Unit,
    onRequestDelete: (HikeSummary) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissImportError: () -> Unit = {},
    onDismissActionError: () -> Unit = {}
) {
    val filteredHikes = remember(uiState.hikes, searchQuery) {
        filterHikes(uiState.hikes, searchQuery)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HorizontalPadding)
    ) {
        SearchAndSettingsRow(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onSearchFocusChanged = onSearchFocusChanged,
            onOpenSettings = onOpenSettings
        )
        Spacer(modifier = Modifier.height(ContentSpacing))
        LibraryActionsHeader(
            totalCount = uiState.hikes.size,
            isImporting = uiState.isImporting,
            onImportGpxClick = onImportGpxClick
        )
        uiState.importError?.let { error ->
            Spacer(modifier = Modifier.height(ContentSpacing))
            LibraryErrorBanner(
                message = importErrorText(error),
                onDismiss = onDismissImportError,
                tag = OpenHikesTestTags.HIKE_IMPORT_ERROR
            )
        }
        if (uiState.actionError != null) {
            Spacer(modifier = Modifier.height(ContentSpacing))
            LibraryErrorBanner(
                message = stringResource(R.string.hikes_delete_failed_error),
                onDismiss = onDismissActionError,
                tag = OpenHikesTestTags.HIKE_ACTION_ERROR
            )
        }
        Spacer(modifier = Modifier.height(ContentSpacing))
        HikeLibraryBody(
            uiState = uiState,
            searchQuery = searchQuery,
            filteredHikes = filteredHikes,
            onClearSearch = onClearSearch,
            onOpenHike = onOpenHike,
            onRequestDelete = onRequestDelete,
            modifier = Modifier.weight(1f)
        )
    }

    uiState.pendingDeleteHike?.let { hike ->
        DeleteHikeConfirmationDialog(
            hikeTitle = hike.displayTitle,
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete
        )
    }
}

@Composable
private fun HikeLibraryBody(
    uiState: HikeLibraryUiState,
    searchQuery: String,
    filteredHikes: List<HikeSummary>,
    onClearSearch: () -> Unit,
    onOpenHike: (HikeId) -> Unit,
    onRequestDelete: (HikeSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        searchQuery.isNotBlank() && filteredHikes.isEmpty() -> {
            Column(modifier = modifier.fillMaxWidth()) {
                SearchEmptyResultsCard(query = searchQuery, onClearSearch = onClearSearch)
            }
        }
        filteredHikes.isEmpty() -> {
            Column(modifier = modifier.fillMaxWidth()) {
                EmptyLibraryCard()
            }
        }
        else -> {
            LazyColumn(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag(OpenHikesTestTags.HIKE_LIST),
                contentPadding = PaddingValues(bottom = ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(ContentSpacing)
            ) {
                if (searchQuery.isNotBlank()) {
                    item(key = "your_hikes_header") {
                        Text(
                            text = stringResource(R.string.hikes_your_hikes_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .semantics { heading() }
                                .testTag(OpenHikesTestTags.HIKE_SEARCH_SECTION_HEADER)
                        )
                    }
                }
                items(
                    items = filteredHikes,
                    key = { it.id.value }
                ) { hike ->
                    HikeRow(
                        hike = hike,
                        distanceUnit = uiState.distanceUnit,
                        isSelected = hike.id == uiState.selectedHikeId,
                        walkPhase = if (hike.id == uiState.walkedHikeId) uiState.walkedPhase else null,
                        onClick = { onOpenHike(hike.id) },
                        onDeleteClick = { onRequestDelete(hike) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryActionsHeader(totalCount: Int, isImporting: Boolean, onImportGpxClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (isImporting) {
                stringResource(R.string.hikes_importing)
            } else {
                pluralStringResource(R.plurals.hikes_library_count, totalCount, totalCount)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(SearchChromeGap))
        FilledTonalButton(
            onClick = onImportGpxClick,
            enabled = !isImporting,
            modifier = Modifier
                .defaultMinSize(minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.IMPORT_GPX_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.UploadFile),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.hikes_import_gpx))
        }
    }
}

@Composable
private fun SearchAndSettingsRow(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SearchChromeGap)
    ) {
        SearchPillField(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onSearchFocusChanged = onSearchFocusChanged,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = onOpenSettings,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.SETTINGS_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Settings),
                contentDescription = stringResource(R.string.hikes_settings_description)
            )
        }
    }
}

@Composable
private fun SearchPillField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = modifier
    ) {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.hikes_search_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(OpenHikesIcons.Search),
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = onClearSearch,
                        modifier = Modifier
                            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                            .testTag(OpenHikesTestTags.CLEAR_SEARCH_BUTTON)
                    ) {
                        Icon(
                            painter = painterResource(OpenHikesIcons.Close),
                            contentDescription = stringResource(R.string.hikes_search_clear_description)
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    onSearchFocusChanged(focusState.isFocused)
                }
                .testTag(OpenHikesTestTags.MAP_SEARCH)
        )
    }
}

@Composable
private fun EmptyLibraryCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.HIKE_EMPTY_STATE)
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(SearchChromeGap)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Landscape),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(EmptyIconSize)
            )
            Text(
                text = stringResource(R.string.hikes_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.hikes_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchEmptyResultsCard(query: String, onClearSearch: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.HIKE_SEARCH_EMPTY_STATE)
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(SearchChromeGap)
        ) {
            Text(
                text = stringResource(R.string.hikes_search_no_results_title, query.trim()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.hikes_search_no_results_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onClearSearch,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(text = stringResource(R.string.hikes_search_clear_button))
            }
        }
    }
}

@Composable
private fun LibraryErrorBanner(message: String, onDismiss: () -> Unit, tag: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(BannerCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BannerPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(
                    text = stringResource(R.string.hikes_error_dismiss),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
internal fun DeleteHikeConfirmationDialog(hikeTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.HIKE_DELETE_DIALOG),
        title = { Text(text = stringResource(R.string.hikes_delete_dialog_title)) },
        text = { Text(text = stringResource(R.string.hikes_delete_dialog_body, hikeTitle)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.HIKE_DELETE_CONFIRM_BUTTON)
            ) {
                Text(
                    text = stringResource(R.string.hikes_delete_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.HIKE_DELETE_CANCEL_BUTTON)
            ) {
                Text(text = stringResource(R.string.hikes_delete_cancel))
            }
        }
    )
}

@Composable
private fun importErrorText(failure: GpxImportFailure): String = when (failure) {
    GpxImportFailure.MULTIPLE_TRACKS -> stringResource(R.string.hikes_import_error_multiple_tracks)
    GpxImportFailure.NO_USABLE_POINTS -> stringResource(R.string.hikes_import_error_no_usable_points)
    GpxImportFailure.TOO_LARGE -> stringResource(R.string.hikes_import_error_too_large)
    GpxImportFailure.TOO_SHORT -> stringResource(R.string.hikes_import_error_too_short)
    GpxImportFailure.UNREADABLE -> stringResource(R.string.hikes_import_error_unreadable)
}
