package app.openhikes.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.openhikes.R
import app.openhikes.core.map.PhotoCaptureAlert
import app.openhikes.core.map.PhotoCaptureRequest
import app.openhikes.core.map.PhotoCaptureRequestKind
import app.openhikes.core.model.HikeId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LIBRARY_PICK_ITEMS = 10
private val PhotoAlertTouchTarget = 48.dp

/**
 * Floating camera & photo-library chrome state exposed to [MapFloatingControls].
 */
@Immutable
data class MapPhotosChromeState(
    val showPhotoButtons: Boolean = false,
    val isBusy: Boolean = false,
    val onTakePhoto: () -> Unit = {},
    val onAddFromLibrary: () -> Unit = {}
)

@Immutable
internal data class PhotoCaptureHostCallbacks(
    val onConsumeRequest: (Long) -> Unit,
    val onCameraPhotoCaptured: (HikeId, String?, ByteArray, Long) -> Unit,
    val onCameraCaptureFailed: (PhotoCaptureAlert) -> Unit,
    val onLibraryPhotosPicked: (HikeId, String?, List<String>) -> Unit,
    val onDismissAlert: () -> Unit
)

/**
 * Shell-level host executing Android's system camera (`TakePicture`) and Photo Picker
 * (`PickMultipleVisualMedia`) contracts whenever any photo-capable route or floating control
 * enqueues a [PhotoCaptureRequest], persisting the pending target across configuration changes
 * and process recreation, and displaying Material 3 alerts on capture/import failure.
 */
@Composable
internal fun PhotoCaptureHost(
    pendingRequest: PhotoCaptureRequest?,
    alert: PhotoCaptureAlert?,
    callbacks: PhotoCaptureHostCallbacks
) {
    val launchCamera = rememberShellCameraLauncher(callbacks.onCameraPhotoCaptured, callbacks.onCameraCaptureFailed)
    val launchLibrary = rememberShellLibraryLauncher(callbacks.onLibraryPhotosPicked, callbacks.onCameraCaptureFailed)

    LaunchedEffect(pendingRequest?.token) {
        val request = pendingRequest ?: return@LaunchedEffect
        callbacks.onConsumeRequest(request.token)
        when (request.kind) {
            PhotoCaptureRequestKind.CAMERA -> launchCamera(request)
            PhotoCaptureRequestKind.LIBRARY -> launchLibrary(request)
        }
    }

    if (alert != null) {
        PhotoCaptureAlertDialog(alert = alert, onDismiss = callbacks.onDismissAlert)
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun rememberShellCameraLauncher(
    onCameraPhotoCaptured: (HikeId, String?, ByteArray, Long) -> Unit,
    onCameraCaptureFailed: (PhotoCaptureAlert) -> Unit
): (PhotoCaptureRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingHikeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val hikeId = pendingHikeId?.let(::HikeId)
        val placeId = pendingPlaceId
        val file = pendingFilePath?.let(::File)
        pendingHikeId = null
        pendingPlaceId = null
        pendingFilePath = null
        if (hikeId == null || file == null) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        if (!success) {
            file.delete()
        } else {
            val fallbackMillis = System.currentTimeMillis()
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull().also { file.delete() }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    onCameraPhotoCaptured(hikeId, placeId, bytes, fallbackMillis)
                } else {
                    onCameraCaptureFailed(PhotoCaptureAlert.CAMERA_SAVE_FAILED)
                }
            }
        }
    }

    return { request ->
        runCatching {
            val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
            val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
            pendingHikeId = request.subject.hikeId.value
            pendingPlaceId = request.subject.placeId
            pendingFilePath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraLauncher.launch(uri)
        }.onFailure {
            pendingFilePath?.let { path -> File(path).delete() }
            pendingHikeId = null
            pendingPlaceId = null
            pendingFilePath = null
            onCameraCaptureFailed(PhotoCaptureAlert.CAMERA_SAVE_FAILED)
        }
    }
}

@Composable
private fun rememberShellLibraryLauncher(
    onLibraryPhotosPicked: (HikeId, String?, List<String>) -> Unit,
    onCameraCaptureFailed: (PhotoCaptureAlert) -> Unit
): (PhotoCaptureRequest) -> Unit {
    var pendingHikeId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val libraryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_LIBRARY_PICK_ITEMS)
    ) { uris ->
        val hikeId = pendingHikeId?.let(::HikeId)
        val placeId = pendingPlaceId
        pendingHikeId = null
        pendingPlaceId = null
        if (hikeId != null && uris.isNotEmpty()) {
            onLibraryPhotosPicked(hikeId, placeId, uris.map { it.toString() })
        }
    }

    return { request ->
        runCatching {
            pendingHikeId = request.subject.hikeId.value
            pendingPlaceId = request.subject.placeId
            libraryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            pendingHikeId = null
            pendingPlaceId = null
            onCameraCaptureFailed(PhotoCaptureAlert.LIBRARY_IMPORT_FAILED)
        }
    }
}

@Composable
private fun PhotoCaptureAlertDialog(alert: PhotoCaptureAlert, onDismiss: () -> Unit) {
    val (titleRes, messageRes) = when (alert) {
        PhotoCaptureAlert.CAMERA_PERMISSION_DENIED ->
            R.string.photo_alert_permission_title to R.string.photo_alert_permission_message
        PhotoCaptureAlert.CAMERA_SAVE_FAILED ->
            R.string.photo_alert_camera_failed_title to R.string.photo_alert_camera_failed_message
        PhotoCaptureAlert.LIBRARY_IMPORT_FAILED ->
            R.string.photo_alert_library_failed_title to R.string.photo_alert_library_failed_message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = { Text(text = stringResource(messageRes)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = PhotoAlertTouchTarget)
            ) {
                Text(text = stringResource(R.string.photo_alert_dismiss))
            }
        }
    )
}
