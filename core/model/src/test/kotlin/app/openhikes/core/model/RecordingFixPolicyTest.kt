package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingFixPolicyTest {
    private val baseMillis = 1_767_225_600_000L

    @Test
    fun rejectsStaleInaccurateNonRepresentableAndDuplicateTimestampFixes() {
        val fresh = LocationFix(
            latitude = 47.7185,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis,
            horizontalAccuracyMeters = 8.0
        )
        assertThat(RecordingFixPolicy.accepts(fresh, afterPrevious = null, nowEpochMillis = baseMillis)).isTrue()

        val stale = fresh.copy(timestampEpochMillis = baseMillis - 31_000L)
        assertThat(RecordingFixPolicy.accepts(stale, afterPrevious = null, nowEpochMillis = baseMillis)).isFalse()

        val futureTooFar = fresh.copy(timestampEpochMillis = baseMillis + 6_000L)
        assertThat(
            RecordingFixPolicy.accepts(futureTooFar, afterPrevious = null, nowEpochMillis = baseMillis)
        ).isFalse()

        val loose = fresh.copy(horizontalAccuracyMeters = 51.0)
        assertThat(RecordingFixPolicy.accepts(loose, afterPrevious = null, nowEpochMillis = baseMillis)).isFalse()

        val polar = fresh.copy(latitude = 89.0)
        assertThat(RecordingFixPolicy.accepts(polar, afterPrevious = null, nowEpochMillis = baseMillis)).isFalse()

        val previous = RecordingPoint(
            latitude = 47.7185,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis,
            horizontalAccuracyMeters = 8.0,
            courseDegrees = 0.0
        )
        val sameTime = fresh.copy(latitude = 47.7190, timestampEpochMillis = baseMillis)
        assertThat(
            RecordingFixPolicy.accepts(sameTime, afterPrevious = previous, nowEpochMillis = baseMillis)
        ).isFalse()
    }

    @Test
    fun gatesSpeedSpikesUnlessCorroboratedNonPedestrianOrPastHeartbeat() {
        val previous = RecordingPoint(
            latitude = 47.7185,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis,
            horizontalAccuracyMeters = 6.0,
            courseDegrees = 10.0
        )
        // ~111 m north in 2 s => ~55 m/s spike without corroboration
        val spike = LocationFix(
            latitude = 47.7195,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis + 2_000L,
            horizontalAccuracyMeters = 6.0,
            speedMps = 1.2
        )
        assertThat(
            RecordingFixPolicy.accepts(
                fix = spike,
                afterPrevious = previous,
                motionState = RecordingMotionState.PEDESTRIAN,
                nowEpochMillis = baseMillis + 2_000L
            )
        ).isFalse()

        // Corroborated reported speed admits fast segment
        val corroborated = spike.copy(speedMps = 52.0)
        assertThat(
            RecordingFixPolicy.accepts(
                fix = corroborated,
                afterPrevious = previous,
                motionState = RecordingMotionState.PEDESTRIAN,
                nowEpochMillis = baseMillis + 2_000L
            )
        ).isTrue()

        // Non-pedestrian mode admits fast move
        assertThat(
            RecordingFixPolicy.accepts(
                fix = spike,
                afterPrevious = previous,
                motionState = RecordingMotionState.NON_PEDESTRIAN,
                nowEpochMillis = baseMillis + 2_000L
            )
        ).isTrue()

        // Cold-start recovery after >= 10 s heartbeat admits re-anchor
        val late = spike.copy(timestampEpochMillis = baseMillis + 11_000L)
        assertThat(
            RecordingFixPolicy.accepts(
                fix = late,
                afterPrevious = previous,
                motionState = RecordingMotionState.PEDESTRIAN,
                nowEpochMillis = baseMillis + 11_000L
            )
        ).isTrue()
    }

    @Test
    fun gatesMicroStepsUnlessHeadingTurnedPastThreshold() {
        val previous = RecordingPoint(
            latitude = 47.7185,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis,
            horizontalAccuracyMeters = 6.0,
            courseDegrees = 10.0
        )
        // Small displacement below 5 m gate rejected unless >= 10s or bearing turns > 15 deg
        val microStep = LocationFix(
            latitude = 47.71851,
            longitude = 12.8317,
            timestampEpochMillis = baseMillis + 3_000L,
            horizontalAccuracyMeters = 6.0,
            courseDegrees = 10.0,
            speedMps = 1.0
        )
        assertThat(
            RecordingFixPolicy.accepts(
                fix = microStep,
                afterPrevious = previous,
                nowEpochMillis = baseMillis + 3_000L
            )
        ).isFalse()

        val turnInPlace = microStep.copy(courseDegrees = 45.0, courseAccuracyDegrees = 10.0)
        assertThat(
            RecordingFixPolicy.accepts(
                fix = turnInPlace,
                afterPrevious = previous,
                nowEpochMillis = baseMillis + 3_000L
            )
        ).isTrue()
    }

    @Test
    fun altitudePolicyAndFixWindowEnforceVerticalAccuracyAndLegStart() {
        assertThat(AltitudePolicy.trustedAltitude(540.0, 15.0)).isEqualTo(540.0)
        assertThat(AltitudePolicy.trustedAltitude(540.0, 15.1)).isNull()
        assertThat(AltitudePolicy.trustedAltitude(540.0, -1.0)).isNull()
        assertThat(AltitudePolicy.trustedAltitude(Double.POSITIVE_INFINITY, 5.0)).isNull()

        val window = RecordingFixWindow(baseMillis)
        assertThat(window.admits(baseMillis - 1L)).isFalse()
        assertThat(window.admits(baseMillis)).isTrue()
        window.reopen(baseMillis + 60_000L)
        window.reopen(baseMillis + 10_000L) // Never moves backwards
        assertThat(window.opensAtEpochMillis).isEqualTo(baseMillis + 60_000L)
        assertThat(window.admits(baseMillis + 59_999L)).isFalse()
        assertThat(window.admits(baseMillis + 60_000L)).isTrue()
    }
}
