package app.openhikes.shell

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.map.PhotoCaptureRequest
import app.openhikes.core.map.PhotoCaptureRequestKind
import app.openhikes.core.map.PhotoCaptureSubject
import app.openhikes.core.model.HikeId
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoCaptureHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cameraCaptureResultDeliversToSavedTargetAfterStateRestorationAndDeletesTempFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val captureDir = File(context.cacheDir, "camera_captures").apply { deleteRecursively() }
        val registry = DeferredActivityResultRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val restorationTester = StateRestorationTester(composeRule)
        var pendingRequest by mutableStateOf<PhotoCaptureRequest?>(null)
        var capturedTarget: Triple<HikeId, String?, ByteArray>? = null
        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x42)

        restorationTester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PhotoCaptureHost(
                    pendingRequest = pendingRequest,
                    alert = null,
                    callbacks = PhotoCaptureHostCallbacks(
                        onConsumeRequest = { pendingRequest = null },
                        onCameraPhotoCaptured = { hikeId, placeId, bytes, _ ->
                            capturedTarget = Triple(hikeId, placeId, bytes)
                        },
                        onCameraCaptureFailed = {},
                        onLibraryPhotosPicked = { _, _, _ -> },
                        onDismissAlert = {}
                    )
                )
            }
        }

        val hikeId = HikeId("hike-restore")
        pendingRequest = PhotoCaptureRequest(
            token = 1L,
            kind = PhotoCaptureRequestKind.CAMERA,
            subject = PhotoCaptureSubject(
                routeKey = "place-detail",
                depth = 2,
                hikeId = hikeId,
                placeId = "place-7"
            )
        )
        composeRule.waitForIdle()
        val requestCode = requireNotNull(registry.lastRequestCode)
        val launchedUri = registry.lastInput as Uri
        val tempFile = File(captureDir, requireNotNull(launchedUri.lastPathSegment))
        tempFile.writeBytes(jpegPayload)
        assertThat(tempFile.exists()).isTrue()

        // Simulate Activity recreation while the external camera app is open.
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        // Deliver TakePicture success after recreation.
        composeRule.runOnUiThread {
            registry.dispatchDeferredResult(requestCode, true)
        }
        composeRule.waitUntil(5_000L) {
            shadowOf(context.mainLooper).idle()
            capturedTarget != null
        }

        assertThat(capturedTarget?.first).isEqualTo(hikeId)
        assertThat(capturedTarget?.second).isEqualTo("place-7")
        assertThat(capturedTarget?.third).isEqualTo(jpegPayload)
        assertThat(tempFile.exists()).isFalse()
    }

    @Test
    fun libraryPickerResultDeliversToSavedTargetAfterStateRestoration() {
        val registry = DeferredActivityResultRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val restorationTester = StateRestorationTester(composeRule)
        var pendingRequest by mutableStateOf<PhotoCaptureRequest?>(null)
        var pickedResult: Triple<HikeId, String?, List<String>>? = null

        restorationTester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PhotoCaptureHost(
                    pendingRequest = pendingRequest,
                    alert = null,
                    callbacks = PhotoCaptureHostCallbacks(
                        onConsumeRequest = { pendingRequest = null },
                        onCameraPhotoCaptured = { _, _, _, _ -> },
                        onCameraCaptureFailed = {},
                        onLibraryPhotosPicked = { hikeId, placeId, uris ->
                            pickedResult = Triple(hikeId, placeId, uris)
                        },
                        onDismissAlert = {}
                    )
                )
            }
        }

        val hikeId = HikeId("hike-picker")
        pendingRequest = PhotoCaptureRequest(
            token = 2L,
            kind = PhotoCaptureRequestKind.LIBRARY,
            subject = PhotoCaptureSubject(
                routeKey = "hike-detail",
                depth = 1,
                hikeId = hikeId,
                placeId = "place-9"
            )
        )
        composeRule.waitForIdle()
        val requestCode = requireNotNull(registry.lastRequestCode)

        // Simulate Activity recreation while the system Photo Picker is open.
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        val uris = listOf("content://media/picker/101".toUri(), "content://media/picker/102".toUri())
        composeRule.runOnUiThread {
            registry.dispatchDeferredResult(requestCode, uris)
        }
        composeRule.waitForIdle()

        assertThat(pickedResult?.first).isEqualTo(hikeId)
        assertThat(pickedResult?.second).isEqualTo("place-9")
        assertThat(pickedResult?.third).containsExactly(
            "content://media/picker/101",
            "content://media/picker/102"
        ).inOrder()
    }

    private class DeferredActivityResultRegistry : ActivityResultRegistry() {
        var lastRequestCode: Int? = null
            private set
        var lastInput: Any? = null
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            lastRequestCode = requestCode
            lastInput = input
        }

        fun <O> dispatchDeferredResult(requestCode: Int, result: O) {
            dispatchResult(requestCode, result)
        }
    }
}
