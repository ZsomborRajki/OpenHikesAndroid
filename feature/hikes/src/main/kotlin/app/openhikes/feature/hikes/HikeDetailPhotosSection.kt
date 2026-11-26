package app.openhikes.feature.hikes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.ui.components.HikePhotoStrip
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val PhotoRowMinHeight = 52.dp
private val PhotoSectionHorizontalPadding = 16.dp
private val PhotoRowVerticalPadding = 12.dp
private val PhotoRowIconSize = 20.dp

/**
 * Always-visible Photos section on [HikeDetailScreen] displaying chronological photo tiles,
 * *Find Photos of This Hike* entry, and dynamic anchoring status caption.
 */
@Composable
internal fun HikeDetailPhotosSection(
    photos: List<HikePhoto>,
    anchoredPhotoCount: Int,
    canMatchPhotos: Boolean,
    loadThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    onPhotoAction: (HikePhotoAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val caption = photosCaption(
        totalCount = photos.size,
        anchoredCount = anchoredPhotoCount,
        canMatchPhotos = canMatchPhotos
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HikeSectionCard(title = stringResource(R.string.hike_detail_section_photos)) {
            if (photos.isNotEmpty()) {
                HikePhotoStrip(
                    photos = photos,
                    loadThumbnailBytes = loadThumbnailBytes,
                    onPhotoClick = { photo -> onPhotoAction(HikePhotoAction.OpenPhoto(photo.id)) },
                    contentPadding = PaddingValues(
                        horizontal = PhotoSectionHorizontalPadding,
                        vertical = PhotoRowVerticalPadding
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = PhotoSectionHorizontalPadding)
                )
            }
            FindPhotosOfHikeRow(
                onClick = { onPhotoAction(HikePhotoAction.OpenPhotoDiscovery) }
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun FindPhotosOfHikeRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PhotoRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = PhotoSectionHorizontalPadding, vertical = PhotoRowVerticalPadding)
            .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_BUTTON)
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.PhotoLibrary),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(PhotoRowIconSize)
        )
        Text(
            text = stringResource(R.string.hike_detail_find_photos),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(PhotoRowIconSize)
        )
    }
}

@ReadOnlyComposable
@Composable
private fun photosCaption(totalCount: Int, anchoredCount: Int, canMatchPhotos: Boolean): String = when {
    totalCount == 0 && !canMatchPhotos -> stringResource(R.string.hike_detail_photos_caption_untimed)
    totalCount == 0 -> stringResource(R.string.hike_detail_photos_caption_can_match_empty)
    anchoredCount == totalCount -> stringResource(R.string.hike_detail_photos_caption_all_pinned)
    anchoredCount == 0 -> stringResource(R.string.hike_detail_photos_caption_none_pinned)
    else -> pluralStringResource(
        R.plurals.hike_detail_photos_caption_some_pinned,
        anchoredCount,
        anchoredCount,
        totalCount
    )
}
