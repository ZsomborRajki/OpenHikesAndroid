package app.openhikes.feature.places

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
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
class AddHikePlaceCameraRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stagedCameraCaptureSurvivesStateRestorationAndDeletesTempFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = DeferredActivityResultRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val restorationTester = StateRestorationTester(composeRule)
        var capturedFile: File? = null
        var stagedBytes: ByteArray? = null
        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x55)

        restorationTester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val launchCamera = rememberPlaceCameraStagingAction(
                    onSetBusy = {},
                    onStageCameraPhoto = { bytes, _ -> stagedBytes = bytes },
                    uriProvider = { _, file ->
                        capturedFile = file
                        Uri.fromFile(file)
                    }
                )
                TextButton(
                    onClick = launchCamera,
                    modifier = Modifier.testTag("launch-place-camera")
                ) {
                    Text("Take Photo")
                }
            }
        }

        composeRule.onNodeWithTag("launch-place-camera").performClick()
        composeRule.waitForIdle()
        val requestCode = requireNotNull(registry.lastRequestCode)
        val tempFile = requireNotNull(capturedFile)
        tempFile.writeBytes(jpegPayload)
        assertThat(tempFile.exists()).isTrue()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            registry.dispatchDeferredResult(requestCode, true)
        }
        composeRule.waitUntil(5_000L) {
            shadowOf(context.mainLooper).idle()
            stagedBytes != null
        }

        assertThat(stagedBytes).isEqualTo(jpegPayload)
        assertThat(tempFile.exists()).isFalse()
    }

    private class DeferredActivityResultRegistry : ActivityResultRegistry() {
        var lastRequestCode: Int? = null
            private set

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            lastRequestCode = requestCode
        }

        fun <O> dispatchDeferredResult(requestCode: Int, result: O) {
            dispatchResult(requestCode, result)
        }
    }
}
