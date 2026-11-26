package app.openhikes.feature.photos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.PhotoMatchEvidence
import app.openhikes.core.ui.components.PhotoBitmapDecoder
import app.openhikes.core.ui.components.PhotoPresentationFormatter
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GridCellMinSize = 104.dp
private val GridSpacing = 8.dp
private const val DISCOVERY_CELL_DECODE_MAX_EDGE_PX = 400
private val GridCellCornerRadius = 12.dp
private val SelectedBorderWidth = 3.dp
private val CheckBadgeSize = 22.dp
private val StatusIconSize = 44.dp
private val DiscoveryControlTouchTarget = 48.dp

@Composable
internal fun PhotoDiscoveryPhaseContent(
    phase: PhotoDiscoveryPhase,
    loadThumbnailBytes: suspend (String) -> ByteArray?,
    onAction: (PhotoDiscoveryAction) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    when (phase) {
        PhotoDiscoveryPhase.Searching -> DiscoveryProgressState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_SEARCHING,
            message = stringResource(R.string.photo_discovery_searching)
        )
        is PhotoDiscoveryPhase.Importing -> DiscoveryImportingState(
            completed = phase.completed,
            total = phase.total
        )
        PhotoDiscoveryPhase.Unsupported -> DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_UNSUPPORTED,
            iconRes = OpenHikesIcons.History,
            title = stringResource(R.string.photo_discovery_unsupported_title),
            body = stringResource(R.string.photo_discovery_unsupported_body)
        )
        PhotoDiscoveryPhase.ReadFailed -> DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_READ_FAILED,
            iconRes = OpenHikesIcons.Warning,
            title = stringResource(R.string.photo_discovery_read_failed_title),
            body = stringResource(R.string.photo_discovery_read_failed_body),
            actionLabel = stringResource(R.string.photos_action_try_again),
            actionTag = OpenHikesTestTags.PHOTO_DISCOVERY_RETRY_BUTTON,
            onAction = { onAction(PhotoDiscoveryAction.RetrySearch) }
        )
        PhotoDiscoveryPhase.AccessDenied -> DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_DENIED,
            iconRes = OpenHikesIcons.PhotoLibrary,
            title = stringResource(R.string.photo_discovery_denied_title),
            body = stringResource(R.string.photo_discovery_denied_body),
            actionLabel = stringResource(R.string.photo_discovery_open_settings),
            onAction = onOpenAppSettings
        )
        PhotoDiscoveryPhase.AccessRestricted -> DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_RESTRICTED,
            iconRes = OpenHikesIcons.PhotoLibrary,
            title = stringResource(R.string.photo_discovery_restricted_title),
            body = stringResource(R.string.photo_discovery_restricted_body)
        )
        is PhotoDiscoveryPhase.Empty -> DiscoveryEmptyPhase(
            isLimitedAccess = phase.isLimitedAccess,
            onSelectMore = { onAction(PhotoDiscoveryAction.SelectMorePhotos) }
        )
        is PhotoDiscoveryPhase.Results -> DiscoveryResultsGrid(
            results = phase,
            loadThumbnailBytes = loadThumbnailBytes,
            onToggle = { id -> onAction(PhotoDiscoveryAction.ToggleSelection(id)) }
        )
    }
}

@Composable
private fun DiscoveryEmptyPhase(isLimitedAccess: Boolean, onSelectMore: () -> Unit) {
    if (isLimitedAccess) {
        DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_EMPTY,
            iconRes = OpenHikesIcons.PhotoLibrary,
            title = stringResource(R.string.photo_discovery_limited_empty_title),
            body = stringResource(R.string.photo_discovery_limited_empty_body),
            actionLabel = stringResource(R.string.photo_discovery_select_more),
            actionTag = OpenHikesTestTags.PHOTO_DISCOVERY_SELECT_MORE_BUTTON,
            onAction = onSelectMore
        )
    } else {
        DiscoveryMessageState(
            tag = OpenHikesTestTags.PHOTO_DISCOVERY_EMPTY,
            iconRes = OpenHikesIcons.PhotoLibrary,
            title = stringResource(R.string.photo_discovery_empty_title),
            body = stringResource(R.string.photo_discovery_empty_body)
        )
    }
}

