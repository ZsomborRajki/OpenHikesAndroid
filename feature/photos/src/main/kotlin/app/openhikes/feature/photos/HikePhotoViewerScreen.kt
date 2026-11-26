package app.openhikes.feature.photos

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.data.PreparedSharePhoto
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PhotoCreditPlacement
import app.openhikes.core.ui.components.PhotoBitmapDecoder
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private val ViewerTouchTarget = 48.dp
private val ToolbarDestructiveGap = 16.dp
private const val DISPLAY_MAX_EDGE_PX = 2688

/**
 * Stateful route binding [HikePhotoViewerViewModel] to [HikePhotoViewerScreen].
 */
@Composable
fun HikePhotoViewerRoute(
    hikeId: HikeId,
    startPhotoId: String,
    onBack: () -> Unit,
    onRevealMap: () -> Unit,
    onExpandFullHeight: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HikePhotoViewerViewModel = hiltViewModel<HikePhotoViewerViewModel, HikePhotoViewerViewModel.Factory>(
        key = "photo-viewer-${hikeId.value}-$startPhotoId"
    ) { factory -> factory.create(hikeId.value, startPhotoId) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState.shouldClose) {
        if (uiState.shouldClose) onBack()
    }
    LaunchedEffect(uiState.pendingRevealMapToken) {
        if (uiState.pendingRevealMapToken > 0L) {
            onRevealMap()
            viewModel.onAction(HikePhotoViewerAction.ConsumeRevealMap)
        }
    }
    LaunchedEffect(uiState.pendingExpandFullHeightToken) {
        if (uiState.pendingExpandFullHeightToken > 0L) {
            onExpandFullHeight()
            viewModel.onAction(HikePhotoViewerAction.ConsumeExpandFullHeight)
        }
    }
    LaunchedEffect(uiState.pendingSharePhoto) {
        val prepared = uiState.pendingSharePhoto ?: return@LaunchedEffect
        sharePreparedPhoto(context, prepared)
        viewModel.onAction(HikePhotoViewerAction.ConsumeShareFile)
    }

    HikePhotoViewerScreen(
        uiState = uiState,
        loadPageContent = viewModel::loadPageContent,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        modifier = modifier
    )
}

/**
 * Stateless dark full-screen swipeable photograph viewer used by the app and screenshot tests.
 */
@Composable
fun HikePhotoViewerScreen(
    uiState: HikePhotoViewerUiState,
    loadPageContent: suspend (HikePhoto) -> PhotoPageContent,
    onAction: (HikePhotoViewerAction) -> Unit,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicenseDialogFor by remember { mutableStateOf<HikePhoto?>(null) }

    Surface(
        color = Color.Black,
        contentColor = Color.White,
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.PHOTO_VIEWER)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val creditPlacement = PhotoCreditPlacement.resolve(
                viewportWidth = maxWidth.value,
                viewportHeight = maxHeight.value
            )

            PhotoViewerPagerBody(
                uiState = uiState,
                loadPageContent = loadPageContent,
                onSelectPhoto = { onAction(HikePhotoViewerAction.SelectPhoto(it)) },
                onTapSurface = { onAction(HikePhotoViewerAction.TapPhotoSurface) },
                onRequestDelete = { onAction(HikePhotoViewerAction.RequestDelete) }
            )

            PhotoViewerTopBar(
                uiState = uiState,
                onBack = onBack,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )

            PhotoViewerOverlayChrome(
                uiState = uiState,
                creditPlacement = creditPlacement,
                onStepPrevious = { onAction(HikePhotoViewerAction.StepPrevious) },
                onStepNext = { onAction(HikePhotoViewerAction.StepNext) },
                onOpenLicense = { photo -> showLicenseDialogFor = photo },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }

    PhotoViewerDialogs(
        uiState = uiState,
        licenseDialogPhoto = showLicenseDialogFor,
        onDismissLicense = { showLicenseDialogFor = null },
        onOpenUrl = onOpenUrl,
        onAction = onAction
    )
}

