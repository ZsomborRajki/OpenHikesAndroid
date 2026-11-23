package app.openhikes.core.location

import app.openhikes.core.model.RecordingMotionState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Injectable motion state feed supplying pedestrian / stationary / carried classification when available.
 */
interface MotionFeed {
    val motionState: StateFlow<RecordingMotionState>

    fun start()

    fun stop()
}

/**
 * Default [MotionFeed] reporting [RecordingMotionState.UNKNOWN] when activity recognition
 * sensors are unavailable or ungranted; auto-pause operates cleanly on positions alone.
 */
@Singleton
internal class DefaultMotionFeed @Inject constructor() : MotionFeed {
    private val state = MutableStateFlow(RecordingMotionState.UNKNOWN)

    override val motionState: StateFlow<RecordingMotionState> = state.asStateFlow()

    override fun start() {
        state.value = RecordingMotionState.UNKNOWN
    }

    override fun stop() {
        state.value = RecordingMotionState.UNKNOWN
    }
}
