package app.openhikes.feature.photos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.HikeId
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val DiscoveryTouchTarget = 48.dp

/**
 * Stateful route binding [PhotoDiscoveryViewModel] and Android media-read permission launchers to
 * [PhotoDiscoveryScreen].
 */
@Composable
fun PhotoDiscoveryRoute(
    hikeId: HikeId,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoDiscoveryViewModel = hiltViewModel<PhotoDiscoveryViewModel, PhotoDiscoveryViewModel.Factory>(
        key = "photo-discovery-${hikeId.value}"
    ) { factory -> factory.create(hikeId.value) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ObserveDiscoveryResume(onResumed = { viewModel.onAction(PhotoDiscoveryAction.LifecycleResumed) })
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onAction(PhotoDiscoveryAction.PermissionResultReceived)
    }

    LaunchedEffect(uiState.shouldClose) {
        if (uiState.shouldClose) onDone()
    }
    LaunchedEffect(uiState.shouldRequestPermissionsToken) {
        if (uiState.shouldRequestPermissionsToken > 0L) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
            )
            viewModel.onAction(PhotoDiscoveryAction.ConsumePermissionRequest)
        }
    }
    LaunchedEffect(uiState.shouldRequestLimitedMoreToken) {
        if (uiState.shouldRequestLimitedMoreToken > 0L) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            )
            viewModel.onAction(PhotoDiscoveryAction.ConsumeLimitedMoreRequest)
        }
    }

    PhotoDiscoveryScreen(
        uiState = uiState,
        loadThumbnailBytes = viewModel::loadDiscoveryThumbnail,
        onAction = viewModel::onAction,
        onDone = onDone,
        onOpenAppSettings = {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun ObserveDiscoveryResume(onResumed: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasObservedInitialResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasObservedInitialResume) {
                    onResumed()
                } else {
                    hasObservedInitialResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Stateless *Photos of This Hike* discovery review screen used by the app and screenshot tests.
 */
@Composable
fun PhotoDiscoveryScreen(
    uiState: PhotoDiscoveryUiState,
    loadThumbnailBytes: suspend (String) -> ByteArray?,
    onAction: (PhotoDiscoveryAction) -> Unit,
    onDone: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_SCREEN)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PhotoDiscoveryTopBar(
                phase = uiState.phase,
                onDone = onDone,
                onAddSelected = { onAction(PhotoDiscoveryAction.AddSelected) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                PhotoDiscoveryPhaseContent(
                    phase = uiState.phase,
                    loadThumbnailBytes = loadThumbnailBytes,
                    onAction = onAction,
                    onOpenAppSettings = onOpenAppSettings
                )
            }
            val results = uiState.phase as? PhotoDiscoveryPhase.Results
            if (results != null) {
                PhotoDiscoveryBottomBar(
                    results = results,
                    onToggleSelectAll = { onAction(PhotoDiscoveryAction.ToggleSelectAll) },
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun PhotoDiscoveryTopBar(phase: PhotoDiscoveryPhase, onDone: () -> Unit, onAddSelected: () -> Unit) {
    val results = phase as? PhotoDiscoveryPhase.Results
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        TextButton(
            onClick = onDone,
            modifier = Modifier
                .defaultMinSize(minHeight = DiscoveryTouchTarget)
                .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_DONE_BUTTON)
        ) {
            Text(text = stringResource(R.string.photo_discovery_done))
        }
        Text(
            text = stringResource(R.string.photo_discovery_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        if (results != null && results.matches.isNotEmpty()) {
            val count = results.selectedCount
            val label = if (count > 0) {
                stringResource(R.string.photo_discovery_add_label_compact, count)
            } else {
                stringResource(R.string.photo_discovery_add)
            }
            val spoken = if (count > 0) {
                stringResource(R.string.photo_discovery_add_count, count)
            } else {
                stringResource(R.string.photo_discovery_add)
            }
            FilledTonalButton(
                onClick = onAddSelected,
                enabled = count > 0,
                modifier = Modifier
                    .defaultMinSize(minHeight = DiscoveryTouchTarget)
                    .semantics { contentDescription = spoken }
                    .testTag(OpenHikesTestTags.PHOTO_DISCOVERY_ADD_BUTTON)
            ) {
                Text(text = label)
            }
        }
    }
}
