package app.openhikes.feature.recording

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.location.RecordingSessionState
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingRecoverySummary
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RouteSegment
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port")
class RecordingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controlsReflectPhaseAndDiscardIsHiddenWhileActivelyRecording() {
        val uiState = MutableStateFlow(RecordingUiState(session = RecordingSessionState(phase = RecordingPhase.IDLE)))
        val statsFlow = MutableStateFlow(RecordingStatsSnapshot())
        val actions = mutableListOf<RecordingAction>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RecordingScreen(
                    uiState = RecordingUiState(session = RecordingSessionState(phase = RecordingPhase.RECORDING)),
                    statsFlow = statsFlow,
                    elapsedSecondsProvider = { 120.0 },
                    onBack = {},
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_PAUSE_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON).assertDoesNotExist()
        assertThat(uiState.value.session.phase).isEqualTo(RecordingPhase.IDLE)
    }

    @Test
    fun pausedPhaseExposesResumeStopDiscardAndMultipleSegments() {
        val statsFlow = MutableStateFlow(
            RecordingStatsSnapshot(
                distanceMeters = 1_450.0,
                recordedDurationSeconds = 960.0,
                movingSeconds = 800.0,
                pointCount = 12,
                segments = listOf(
                    RouteSegment(distanceMeters = 800.0, durationSeconds = 420.0, stopSeconds = 160.0),
                    RouteSegment(distanceMeters = 650.0, durationSeconds = 380.0)
                )
            )
        )
        val actions = mutableListOf<RecordingAction>()

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RecordingScreen(
                    uiState = RecordingUiState(
                        session = RecordingSessionState(
                            phase = RecordingPhase.PAUSED,
                            sessionId = "rec-paused",
                            isAutoPaused = true,
                            recoveryState = RecordingRecoveryState.NeedsDecision(
                                RecordingRecoverySummary(
                                    startedAtEpochMillis = 1_781_510_400_000L,
                                    lastUpdatedAtEpochMillis = 1_781_511_000_000L,
                                    distanceMeters = 1_450.0,
                                    pointCount = 12
                                )
                            )
                        )
                    ),
                    statsFlow = statsFlow,
                    elapsedSecondsProvider = { 960.0 },
                    onBack = {},
                    onAction = actions::add
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_RESUME_BUTTON).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_DISCARD_BUTTON).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_RECOVERY_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_CONDITIONS_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeSegment(1)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.hikeSegment(2)).performScrollTo().assertIsDisplayed()

        assertThat(actions).containsExactly(RecordingAction.Resume, RecordingAction.RequestDiscard).inOrder()
    }

    @Test
    fun stopDialogTypingAndRetryBannerEmitExpectedActions() {
        val statsFlow = MutableStateFlow(RecordingStatsSnapshot(distanceMeters = 400.0, pointCount = 4))
        val actions = mutableListOf<RecordingAction>()
        var uiState by mutableStateOf(
            RecordingUiState(
                session = RecordingSessionState(
                    phase = RecordingPhase.FAILED,
                    sessionId = "rec-fail",
                    failure = RecordingFailure.Save("Failed"),
                    canRetrySave = true
                )
            )
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RecordingScreen(
                    uiState = uiState,
                    statsFlow = statsFlow,
                    elapsedSecondsProvider = { 300.0 },
                    onBack = {},
                    onAction = { action ->
                        actions.add(action)
                        if (action is RecordingAction.StopNameChanged) {
                            uiState = uiState.copy(stopNameDraft = action.value)
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_RETRY_SAVE).assertIsDisplayed().performClick()

        val offered = "Morning Hike – Jun 15, 2026"
        uiState = uiState.copy(
            stopDialogStep = RecordingStopStep.ASK_NAME,
            offeredTitle = offered,
            stopNameDraft = offered
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.RECORDING_STOP_NAME_FIELD).performTextReplacement("Alpine Loop")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(actions.any { it is RecordingAction.RetrySave }).isTrue()
        assertThat(uiState.stopNameDraft).isEqualTo("Alpine Loop")
        assertThat(actions.last()).isEqualTo(RecordingAction.ConfirmStopSave)
    }
}