@Composable
private fun PhotoViewerTopBar(
    uiState: HikePhotoViewerUiState,
    onBack: () -> Unit,
    onAction: (HikePhotoViewerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = uiState.currentPhoto
    val titleText = if (uiState.photos.isNotEmpty() && uiState.currentIndex >= 0) {
        stringResource(R.string.photos_viewer_position_title, uiState.currentIndex + 1, uiState.photos.size)
    } else {
        stringResource(R.string.photos_viewer_fallback_title)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = ViewerTouchTarget, minHeight = ViewerTouchTarget)
                .testTag(OpenHikesTestTags.PHOTO_VIEWER_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.photos_nav_back),
                tint = Color.White
            )
        }
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (current != null) {
            PhotoViewerTopBarActions(
                isAnchored = current.isAnchored,
                canShare = uiState.canShareCurrent,
                onAction = onAction
            )
        }
    }
}

@Composable
private fun PhotoViewerTopBarActions(
    isAnchored: Boolean,
    canShare: Boolean,
    onAction: (HikePhotoViewerAction) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isAnchored) {
            IconButton(
                onClick = { onAction(HikePhotoViewerAction.ShowOnMap) },
                modifier = Modifier
                    .defaultMinSize(minWidth = ViewerTouchTarget, minHeight = ViewerTouchTarget)
                    .testTag(OpenHikesTestTags.PHOTO_SHOW_ON_MAP_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Place),
                    contentDescription = stringResource(R.string.photos_action_show_on_map),
                    tint = Color.White
                )
            }
        }
        if (canShare) {
            IconButton(
                onClick = { onAction(HikePhotoViewerAction.ShareCurrent) },
                modifier = Modifier
                    .defaultMinSize(minWidth = ViewerTouchTarget, minHeight = ViewerTouchTarget)
                    .testTag(OpenHikesTestTags.PHOTO_SHARE_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Share),
                    contentDescription = stringResource(R.string.photos_action_share),
                    tint = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.width(ToolbarDestructiveGap))
        IconButton(
            onClick = { onAction(HikePhotoViewerAction.RequestDelete) },
            modifier = Modifier
                .defaultMinSize(minWidth = ViewerTouchTarget, minHeight = ViewerTouchTarget)
                .testTag(OpenHikesTestTags.PHOTO_DELETE_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Delete),
                contentDescription = stringResource(R.string.photos_action_delete),
                tint = MaterialTheme.colorScheme.errorContainer
            )
        }
    }
}

