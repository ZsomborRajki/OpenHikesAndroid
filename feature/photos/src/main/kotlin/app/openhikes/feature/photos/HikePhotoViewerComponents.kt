package app.openhikes.feature.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoLicense
import app.openhikes.core.model.PhotoCreditPlacement
import app.openhikes.core.model.PhotoUnavailableReason
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val OverlayControlTouchTarget = 48.dp
private val BesideCreditMaxWidth = 128.dp
private val UnavailableIconSize = 48.dp
private val UnavailableMaxWidth = 320.dp
private const val SCRIM_ALPHA = 0.72f
private const val DISABLED_ICON_ALPHA = 0.35f

@Composable
internal fun PhotoViewerOverlayChrome(
    uiState: HikePhotoViewerUiState,
    creditPlacement: PhotoCreditPlacement,
    onStepPrevious: () -> Unit,
    onStepNext: () -> Unit,
    onOpenLicense: (HikePhoto) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = uiState.currentPhoto
    val hasMultiple = uiState.photos.size > 1
    if (creditPlacement == PhotoCreditPlacement.BESIDE) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            if (current != null) {
                PhotoCreditCapsule(
                    photo = current,
                    maxLines = 2,
                    onOpenLicense = { onOpenLicense(current) },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .widthIn(max = BesideCreditMaxWidth)
                )
            }
            PhotoStepControls(
                visible = hasMultiple,
                canStepPrevious = uiState.canStepPrevious,
                canStepNext = uiState.canStepNext,
                onStepPrevious = onStepPrevious,
                onStepNext = onStepNext,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            if (current != null) {
                PhotoCreditCapsule(
                    photo = current,
                    maxLines = 1,
                    onOpenLicense = { onOpenLicense(current) }
                )
            }
            PhotoStepControls(
                visible = hasMultiple,
                canStepPrevious = uiState.canStepPrevious,
                canStepNext = uiState.canStepNext,
                onStepPrevious = onStepPrevious,
                onStepNext = onStepNext
            )
        }
    }
}

@Composable
private fun PhotoStepControls(
    visible: Boolean,
    canStepPrevious: Boolean,
    canStepNext: Boolean,
    onStepPrevious: () -> Unit,
    onStepNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hiddenModifier = if (visible) {
        modifier
    } else {
        modifier
            .alpha(0f)
            .clearAndSetSemantics {}
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = hiddenModifier
    ) {
        StepIconButton(
            iconRes = OpenHikesIcons.ChevronLeft,
            contentDescription = stringResource(R.string.photos_action_previous),
            enabled = visible && canStepPrevious,
            testTag = OpenHikesTestTags.PREVIOUS_PHOTO_BUTTON,
            onClick = onStepPrevious
        )
        StepIconButton(
            iconRes = OpenHikesIcons.ChevronRight,
            contentDescription = stringResource(R.string.photos_action_next),
            enabled = visible && canStepNext,
            testTag = OpenHikesTestTags.NEXT_PHOTO_BUTTON,
            onClick = onStepNext
        )
    }
}

@Composable
private fun StepIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = SCRIM_ALPHA),
            contentColor = Color.White,
            disabledContainerColor = Color.Black.copy(alpha = DISABLED_ICON_ALPHA),
            disabledContentColor = Color.White.copy(alpha = DISABLED_ICON_ALPHA)
        ),
        modifier = Modifier
            .defaultMinSize(minWidth = OverlayControlTouchTarget, minHeight = OverlayControlTouchTarget)
            .testTag(testTag)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun PhotoCreditCapsule(
    photo: HikePhoto,
    maxLines: Int,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier
) {
    val author = photo.importedAuthorName?.trim()?.takeIf { it.isNotEmpty() }
    val license = photo.license
    if (license == null && author == null) return

    val label = when {
        author != null && license != null -> stringResource(R.string.photos_credit_by_license, author, license.name)
        author != null -> stringResource(R.string.photos_credit_by, author)
        license != null -> stringResource(R.string.photos_credit_license_only, license.name)
        else -> return
    }
    val tag = if (license != null) OpenHikesTestTags.PHOTO_LICENSE_CREDIT else OpenHikesTestTags.PHOTO_CREDIT

    if (license != null) {
        Surface(
            onClick = onOpenLicense,
            shape = CircleShape,
            color = Color.Black.copy(alpha = SCRIM_ALPHA),
            contentColor = Color.White,
            modifier = modifier
                .defaultMinSize(minHeight = OverlayControlTouchTarget)
                .testTag(tag)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = SCRIM_ALPHA),
            contentColor = Color.White,
            modifier = modifier.testTag(tag)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
internal fun PhotoLicenseDialog(license: HikePhotoLicense, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.photos_license_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onOpenUrl(license.sourceUrl) },
                    modifier = Modifier.defaultMinSize(minHeight = OverlayControlTouchTarget)
                ) {
                    Text(text = stringResource(R.string.photos_license_view_commons))
                }
                license.url?.let { url ->
                    TextButton(
                        onClick = { onOpenUrl(url) },
                        modifier = Modifier.defaultMinSize(minHeight = OverlayControlTouchTarget)
                    ) {
                        Text(text = stringResource(R.string.photos_license_about, license.name))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = OverlayControlTouchTarget)
            ) {
                Text(text = stringResource(R.string.photos_action_close))
            }
        }
    )
}

