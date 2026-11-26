package app.openhikes.feature.places

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.ui.components.HikePhotoStrip
import app.openhikes.core.ui.components.HikePhotoTileCornerRadius
import app.openhikes.core.ui.components.HikePhotoTileSize
import app.openhikes.core.ui.components.HikePhotoTileSpacing
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PlacePhotoIconSize = 18.dp
private val RemoveBadgeSize = 28.dp

/**
 * In-memory photograph staged while composing a new place on [AddHikePlaceScreen].
 * Discarded cleanly if the hiker taps Cancel; filed under the newly created place on Add.
 */
@Immutable
data class StagedPlacePhoto(
    val id: String,
    val bytes: ByteArray,
    val capturedAtEpochMillis: Long?,
    val fromCamera: Boolean
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StagedPlacePhoto && id == other.id && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}

@Composable
internal fun PlacePhotosSection(
    photos: List<HikePhoto>,
    loadThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    onTakePhoto: () -> Unit,
    onAddPhotos: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    cameraTag: String,
    libraryTag: String,
    stripTag: String,
    showEmptyHint: Boolean,
    modifier: Modifier = Modifier
) {
    PlaceSectionCard(
        title = stringResource(R.string.places_section_photos),
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            PlacePhotoActionButtonsRow(
                onTakePhoto = onTakePhoto,
                onAddPhotos = onAddPhotos,
                cameraTag = cameraTag,
                libraryTag = libraryTag,
                enabled = true
            )
            if (photos.isNotEmpty()) {
                HikePhotoStrip(
                    photos = photos,
                    loadThumbnailBytes = loadThumbnailBytes,
                    onPhotoClick = { photo -> onOpenPhoto(photo.id) },
                    stripTestTag = stripTag,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (showEmptyHint) {
                Text(
                    text = stringResource(R.string.places_photos_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun StagedPlacePhotosSection(
    stagedPhotos: List<StagedPlacePhoto>,
    isBusy: Boolean,
    onTakePhoto: () -> Unit,
    onAddPhotos: () -> Unit,
    onRemoveStagedPhoto: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceSectionCard(
        title = stringResource(R.string.places_section_photos),
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            PlacePhotoActionButtonsRow(
                onTakePhoto = onTakePhoto,
                onAddPhotos = onAddPhotos,
                cameraTag = OpenHikesTestTags.HIKE_PLACE_ADDER_CAMERA,
                libraryTag = OpenHikesTestTags.HIKE_PLACE_ADDER_LIBRARY,
                enabled = !isBusy
            )
            if (stagedPhotos.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(HikePhotoTileSpacing),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_PHOTOS)
                ) {
                    items(items = stagedPhotos, key = { it.id }) { staged ->
                        StagedPhotoTile(
                            staged = staged,
                            onRemove = { onRemoveStagedPhoto(staged.id) }
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.places_adder_photos_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlacePhotoActionButtonsRow(
    onTakePhoto: () -> Unit,
    onAddPhotos: () -> Unit,
    cameraTag: String,
    libraryTag: String,
    enabled: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FilledTonalButton(
            onClick = onTakePhoto,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(cameraTag)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.PhotoCamera),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(PlacePhotoIconSize)
            )
            Text(text = stringResource(R.string.places_action_take_photo))
        }
        FilledTonalButton(
            onClick = onAddPhotos,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .testTag(libraryTag)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.PhotoLibrary),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(PlacePhotoIconSize)
            )
            Text(text = stringResource(R.string.places_action_add_photos))
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun StagedPhotoTile(staged: StagedPlacePhoto, onRemove: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = staged.id) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(staged.bytes, 0, staged.bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(HikePhotoTileSize)
            .clip(RoundedCornerShape(HikePhotoTileCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(RemoveBadgeSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .testTag(OpenHikesTestTags.HIKE_PLACE_ADDER_REMOVE_PHOTO)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.places_adder_remove_staged_photo),
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
