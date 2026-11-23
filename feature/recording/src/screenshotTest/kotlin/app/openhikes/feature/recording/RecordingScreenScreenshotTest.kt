package app.openhikes.feature.recording

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RouteSegment
import com.android.tools.screenshot.PreviewTest
import kotlinx.coroutines.flow.MutableStateFlow

@PreviewTest
@Preview(name = "Recording Idle Light", showBackground = true, widthDp = 411, heightDp = 700)
@Composable
fun RecordingIdleLightPreview() {
    RecordingPreviewHost(
        darkTheme = false,
        uiState = RecordingUiState(session = RecordingSessionState(phase = RecordingPhase.IDLE)),
        stats = RecordingStatsSnapshot()
    )
}

@PreviewTest
@Preview(name = "Recording Active Light", showBackground = true, widthDp = 411, heightDp = 700)
@Composable
fun RecordingActiveLightPreview() {
    RecordingPreviewHost(
        darkTheme = false,
        uiState = RecordingUiState(
            session = RecordingSessionState(
                phase = RecordingPhase.RECORDING,
                sessionId = "shot-active",
                energyProfile = RecordingEnergyProfile.Precise,
                recoveryState = RecordingRecoveryState.Resumed
            )
        ),
        stats = RecordingStatsSnapshot(
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

@PreviewTest
@Preview(name = "Recording Paused Dark", showBackground = true, widthDp = 411, heightDp = 700)
@Composable
fun RecordingPausedDarkPreview() {
    RecordingPreviewHost(
        darkTheme = true,
        uiState = RecordingUiState(
            session = RecordingSessionState(
                phase = RecordingPhase.PAUSED,
                sessionId = "shot-paused",
                isAutoPaused = true
            )
        ),
        stats = RecordingStatsSnapshot(
            distanceMeters = 1_950.0,
            recordedDurationSeconds = 1_500.0,
            movingSeconds = 1_320.0,
            elevationGainMeters = 115.0,
            horizontalAccuracyMeters = 6.0,
            pointCount = 56
        )
    )
}

@PreviewTest
@Preview(name = "Recording Retry Save Light", showBackground = true, widthDp = 411, heightDp = 700)
@Composable
fun RecordingRetrySaveLightPreview() {
    RecordingPreviewHost(
        darkTheme = false,
        uiState = RecordingUiState(
            session = RecordingSessionState(
                phase = RecordingPhase.FAILED,
                sessionId = "shot-retry",
                failure = RecordingFailure.Save("Database busy"),
                canRetrySave = true
            )
        ),
        stats = RecordingStatsSnapshot(
            distanceMeters = 1_240.0,
            recordedDurationSeconds = 900.0,
            movingSeconds = 850.0,
            pointCount = 34
        )
    )
}

@PreviewTest
@Preview(name = "Recording Large Font 200", showBackground = true, widthDp = 411, heightDp = 891, fontScale = 2.0f)
@Composable
fun RecordingLargeFontPreview() {
    RecordingPreviewHost(
        darkTheme = false,
        uiState = RecordingUiState(
            session = RecordingSessionState(
                phase = RecordingPhase.PAUSED,
                sessionId = "shot-a11y"
            )
        ),
        stats = RecordingStatsSnapshot(
            distanceMeters = 2_500.0,
            recordedDurationSeconds = 1_800.0,
            movingSeconds = 1_650.0,
            elevationGainMeters = 180.0,
            averageSpeedMps = 1.5,
            recentSpeedMps = 1.4,
            horizontalAccuracyMeters = 5.0,
            pointCount = 64
        )
    )
}

@Composable
private fun RecordingPreviewHost(
    darkTheme: Boolean,
    uiState: RecordingUiState,
    stats: RecordingStatsSnapshot
) {
    val statsFlow = remember(stats) { MutableStateFlow(stats) }
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            RecordingScreen(
                uiState = uiState,
                statsFlow = statsFlow,
                elapsedSecondsProvider = { stats.recordedDurationSeconds },
                onBack = {},
                onAction = {}
            )
        }
    }
}
