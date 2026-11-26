package app.openhikes.core.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PhotoMapPin
import app.openhikes.core.ui.testing.OpenHikesTestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val HikePhotoTileSize: Dp = 76.dp
val HikePhotoTileSpacing: Dp = 8.dp
val HikePhotoTileCornerRadius: Dp = 12.dp
private val HikePhotoPlaceholderIconSize: Dp = 24.dp

/**
 * Shared formatting helpers for photo timestamps, pin accessibility labels, and strip tiles.
 */
object PhotoPresentationFormatter {
    fun formatTimestamp(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(formatter)
    }

    fun formatTimeOfDay(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(formatter)
    }

    fun pinSpokenLabel(pin: PhotoMapPin): String {
        val timestamp = formatTimestamp(pin.photo.capturedAtEpochMillis)
        return if (pin.count > 1) {
            "${pin.count} photos taken here, first on $timestamp"
        } else {
            "Photo taken $timestamp"
        }
    }

    fun pinSubtitle(pin: PhotoMapPin): String? = if (pin.count > 1) "First of ${pin.count} photos taken here" else null

    fun pinPreviewLabel(pin: PhotoMapPin): String {
        val timestamp = formatTimestamp(pin.photo.capturedAtEpochMillis)
        val base = if (pin.count > 1) {
            "Open photo taken $timestamp, first of ${pin.count} taken here"
        } else {
            "Open photo taken $timestamp"
        }
        return if (pin.photo.recordsPlaceOnly) "$base, no photo in the file" else base
    }

    fun stripTileDescription(indexZeroBased: Int, totalCount: Int, photo: HikePhoto): String {
        val placement = if (photo.isAnchored) "pinned to the trail" else "not pinned to the trail"
        val base = "Photo ${indexZeroBased + 1} of $totalCount, $placement"
        return if (photo.recordsPlaceOnly) "$base, no photo in the file" else base
    }
}

/**
 * Horizontal lazy thumbnail strip (`76 dp × 76 dp`, `12 dp` radius) shared by Hike Detail and
 * Place Detail screens.
 */
@Composable
fun HikePhotoStrip(
    photos: List<HikePhoto>,
    loadThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    onPhotoClick: (HikePhoto) -> Unit,
    modifier: Modifier = Modifier,
    stripTestTag: String = OpenHikesTestTags.HIKE_PHOTO_STRIP,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(HikePhotoTileSpacing),
        contentPadding = contentPadding,
        modifier = modifier.testTag(stripTestTag)
    ) {
        itemsIndexed(items = photos, key = { _, photo -> photo.id }) { index, photo ->
            HikePhotoThumbnailTile(
                photo = photo,
                loadThumbnailBytes = loadThumbnailBytes,
                description = PhotoPresentationFormatter.stripTileDescription(index, photos.size, photo),
                onClick = { onPhotoClick(photo) }
            )
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
fun HikePhotoThumbnailTile(
    photo: HikePhoto,
    loadThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tileSize: Dp = HikePhotoTileSize
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = photo.id) {
        val bytes = loadThumbnailBytes(photo)
        value = if (bytes != null) {
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        } else {
            null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(tileSize)
            .clip(RoundedCornerShape(HikePhotoTileCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .testTag(OpenHikesTestTags.hikePhotoTile(photo.id))
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
            val iconRes = if (photo.recordsPlaceOnly) OpenHikesIcons.Place else OpenHikesIcons.Photo
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HikePhotoPlaceholderIconSize)
            )
        }
    }
}
