package app.openhikes.core.testing.android

import app.openhikes.core.location.RecordingServiceBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [RecordingServiceBridge] fake counting foreground-service start/stop requests.
 */
@Singleton
class FakeRecordingServiceBridge @Inject constructor() : RecordingServiceBridge {
    var ensureStartedCount: Int = 0
        private set

    var stopServiceCount: Int = 0
        private set

    var isServiceRunning: Boolean = false
        private set

    override fun ensureStarted() {
        ensureStartedCount += 1
        isServiceRunning = true
    }

    override fun stopService() {
        stopServiceCount += 1
        isServiceRunning = false
    }
}
