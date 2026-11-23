package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveUpdatePolicyTest {
    private val baseMillis = 1_767_225_600_000L

    @Test
    fun enforcesPrecedenceThrottlingFlipFloorsAndPauseBypass() {
        val arbiter = LiveUpdateArbiter()
        val follow = LiveUpdatePayload(
            kind = LiveUpdateKind.FOLLOWING,
            sessionId = "follow-1",
            title = "Ridge Trail",
            runState = LiveUpdateRunState.FOLLOWING,
            distanceMeters = 500.0
        )
        assertThat(arbiter.submit(follow, baseMillis)).isEqualTo(LiveUpdateDecision.POST_IMMEDIATE)

        val rec1 = LiveUpdatePayload(
            kind = LiveUpdateKind.RECORDING,
            sessionId = "rec-1",
            title = "Morning Hike",
            runState = LiveUpdateRunState.RECORDING,
            distanceMeters = 100.0
        )
        assertThat(arbiter.submit(rec1, baseMillis + 1_000L)).isEqualTo(LiveUpdateDecision.POST_IMMEDIATE)
        // Recording outranks following while active
        assertThat(arbiter.submit(follow.copy(distanceMeters = 900.0), baseMillis + 25_000L))
            .isEqualTo(LiveUpdateDecision.SUPPRESS)

        // Small update under 20s floor suppressed
        assertThat(arbiter.submit(rec1.copy(distanceMeters = 115.0), baseMillis + 10_000L))
            .isEqualTo(LiveUpdateDecision.SUPPRESS)

        // Update >= 20s and >= 25m delta posts
        assertThat(arbiter.submit(rec1.copy(distanceMeters = 130.0), baseMillis + 22_000L))
            .isEqualTo(LiveUpdateDecision.POST_IMMEDIATE)

        // Pause transition stops fix stream and bypasses throttle immediately
        assertThat(
            arbiter.submit(
                rec1.copy(distanceMeters = 130.0, runState = LiveUpdateRunState.PAUSED),
                baseMillis + 23_000L
            )
        ).isEqualTo(LiveUpdateDecision.POST_IMMEDIATE)

        // Ending recording restores suppressed following notification
        val restored = arbiter.end(LiveUpdateKind.RECORDING, baseMillis + 30_000L)
        assertThat(restored?.sessionId).isEqualTo("follow-1")
        assertThat(arbiter.activeKind).isEqualTo(LiveUpdateKind.FOLLOWING)
    }
}
