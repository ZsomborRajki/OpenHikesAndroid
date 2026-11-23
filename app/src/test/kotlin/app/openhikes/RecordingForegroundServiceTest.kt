package app.openhikes

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.location.LocationPermissionStatus
import app.openhikes.core.location.RecordingController
import app.openhikes.core.location.RecordingForegroundService
import app.openhikes.core.location.RecordingNotificationBuilder
import app.openhikes.core.location.di.RecordingJournalDir
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.testing.android.FakeLocationFeed
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class)
class RecordingForegroundServiceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var recordingController: RecordingController

    @Inject
    lateinit var locationFeed: FakeLocationFeed

    @Inject
    @RecordingJournalDir
    lateinit var journalDir: File

    @Before
    fun setUp() {
        hiltRule.inject()
        locationFeed.setPermission(LocationPermissionStatus.GRANTED_FINE)
    }

    @After
    fun tearDown() = runBlocking {
        recordingController.discard()
        journalDir.deleteRecursively()
        Unit
    }

    @Test
    fun actionStartPromotesLocationForegroundServiceWithOngoingNotification() = runBlocking {
        recordingController.start()
        val context = ApplicationProvider.getApplicationContext<Context>()

        @Suppress("DEPRECATION")
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, RecordingForegroundService::class.java),
            0
        )
        assertThat(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            .isNotEqualTo(0)

        val controller = Robolectric.buildService(
            RecordingForegroundService::class.java,
            Intent(RecordingForegroundService.ACTION_START)
        )
        val service = controller.create().startCommand(0, 0).get()
        val shadowService = shadowOf(service)

        assertThat(shadowService.lastForegroundNotificationId)
            .isEqualTo(RecordingNotificationBuilder.NOTIFICATION_ID)
        val notification = checkNotNull(shadowService.lastForegroundNotification)
        assertThat(NotificationCompat.getActionCount(notification)).isEqualTo(1)
        assertThat(NotificationCompat.getAction(notification, 0)?.title?.toString()).isEqualTo("Pause")
        controller.destroy()
        Unit
    }

    @Test
    fun pauseResumeAndStopActionsManageForegroundLifecycle() = runBlocking {
        recordingController.start()
        val serviceController = Robolectric.buildService(RecordingForegroundService::class.java).create()
        val service = serviceController.get()
        val shadowService = shadowOf(service)

        service.onStartCommand(Intent(RecordingForegroundService.ACTION_START), Service.START_FLAG_RETRY, 0)
        assertThat(shadowService.isStoppedBySelf).isFalse()

        service.onStartCommand(Intent(RecordingForegroundService.ACTION_PAUSE), 0, 1)
        waitUntilPhase(RecordingPhase.PAUSED)

        service.onStartCommand(Intent(RecordingForegroundService.ACTION_RESUME), 0, 2)
        waitUntilPhase(RecordingPhase.WAITING_FOR_FIX)

        service.onStartCommand(Intent(RecordingForegroundService.ACTION_STOP_SERVICE), 0, 3)
        ShadowLooper.idleMainLooper()
        assertThat(shadowService.isForegroundStopped).isTrue()
        assertThat(shadowService.isStoppedBySelf).isTrue()
        serviceController.destroy()
        Unit
    }

    private suspend fun waitUntilPhase(target: RecordingPhase) {
        val deadlineNanos = System.nanoTime() + PHASE_WAIT_NANOS
        while (recordingController.state.value.phase != target && System.nanoTime() < deadlineNanos) {
            ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(POLL_INTERVAL_MILLIS)
        }
        assertThat(recordingController.state.value.phase).isEqualTo(target)
    }

    private companion object {
        const val PHASE_WAIT_NANOS = 3_000_000_000L
        const val POLL_INTERVAL_MILLIS = 15L
    }
}
