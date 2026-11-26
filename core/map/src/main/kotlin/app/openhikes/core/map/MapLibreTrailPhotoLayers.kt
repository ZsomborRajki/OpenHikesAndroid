package app.openhikes.core.map

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.Position

private val PhotoPinTouchTargetSize = 48.dp
private val PhotoPinVisualDiameter = 32.dp
private val PhotoPinIconSize = 18.dp
private val PhotoPinBorderWidth = 2.dp
private val PhotoPinShadowElevation = 4.dp
private val PhotoCalloutWidth = 180.dp
private val PhotoCalloutHeight = 135.dp
private val PhotoCalloutCornerRadius = 10.dp
private val PhotoCalloutCardPadding = 6.dp
private val PhotoCalloutVerticalOffset = (-24).dp
private val PhotoCalloutPlaceholderIconSize = 28.dp
private val PhotoCalloutCardElevation = 6.dp

@Composable
internal fun MapOverlayScope.TrailPhotosInteractiveOverlay(
    trailPhotosOverlayState: StateFlow<MapTrailPhotosOverlay?>,
    interactionHandlerProvider: () -> TrailPhotosMapInteractionHandler?
) {
    val overlay by trailPhotosOverlayState.collectAsStateWithLifecycle()
    val current = overlay?.takeIf { it.hasVisibleContent } ?: return
    val currentHandlerProvider by rememberUpdatedState(interactionHandlerProvider)

    current.pins.forEach { item ->
        MapPhotoPinMarker(
            item = item,
            onClick = { currentHandlerProvider()?.onTapPhotoPin(item.id) }
        )
    }

    current.selectedPin?.let { selected ->
        MapPhotoPinCallout(
            item = selected,
            handlerProvider = { currentHandlerProvider() },
            onOpenPhoto = { currentHandlerProvider()?.onTapPhotoCallout(selected.pin.photo.id) }
        )
    }
}

@Composable
private fun MapOverlayScope.MapPhotoPinMarker(item: MapPhotoPinItem, onClick: () -> Unit) {
    val pinColor = parseHexColorOrDefault(item.tintHex, MaterialTheme.colorScheme.primary)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .placedAt(
                position = Position(
                    longitude = item.coordinate.longitude,
                    latitude = item.coordinate.latitude
                ),
                alignment = Alignment.Center
            )
            .size(PhotoPinTouchTargetSize)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = item.spokenLabel
                role = Role.Button
            }
            .testTag(OpenHikesTestTags.PHOTO_PIN)
    ) {
        Surface(
            color = pinColor,
            contentColor = Color.White,
            shape = CircleShape,
            border = BorderStroke(PhotoPinBorderWidth, Color.White),
            shadowElevation = PhotoPinShadowElevation,
            modifier = Modifier.size(PhotoPinVisualDiameter)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(OpenHikesIcons.PhotoCamera),
                    contentDescription = null,
                    modifier = Modifier.size(PhotoPinIconSize)
                )
            }
        }
    }
}

@Composable
private fun MapOverlayScope.MapPhotoPinCallout(
    item: MapPhotoPinItem,
    handlerProvider: () -> TrailPhotosMapInteractionHandler?,
    onOpenPhoto: () -> Unit
) {
    val thumbnail = rememberDecodedCalloutThumbnail(item = item, handlerProvider = handlerProvider)
    Surface(
        shape = RoundedCornerShape(PhotoCalloutCornerRadius + PhotoCalloutCardPadding),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = PhotoCalloutCardElevation,
        modifier = Modifier
            .placedAt(
                position = Position(
                    longitude = item.coordinate.longitude,
                    latitude = item.coordinate.latitude
                ),
                alignment = Alignment.BottomCenter
            )
            .offset(y = PhotoCalloutVerticalOffset)
            .clickable(role = Role.Button, onClick = onOpenPhoto)
            .semantics {
                contentDescription = item.previewAccessibilityLabel
                role = Role.Button
            }
            .testTag(OpenHikesTestTags.PHOTO_PIN_PREVIEW)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(PhotoCalloutCardPadding)
        ) {
            CalloutThumbnailBox(
                thumbnail = thumbnail,
                isPlaceOnly = item.pin.photo.recordsPlaceOnly
            )
            item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun rememberDecodedCalloutThumbnail(
    item: MapPhotoPinItem,
    handlerProvider: () -> TrailPhotosMapInteractionHandler?
): ImageBitmap? {
    val state by produceState<ImageBitmap?>(initialValue = null, key1 = item.pin.photo.id) {
        val bytes = handlerProvider()?.loadThumbnailBytes(item.pin.photo)
        value = if (bytes != null) {
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        } else {
            null
        }
    }
    return state
}

@Composable
private fun CalloutThumbnailBox(thumbnail: ImageBitmap?, isPlaceOnly: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = PhotoCalloutWidth, height = PhotoCalloutHeight)
            .clip(RoundedCornerShape(PhotoCalloutCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            val placeholderIcon = if (isPlaceOnly) OpenHikesIcons.Place else OpenHikesIcons.Photo
            Icon(
                painter = painterResource(placeholderIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(PhotoCalloutPlaceholderIconSize)
            )
        }
    }
}
