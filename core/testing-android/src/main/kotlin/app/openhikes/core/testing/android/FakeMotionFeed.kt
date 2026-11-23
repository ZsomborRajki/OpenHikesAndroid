package app.openhikes.core.testing.android

import app.openhikes.core.location.MotionFeed
import app.openhikes.core.model.RecordingMotionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [MotionFeed] fake for testing auto-pause and motion-flag transitions.
 */
@Singleton
class FakeMotionFeed @Inject constructor() : MotionFeed {
    private val state = MutableStateFlow(RecordingMotionState.UNKNOWN)

    override val motionState: StateFlow<RecordingMotionState> = state.asStateFlow()

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    fun setMotionState(motion: RecordingMotionState) {
        state.value = motion
    }

    override fun start() {
        startCount += 1
    }

    override fun stop() {
        stopCount += 1
        state.value = RecordingMotionState.UNKNOWN
    }
}
