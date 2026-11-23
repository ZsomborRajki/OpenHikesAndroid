package app.openhikes.core.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam decoupling recording engine lifecycle from [RecordingForegroundService] intent dispatch.
 */
interface RecordingServiceBridge {
    fun ensureStarted()

    fun stopService()
}

@Singleton
internal class AndroidRecordingServiceBridge @Inject constructor(@ApplicationContext private val context: Context) :
    RecordingServiceBridge {
    override fun ensureStarted() {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stopService() {
        val intent = Intent(context, RecordingForegroundService::class.java)
        context.stopService(intent)
    }
}