@Composable
private fun PhotoViewerPagerBody(
    uiState: HikePhotoViewerUiState,
    loadPageContent: suspend (HikePhoto) -> PhotoPageContent,
    onSelectPhoto: (String) -> Unit,
    onTapSurface: () -> Unit,
    onRequestDelete: () -> Unit
) {
    if (uiState.photos.isEmpty()) return
    val targetPage = uiState.currentIndex.coerceIn(0, uiState.photos.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = targetPage,
        pageCount = { uiState.photos.size }
    )

    LaunchedEffect(targetPage, uiState.photos.size) {
        if (pagerState.currentPage != targetPage && targetPage in uiState.photos.indices) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState, uiState.photos) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                uiState.photos.getOrNull(page)?.let { photo ->
                    onSelectPhoto(photo.id)
                }
            }
    }

    HorizontalPager(
        state = pagerState,
        key = { index -> uiState.photos[index].id },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onTapSurface) {
                detectTapGestures(onTap = { onTapSurface() })
            }
    ) { page ->
        val photo = uiState.photos[page]
        PhotoViewerPage(
            photo = photo,
            loadPageContent = loadPageContent,
            onRequestDelete = onRequestDelete
        )
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun PhotoViewerPage(
    photo: HikePhoto,
    loadPageContent: suspend (HikePhoto) -> PhotoPageContent,
    onRequestDelete: () -> Unit
) {
    var retryCount by remember(photo.id) { mutableIntStateOf(0) }
    val pageState by produceState<PhotoPageState>(
        initialValue = PhotoPageState.Loading,
        key1 = photo.id,
        key2 = retryCount
    ) {
        value = when (val loaded = loadPageContent(photo)) {
            PhotoPageContent.Loading -> PhotoPageState.Loading
            is PhotoPageContent.Unavailable -> PhotoPageState.Unavailable(loaded.reason)
            is PhotoPageContent.Ready -> {
                val decoded = withContext(Dispatchers.Default) {
                    decodeDisplayBitmap(loaded.bytes)
                }
                if (decoded != null) {
                    PhotoPageState.BitmapReady(decoded)
                } else {
                    PhotoPageState.Unavailable(app.openhikes.core.model.PhotoUnavailableReason.UNREADABLE)
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        when (val current = pageState) {
            PhotoPageState.Loading -> CircularProgressIndicator(color = Color.White)
            is PhotoPageState.BitmapReady -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Photograph" }
            )
            is PhotoPageState.Unavailable -> PhotoUnavailableView(
                reason = current.reason,
                onRetry = { retryCount += 1 },
                onRemove = onRequestDelete
            )
        }
    }
}

private sealed interface PhotoPageState {
    data object Loading : PhotoPageState

    data class BitmapReady(val bitmap: ImageBitmap) : PhotoPageState

    data class Unavailable(val reason: app.openhikes.core.model.PhotoUnavailableReason) : PhotoPageState
}

@Composable
private fun PhotoViewerDialogs(
    uiState: HikePhotoViewerUiState,
    licenseDialogPhoto: HikePhoto?,
    onDismissLicense: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAction: (HikePhotoViewerAction) -> Unit
) {
    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onAction(HikePhotoViewerAction.DismissDelete) },
            modifier = Modifier.testTag(OpenHikesTestTags.PHOTO_DELETE_DIALOG),
            title = { Text(text = stringResource(R.string.photos_delete_dialog_title)) },
            text = { Text(text = stringResource(R.string.photos_delete_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(HikePhotoViewerAction.ConfirmDelete) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = ViewerTouchTarget)
                        .testTag(OpenHikesTestTags.PHOTO_DELETE_CONFIRM)
                ) {
                    Text(
                        text = stringResource(R.string.photos_delete_dialog_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(HikePhotoViewerAction.DismissDelete) },
                    modifier = Modifier.defaultMinSize(minHeight = ViewerTouchTarget)
                ) {
                    Text(text = stringResource(R.string.photos_action_cancel))
                }
            }
        )
    }

    licenseDialogPhoto?.license?.let { license ->
        PhotoLicenseDialog(
            license = license,
            onOpenUrl = onOpenUrl,
            onDismiss = onDismissLicense
        )
    }

    uiState.errorMessage?.let { error ->
        val messageRes = when (error) {
            PhotoViewerError.SHARE_FAILED -> R.string.photos_share_failed
            PhotoViewerError.DELETE_FAILED -> R.string.photos_delete_failed
        }
        AlertDialog(
            onDismissRequest = { onAction(HikePhotoViewerAction.DismissError) },
            text = { Text(text = stringResource(messageRes)) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(HikePhotoViewerAction.DismissError) },
                    modifier = Modifier.defaultMinSize(minHeight = ViewerTouchTarget)
                ) {
                    Text(text = stringResource(R.string.photos_action_close))
                }
            }
        )
    }
}

private fun decodeDisplayBitmap(bytes: ByteArray): ImageBitmap? =
    PhotoBitmapDecoder.decodeUpright(bytes, DISPLAY_MAX_EDGE_PX)

private fun sharePreparedPhoto(context: android.content.Context, prepared: PreparedSharePhoto) {
    runCatching {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, prepared.file, prepared.displayName)
        val mime = when (prepared.file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.photos_share_chooser_title))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
