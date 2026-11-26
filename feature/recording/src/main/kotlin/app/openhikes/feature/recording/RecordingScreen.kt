package app.openhikes.feature.recording

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RouteSegment
import app.openhikes.core.ui.components.HikePhotoStrip
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stateful destination entry for live trail recording, wiring permissions, keep-screen-awake,
 * and one-shot save/discard navigation outcomes.
 */
@Composable
fun RecordingRoute(
    onBack: () -> Unit,
    onSaved: (HikeId) -> Unit,
    onDiscarded: (HikeId?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlace: (HikeId, String) -> Unit = { _, _ -> },
    onOpenPhoto: (HikeId, String) -> Unit = { _, _ -> },
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RecordingRouteEffects(
        uiState = uiState,
        onPermissionResult = { grantedFine ->
            viewModel.onAction(RecordingAction.PermissionResult(grantedFine = grantedFine))
        },
        onConsumeNavigation = viewModel::consumeNavigation,
        onSaved = onSaved,
        onDiscarded = onDiscarded,
        onConsumeOpenPlace = viewModel::consumePendingOpenPlace,
        onOpenPlace = onOpenPlace,
        onConsumeOpenPhoto = viewModel::consumePendingOpenPhoto,
        onOpenPhoto = onOpenPhoto
    )
    RecordingScreen(
        uiState = uiState,
        statsFlow = viewModel.stats,
        elapsedSecondsProvider = viewModel::currentElapsedSeconds,
        onBack = onBack,
        onAction = viewModel::onAction,
        onOpenPlace = { placeId ->
            uiState.activeHikeId?.let { hikeId -> onOpenPlace(hikeId, placeId) }
        },
        onOpenPhoto = { photoId ->
            uiState.activeHikeId?.let { hikeId -> onOpenPhoto(hikeId, photoId) }
        },
        loadPhotoThumbnailBytes = viewModel::loadPhotoThumbnailBytes,
        modifier = modifier
    )
}

@Suppress("LongParameterList")
@Composable
private fun RecordingRouteEffects(
    uiState: RecordingUiState,
    onPermissionResult: (Boolean) -> Unit,
    onConsumeNavigation: () -> Unit,
    onSaved: (HikeId) -> Unit,
    onDiscarded: (HikeId?) -> Unit,
    onConsumeOpenPlace: () -> Unit,
    onOpenPlace: (HikeId, String) -> Unit,
    onConsumeOpenPhoto: () -> Unit,
    onOpenPhoto: (HikeId, String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onPermissionResult(results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    LaunchedEffect(uiState.requestLocationPermission) {
        if (!uiState.requestLocationPermission) return@LaunchedEffect
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine) {
            onPermissionResult(true)
        } else {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
    LaunchedEffect(uiState.savedHikeId, uiState.discardCompleted) {
        uiState.savedHikeId?.let { hikeId ->
            onConsumeNavigation()
            onSaved(hikeId)
        }
        if (uiState.discardCompleted) {
            val discardedId = uiState.discardedHikeId
            onConsumeNavigation()
            onDiscarded(discardedId)
        }
    }
    LaunchedEffect(uiState.pendingOpenPlaceId, uiState.activeHikeId) {
        val placeId = uiState.pendingOpenPlaceId ?: return@LaunchedEffect
        val hikeId = uiState.activeHikeId ?: return@LaunchedEffect
        onConsumeOpenPlace()
        onOpenPlace(hikeId, placeId)
    }
    LaunchedEffect(uiState.pendingOpenPhotoId, uiState.activeHikeId) {
        val photoId = uiState.pendingOpenPhotoId ?: return@LaunchedEffect
        val hikeId = uiState.activeHikeId ?: return@LaunchedEffect
        onConsumeOpenPhoto()
        onOpenPhoto(hikeId, photoId)
    }
    DisposableEffect(view, uiState.keepScreenAwake, uiState.session.isActive) {
        view.keepScreenOn = uiState.keepScreenAwake && uiState.session.isActive
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Stateless live recording screen shared across runtime, Compose UI tests, previews, and
 * screenshot tests.
 */
@Suppress("LongParameterList")
@Composable
fun RecordingScreen(
    uiState: RecordingUiState,
    statsFlow: StateFlow<RecordingStatsSnapshot>,
    elapsedSecondsProvider: () -> Double,
    onBack: () -> Unit,
    onAction: (RecordingAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlace: (String) -> Unit = {},
    onOpenPhoto: (String) -> Unit = {},
    loadPhotoThumbnailBytes: suspend (HikePhoto) -> ByteArray? = { null }
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.RECORDING_SCREEN)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            addRecordingCoreItems(
                uiState = uiState,
                statsFlow = statsFlow,
                elapsedSecondsProvider = elapsedSecondsProvider,
                onBack = onBack,
                onAction = onAction
            )
            addRecordingKeepsakeItems(
                uiState = uiState,
                loadPhotoThumbnailBytes = loadPhotoThumbnailBytes,
                onOpenPhoto = onOpenPhoto,
                onOpenPlace = onOpenPlace
            )
        }
        RecordingDialogs(uiState = uiState, onAction = onAction)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.addRecordingCoreItems(
    uiState: RecordingUiState,
    statsFlow: StateFlow<RecordingStatsSnapshot>,
    elapsedSecondsProvider: () -> Double,
    onBack: () -> Unit,
    onAction: (RecordingAction) -> Unit
) {
    item(key = "header") {
        RecordingHeaderBar(session = uiState.session, statsFlow = statsFlow, onBack = onBack)
    }
    item(key = "recovery") {
        RecordingRecoveryNotice(
            recoveryState = uiState.session.recoveryState,
            distanceUnit = uiState.distanceUnit,
            onDismiss = { onAction(RecordingAction.DismissRecoveryNotice) }
        )
    }
    uiState.session.failure?.let { failure ->
        item(key = "failure") {
            RecordingFailureBanner(
                failure = failure,
                canRetrySave = uiState.session.canRetrySave,
                onAction = onAction
            )
        }
    }
    item(key = "conditions") { RecordingConditionsNotice(session = uiState.session) }
    item(key = "controls") { RecordingControlsBar(session = uiState.session, onAction = onAction) }
    if (uiState.session.phase != RecordingPhase.IDLE) {
        item(key = "stats") {
            RecordingStatsSection(
                phase = uiState.session.phase,
                statsFlow = statsFlow,
                distanceUnit = uiState.distanceUnit,
                elapsedSecondsProvider = elapsedSecondsProvider
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.addRecordingKeepsakeItems(
    uiState: RecordingUiState,
    loadPhotoThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    onOpenPhoto: (String) -> Unit,
    onOpenPlace: (String) -> Unit
) {
    if (uiState.photos.isNotEmpty()) {
        item(key = "photos") {
            RecordingPhotosSection(
                photos = uiState.photos,
                loadThumbnailBytes = loadPhotoThumbnailBytes,
                onOpenPhoto = onOpenPhoto
            )
        }
    }
    if (uiState.places.isNotEmpty()) {
        item(key = "places") {
            RecordingPlacesSection(
                places = uiState.places,
                distanceUnit = uiState.distanceUnit,
                onOpenPlace = onOpenPlace
            )
        }
    }
}

@Composable
private fun RecordingPhotosSection(
    photos: List<HikePhoto>,
    loadThumbnailBytes: suspend (HikePhoto) -> ByteArray?,
    onOpenPhoto: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.recording_photos_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HikePhotoStrip(
                photos = photos,
                loadThumbnailBytes = loadThumbnailBytes,
                onPhotoClick = { photo -> onOpenPhoto(photo.id) },
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Suppress("UnusedPrivateFunction")
@Preview(showBackground = true)
@Composable
private fun RecordingScreenActivePreview() {
    val statsFlow = remember {
        MutableStateFlow(
            RecordingStatsSnapshot(
                distanceMeters = 3_420.0,
                recordedDurationSeconds = 2_820.0,
                movingSeconds = 2_640.0,
                elevationGainMeters = 245.0,
                averageSpeedMps = 1.29,
                recentSpeedMps = 1.45,
                horizontalAccuracyMeters = 4.0,
                pointCount = 128,
                segments = listOf(
                    RouteSegment(distanceMeters = 2_000.0, durationSeconds = 1_620.0, stopSeconds = 180.0),
                    RouteSegment(distanceMeters = 1_420.0, durationSeconds = 1_020.0)
                )
            )
        )
    }
    OpenHikesTheme(dynamicColor = false) {
        RecordingScreen(
            uiState = RecordingUiState(
                session = RecordingSessionState(
                    phase = RecordingPhase.RECORDING,
                    sessionId = "preview-session"
                )
            ),
            statsFlow = statsFlow,
            elapsedSecondsProvider = { 2_820.0 },
            onBack = {},
            onAction = {}
        )
    }
}

@Suppress("UnusedPrivateFunction")
@Preview(showBackground = true)
@Composable
private fun RecordingScreenRetrySavePreview() {
    val statsFlow = remember {
        MutableStateFlow(
            RecordingStatsSnapshot(
                distanceMeters = 1_240.0,
                recordedDurationSeconds = 900.0,
                movingSeconds = 850.0,
                pointCount = 34
            )
        )
    }
    OpenHikesTheme(dynamicColor = false) {
        RecordingScreen(
            uiState = RecordingUiState(
                session = RecordingSessionState(
                    phase = RecordingPhase.FAILED,
                    sessionId = "preview-failed",
                    failure = RecordingFailure.Save("Database busy"),
                    canRetrySave = true
                )
            ),
            statsFlow = statsFlow,
            elapsedSecondsProvider = { 900.0 },
            onBack = {},
            onAction = {}
        )
    }
}