@Composable
private fun DiscoveryProgressState(tag: String, message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DiscoveryImportingState(completed: Int, total: Int) {
    val fraction = if (total > 0) completed.toFloat() / total.toFloat() else 0f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_IMPORTING)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.photo_discovery_importing, completed, total),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DiscoveryMessageState(
    tag: String,
    iconRes: Int,
    title: String,
    body: String,
    actionLabel: String? = null,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(StatusIconSize)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                val buttonModifier = Modifier.defaultMinSize(minHeight = DiscoveryControlTouchTarget)
                FilledTonalButton(
                    onClick = onAction,
                    modifier = if (actionTag != null) buttonModifier.testTag(actionTag) else buttonModifier
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DiscoveryResultsGrid(
    results: PhotoDiscoveryPhase.Results,
    loadThumbnailBytes: suspend (String) -> ByteArray?,
    onToggle: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinSize),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
        modifier = Modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_GRID)
    ) {
        itemsIndexed(
            items = results.matches,
            key = { _, match -> match.id }
        ) { index, match ->
            DiscoveryPhotoCell(
                index = index,
                match = match,
                isSelected = match.id in results.selectedIds,
                loadThumbnailBytes = loadThumbnailBytes,
                onClick = { onToggle(match.id) }
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "footnote") {
            Text(
                text = stringResource(R.string.photo_discovery_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun DiscoveryPhotoCell(
    index: Int,
    match: LibraryPhotoMatch,
    isSelected: Boolean,
    loadThumbnailBytes: suspend (String) -> ByteArray?,
    onClick: () -> Unit
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = match.id) {
        val bytes = loadThumbnailBytes(match.asset.localIdentifier)
        value = if (bytes != null) {
            withContext(Dispatchers.Default) {
                PhotoBitmapDecoder.decodeUpright(
                    bytes = bytes,
                    maxEdgePx = DISCOVERY_CELL_DECODE_MAX_EDGE_PX
                )
            }
        } else {
            null
        }
    }
    val timeText = PhotoPresentationFormatter.formatTimeOfDay(match.asset.createdAtEpochMillis)
    val evidenceText = evidenceDescription(match)
    val cellDescription = stringResource(R.string.photo_discovery_cell_description, timeText, evidenceText)

    Surface(
        shape = RoundedCornerShape(GridCellCornerRadius),
        border = if (isSelected) BorderStroke(SelectedBorderWidth, MaterialTheme.colorScheme.primary) else null,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(GridCellCornerRadius))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = cellDescription
                selected = isSelected
            }
            .testTag(OpenHikesTestTags.discoveredPhoto(index))
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            val decoded = bitmap
            if (decoded != null) {
                Image(
                    bitmap = decoded,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(OpenHikesIcons.Photo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DiscoverySelectionBadge(
                isSelected = isSelected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun DiscoverySelectionBadge(isSelected: Boolean, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(CheckBadgeSize)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)
            )
    ) {
        Icon(
            painter = painterResource(
                if (isSelected) OpenHikesIcons.CheckCircle else OpenHikesIcons.RadioButtonUnchecked
            ),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(CheckBadgeSize)
        )
    }
}

@Composable
internal fun PhotoDiscoveryBottomBar(
    results: PhotoDiscoveryPhase.Results,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (results.importFailed) {
                Text(
                    text = stringResource(R.string.photo_discovery_import_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_IMPORT_FAILED)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TextButton(
                    onClick = onToggleSelectAll,
                    modifier = Modifier
                        .defaultMinSize(minHeight = DiscoveryControlTouchTarget)
                        .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_SELECT_ALL_BUTTON)
                ) {
                    Text(
                        text = if (results.allSelected) {
                            stringResource(R.string.photo_discovery_deselect_all)
                        } else {
                            stringResource(R.string.photo_discovery_select_all)
                        }
                    )
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.photo_discovery_selection_count,
                        results.matches.size,
                        results.selectedCount,
                        results.matches.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(OpenHikesTestTags.PHOTO_DISCOVERY_SELECTION_COUNT)
                )
            }
        }
    }
}

@ReadOnlyComposable
@Composable
private fun evidenceDescription(match: LibraryPhotoMatch): String = when (match.evidence) {
    PhotoMatchEvidence.PLACE -> stringResource(R.string.photo_discovery_evidence_place)
    PhotoMatchEvidence.TIME_AND_PLACE -> stringResource(R.string.photo_discovery_evidence_time_and_place)
    PhotoMatchEvidence.WALK -> stringResource(R.string.photo_discovery_evidence_walk)
    PhotoMatchEvidence.TIME -> if (match.secondsFromFix < 1.0) {
        stringResource(R.string.photo_discovery_evidence_time_exact)
    } else {
        stringResource(
            R.string.photo_discovery_evidence_time_offset,
            HikeMetricsFormatter.formatDuration(match.secondsFromFix)
        )
    }
}