@Composable
internal fun PhotoUnavailableView(
    reason: PhotoUnavailableReason,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val descriptor = unavailableDescriptor(reason)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .widthIn(max = UnavailableMaxWidth)
            .padding(24.dp)
            .testTag(descriptor.tag)
    ) {
        Icon(
            painter = painterResource(descriptor.iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(UnavailableIconSize)
        )
        Text(
            text = stringResource(descriptor.titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(descriptor.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center
        )
        if (descriptor.showRetry) {
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier
                    .defaultMinSize(minHeight = OverlayControlTouchTarget)
                    .testTag(OpenHikesTestTags.PHOTO_RETRY_BUTTON)
            ) {
                Text(text = stringResource(R.string.photos_action_try_again))
            }
        }
        if (descriptor.showRemove) {
            OutlinedButton(
                onClick = onRemove,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .defaultMinSize(minHeight = OverlayControlTouchTarget)
                    .testTag(OpenHikesTestTags.PHOTO_REMOVE_BUTTON)
            ) {
                Text(text = stringResource(R.string.photos_action_remove))
            }
        }
    }
}

private data class UnavailableDescriptor(
    val tag: String,
    val iconRes: Int,
    val titleRes: Int,
    val bodyRes: Int,
    val showRetry: Boolean,
    val showRemove: Boolean
)

private fun unavailableDescriptor(reason: PhotoUnavailableReason): UnavailableDescriptor = when (reason) {
    PhotoUnavailableReason.PLACE_ONLY -> UnavailableDescriptor(
        tag = OpenHikesTestTags.PHOTO_PLACE_ONLY,
        iconRes = OpenHikesIcons.Place,
        titleRes = R.string.photos_unavailable_place_only_title,
        bodyRes = R.string.photos_unavailable_place_only_body,
        showRetry = false,
        showRemove = false
    )
    PhotoUnavailableReason.NOT_ON_THIS_DEVICE -> UnavailableDescriptor(
        tag = OpenHikesTestTags.PHOTO_NOT_ON_THIS_DEVICE,
        iconRes = OpenHikesIcons.CloudOff,
        titleRes = R.string.photos_unavailable_not_on_device_title,
        bodyRes = R.string.photos_unavailable_not_on_device_body,
        showRetry = false,
        showRemove = false
    )
    PhotoUnavailableReason.NOT_DOWNLOADED -> UnavailableDescriptor(
        tag = OpenHikesTestTags.PHOTO_NOT_DOWNLOADED,
        iconRes = OpenHikesIcons.CloudDownload,
        titleRes = R.string.photos_unavailable_not_downloaded_title,
        bodyRes = R.string.photos_unavailable_not_downloaded_body,
        showRetry = true,
        showRemove = false
    )
    PhotoUnavailableReason.GONE_FROM_COMMONS -> UnavailableDescriptor(
        tag = OpenHikesTestTags.PHOTO_GONE_FROM_COMMONS,
        iconRes = OpenHikesIcons.BrokenImage,
        titleRes = R.string.photos_unavailable_gone_title,
        bodyRes = R.string.photos_unavailable_gone_body,
        showRetry = false,
        showRemove = true
    )
    PhotoUnavailableReason.UNREADABLE -> UnavailableDescriptor(
        tag = OpenHikesTestTags.PHOTO_UNAVAILABLE,
        iconRes = OpenHikesIcons.Warning,
        titleRes = R.string.photos_unavailable_unreadable_title,
        bodyRes = R.string.photos_unavailable_unreadable_body,
        showRetry = true,
        showRemove = true
    )
}
